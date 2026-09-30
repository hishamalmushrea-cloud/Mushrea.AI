package com.mushrea.code.device.mirror

import android.content.Context
import android.view.Surface
import com.mushrea.code.core.util.safeMessage
import com.mushrea.code.device.usb.UsbDeviceAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One live view-only mirror of the other phone's screen, carried by the device's own
 * `screenrecord --output-format=h264` streaming over a single ADB exec stream — no server
 * binary, no root, nothing installed on the other phone.
 *
 * The system caps every screenrecord take at ~3 minutes, so the session reopens the take on its
 * own and says so in its state. Failure handling is honest: a dead connection is rebuilt (a few
 * times), and when the phone is gone the session reports failure instead of pretending.
 *
 * View-only by design: the mirror carries no touch or key injection.
 */
object ScreenMirrorSession {
    sealed interface State {
        data object Idle : State

        data object Starting : State

        data class Live(
            val take: Int,
        ) : State

        data class Reconnecting(
            val take: Int,
            val reason: String,
        ) : State

        data class Failed(
            val reason: String,
        ) : State

        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var scope: CoroutineScope? = null
    private var connection: UsbDeviceAgent.PersistentAdbConnection? = null
    private var decoder: MirrorDecoder? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var stopping = false

    /** True while a session is starting, live, or reconnecting. */
    val isActiveSession: Boolean
        get() =
            when (_state.value) {
                is State.Starting, is State.Live, is State.Reconnecting -> true
                else -> false
            }

    /** Builds the streaming command; internal so tests can pin the exact contract. */
    internal fun screenrecordCommand(
        width: Int,
        height: Int,
        bitRate: Int,
        takeSeconds: Int,
    ): String {
        require(width in MIN_DIMENSION..MAX_DIMENSION && width % 2 == 0) { "width must be an even 128..1920" }
        require(height in MIN_DIMENSION..MAX_DIMENSION && height % 2 == 0) { "height must be an even 128..1920" }
        require(bitRate in MIN_BIT_RATE..MAX_BIT_RATE) { "bit rate must be 1..20 Mbps" }
        require(takeSeconds in 10..TAKE_SECONDS) { "take length must be 10..$TAKE_SECONDS seconds" }
        return "screenrecord --output-format=h264 --size ${width}x$height --bit-rate $bitRate --time-limit $takeSeconds -"
    }

    /** Starts mirroring the first attached ADB phone; no-op while a session already runs. */
    fun start(
        context: Context,
        agent: UsbDeviceAgent,
    ) {
        if (isActiveSession) return
        stopping = false
        _state.value = State.Starting
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            runLoop(context.applicationContext, agent)
        }
    }

    /** The mirror renders here; called by the display activity's SurfaceHolder. */
    fun attachSurface(newSurface: Surface) {
        surface = newSurface
    }

    fun detachSurface() {
        surface = null
    }

    /** Stops everything; safe to call from any state. */
    fun stop() {
        stopping = true
        scope?.cancel()
        scope = null
        cleanup()
        _state.value = State.Stopped
    }

    private suspend fun runLoop(
        context: Context,
        agent: UsbDeviceAgent,
    ) {
        var take = 0
        var consecutiveFailures = 0
        try {
            while (currentCoroutineContext().isActive && !stopping) {
                take++
                val currentSurface =
                    awaitSurface() ?: run {
                        _state.value = State.Failed("the mirror screen did not open")
                        return
                    }
                try {
                    val activeConnection =
                        connection
                            ?: agent.openPersistentConnection().also { connection = it }
                    val activeDecoder = decoder ?: newDecoder(currentSurface).also { decoder = it }
                    val stream =
                        activeConnection.client.openStream(
                            "exec:" + screenrecordCommand(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_BIT_RATE, TAKE_SECONDS),
                        )
                    consecutiveFailures = 0
                    _state.value = State.Live(take)
                    val splitter = H264AccessUnitSplitter()
                    val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
                    while (currentCoroutineContext().isActive && !stopping) {
                        val chunk = stream.receive(deadline) ?: break
                        splitter.feed(chunk).forEach { unit -> activeDecoder.submit(unit) }
                    }
                    stream.closeQuietly()
                    splitter.flush().forEach { unit -> activeDecoder.submit(unit) }
                    if (!stopping && currentCoroutineContext().isActive) {
                        // The system ended the take (its ~3 minute cap); reopen without drama.
                        _state.value = State.Reconnecting(take, "system take limit")
                    }
                } catch (t: Throwable) {
                    if (stopping || !currentCoroutineContext().isActive) break
                    runCatching { connection?.close() }
                    connection = null
                    decoder?.release()
                    decoder = null
                    consecutiveFailures++
                    val reason = t.safeMessage("mirror stream failed")
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        _state.value = State.Failed(reason)
                        return
                    }
                    _state.value = State.Reconnecting(take, reason)
                }
                delay(RESTART_DELAY_MS)
            }
        } finally {
            if (stopping) {
                cleanup()
            } else {
                cleanup()
                if (_state.value !is State.Failed) _state.value = State.Stopped
            }
        }
    }

    private suspend fun awaitSurface(): Surface? {
        val deadline = System.currentTimeMillis() + SURFACE_WAIT_MS
        while (surface == null) {
            if (!currentCoroutineContext().isActive || stopping) return null
            if (System.currentTimeMillis() > deadline) return null
            delay(SURFACE_POLL_MS)
        }
        return surface
    }

    private fun newDecoder(currentSurface: Surface): MirrorDecoder = MirrorDecoder().apply { begin(currentSurface) }

    private fun cleanup() {
        decoder?.release()
        decoder = null
        runCatching { connection?.close() }
        connection = null
        surface = null
    }

    private companion object {
        const val MIN_DIMENSION = 128
        const val MAX_DIMENSION = 1920
        const val MIN_BIT_RATE = 1_000_000
        const val MAX_BIT_RATE = 20_000_000

        /** Just under the device-enforced ~180 s cap, so we end the take before the system does. */
        const val TAKE_SECONDS = 170
        const val DEFAULT_WIDTH = 854
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_BIT_RATE = 4_000_000

        /** A video chunk should arrive every fraction of a second; this is a generous stall bound. */
        const val READ_TIMEOUT_MS = 30_000L
        const val RESTART_DELAY_MS = 800L
        const val SURFACE_WAIT_MS = 20_000L
        const val SURFACE_POLL_MS = 100L
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
