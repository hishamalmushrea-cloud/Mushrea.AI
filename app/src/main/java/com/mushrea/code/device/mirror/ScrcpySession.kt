package com.mushrea.code.device.mirror

import android.content.Context
import android.view.Surface
import com.mushrea.code.core.util.safeMessage
import com.mushrea.code.device.usb.AdbClient
import com.mushrea.code.device.usb.AdbException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The full scrcpy 4.0 session against the attached phone: the official server binary is pushed
 * to /data/local/tmp, started through app_process, and driven over three adb streams — video
 * (H.264 in), control (touch/key/scroll out) — with audio disabled.
 *
 * Control is real: touches on the display activity are injected on the other phone through the
 * server (the standard scrcpy path, running with shell privileges the user granted to ADB).
 * The server binary self-deletes on a clean exit (the server's own cleanup).
 */
object ScrcpySession {
    sealed interface State {
        data object Idle : State

        data object Starting : State

        data class Live(
            val deviceName: String,
        ) : State

        data class Reconnecting(
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
    private var videoStream: AdbClient.AdbStream? = null
    private var controlStream: AdbClient.AdbStream? = null
    private var serverStream: AdbClient.AdbStream? = null
    private var decoder: MirrorDecoder? = null
    private val controlMutex = Mutex()

    /** Latest reported frame size from the stream's session packets; the touch mapping target. */
    @Volatile
    var frameWidth: Int = DEFAULT_WIDTH
        private set

    @Volatile
    var frameHeight: Int = DEFAULT_HEIGHT
        private set

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var stopping = false

    /** True while starting, live, or reconnecting. */
    val isActiveSession: Boolean
        get() =
            when (_state.value) {
                is State.Starting, is State.Live, is State.Reconnecting -> true
                else -> false
            }

    /** Starts the scrcpy session with the server binary's bytes; no-op while already running. */
    fun start(
        context: Context,
        agent: UsbDeviceAgent,
        serverBytes: ByteArray,
    ) {
        if (isActiveSession) return
        stopping = false
        _state.value = State.Starting
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            runLoop(context.applicationContext, agent, serverBytes)
        }
    }

    /** The stream renders here; called by the activity's SurfaceHolder. */
    fun attachSurface(newSurface: Surface) {
        surface = newSurface
    }

    fun detachSurface() {
        surface = null
    }

    /** Sends one touch event; coordinates are frame pixels. */
    suspend fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        pressure: Float,
    ) {
        val width = frameWidth
        val height = frameHeight
        val message =
            ScrcpyControlMessages.injectTouch(
                action,
                pointerId,
                x.coerceIn(0, width - 1),
                y.coerceIn(0, height - 1),
                width,
                height,
                pressure,
            )
        sendControl(message)
    }

    /** Sends one scroll event; scroll values are wheel notches. */
    suspend fun injectScroll(
        x: Int,
        y: Int,
        hScroll: Float,
        vScroll: Float,
    ) {
        val message = ScrcpyControlMessages.injectScroll(x, y, frameWidth, frameHeight, hScroll, vScroll)
        sendControl(message)
    }

    /** Presses and releases one keycode on the other phone. */
    suspend fun tapKey(keycode: Int) {
        ScrcpyControlMessages.tapKey(keycode).forEach { message -> sendControl(message) }
    }

    private suspend fun sendControl(message: ByteArray) {
        val stream = controlStream ?: return
        controlMutex.withLock {
            stream.sendPayload(message, timeoutMillis = CONTROL_ACK_TIMEOUT_MS)
        }
    }

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
        serverBytes: ByteArray,
    ) {
        var consecutiveFailures = 0
        try {
            while (currentCoroutineContext().isActive && !stopping) {
                val currentSurface =
                    awaitSurface() ?: run {
                        _state.value = State.Failed("the control screen did not open")
                        return
                    }
                try {
                    val activeConnection =
                        connection
                            ?: agent.openPersistentConnection().also { connection = it }
                    // Push the server binary (its own cleanup deletes it on a clean exit).
                    agent.pushBytes(serverBytes, SERVER_REMOTE_PATH)
                    serverStream =
                        activeConnection.client.openStream(
                            "shell:CLASSPATH=$SERVER_REMOTE_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION $SERVER_OPTIONS",
                        )
                    val video = openVideoStream(activeConnection)
                    videoStream = video
                    val reader = StreamByteReader(video)
                    reader.skip(DUMMY_BYTE_LENGTH)
                    val deviceName = reader.readDeviceMeta()
                    val control = activeConnection.client.openStream("localabstract:$SOCKET_NAME")
                    controlStream = control
                    val activeDecoder =
                        decoder ?: MirrorDecoder().apply { begin(currentSurface) }.also { decoder = it }
                    readVideo(reader, deviceName, activeDecoder)
                    if (!stopping && currentCoroutineContext().isActive) {
                        _state.value = State.Reconnecting("the server exited")
                    }
                } catch (t: Throwable) {
                    if (stopping || !currentCoroutineContext().isActive) break
                    teardownStreams()
                    consecutiveFailures++
                    val reason = t.safeMessage("scrcpy session failed")
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        _state.value = State.Failed(reason)
                        return
                    }
                    _state.value = State.Reconnecting(reason)
                }
                delay(RESTART_DELAY_MS)
            }
        } finally {
            cleanup()
            if (_state.value !is State.Failed) _state.value = State.Stopped
        }
    }

    /** Opens the video socket, retrying while app_process is still booting the server. */
    private suspend fun openVideoStream(activeConnection: UsbDeviceAgent.PersistentAdbConnection): AdbClient.AdbStream {
        var lastError: Throwable? = null
        repeat(CONNECT_ATTEMPTS) {
            if (stopping) throw AdbException("stopped")
            try {
                return activeConnection.client.openStream("localabstract:$SOCKET_NAME")
            } catch (t: Throwable) {
                lastError = t
                delay(CONNECT_RETRY_MS)
            }
        }
        throw AdbException("the scrcpy server did not start listening: " + (lastError?.safeMessage("unknown")))
    }

    /**
     * Parses the video socket: codec id, then session packets (rotation) and media packets,
     * per the scrcpy 4.0 wire protocol.
     */
    private suspend fun readVideo(
        reader: StreamByteReader,
        deviceName: String,
        activeDecoder: MirrorDecoder,
    ) {
        val codecId = reader.readI32()
        if (codecId != H264_CODEC_ID) throw AdbException("unexpected video codec id 0x" + codecId.toUInt().toString(16))
        _state.value = State.Live(deviceName)
        while (currentCoroutineContext().isActive && !stopping) {
            val header = reader.readExact(12)
            val first = header[0].toInt() and 0xFF
            val pts =
                ((header[0].toLong() and 0x7F) shl 56) or
                    ((header[1].toLong() and 0xFF) shl 48) or
                    ((header[2].toLong() and 0xFF) shl 40) or
                    ((header[3].toLong() and 0xFF) shl 32) or
                    ((header[4].toLong() and 0xFF) shl 24) or
                    ((header[5].toLong() and 0xFF) shl 16) or
                    ((header[6].toLong() and 0xFF) shl 8) or
                    (header[7].toLong() and 0xFF)
            val size = reader.readI32From(header, 8)
            if (first and SESSION_FLAG != 0) {
                if (size != 8) throw AdbException("bad session packet")
                val body = reader.readExact(8)
                frameWidth = readU32(body, 0)
                frameHeight = readU32(body, 4)
                continue
            }
            val isConfig = first and CONFIG_FLAG != 0
            val isKey = first and KEY_FLAG != 0
            val payload = reader.readExact(size)
            if (pts != 0L || isConfig || isKey) {
                activeDecoder.submit(AccessUnit(payload, isConfig, isKey))
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

    private fun teardownStreams() {
        runCatching { videoStream?.closeQuietly() }
        videoStream = null
        runCatching { controlStream?.closeQuietly() }
        controlStream = null
        runCatching { serverStream?.closeQuietly() }
        serverStream = null
        decoder?.release()
        decoder = null
        runCatching { connection?.close() }
        connection = null
    }

    private fun cleanup() {
        teardownStreams()
        surface = null
    }

    private fun readU32(
        data: ByteArray,
        offset: Int,
    ): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    /** Exact-length reader over the stream's variable-size chunks. */
    private class StreamByteReader(
        private val stream: AdbClient.AdbStream,
    ) {
        private var buffer = ByteArray(0)
        private var position = 0
        private var deadline = System.currentTimeMillis() + READ_TIMEOUT_MS

        suspend fun readExact(count: Int): ByteArray {
            val result = ByteArray(count)
            var filled = 0
            while (filled < count) {
                if (position >= buffer.size) {
                    val chunk = stream.receive(deadline) ?: throw AdbException("the video stream ended")
                    buffer = chunk
                    position = 0
                    continue
                }
                val take = minOf(count - filled, buffer.size - position)
                System.arraycopy(buffer, position, result, filled, take)
                position += take
                filled += take
            }
            return result
        }

        fun readI32(): Int = readI32From(readExact(4), 0)

        fun readI32From(
            data: ByteArray,
            offset: Int,
        ): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

        /** The connection's leading dummy byte. */
        suspend fun skip(count: Int) {
            readExact(count)
        }

        /** The 64-byte zero-padded device name that opens the video socket. */
        suspend fun readDeviceMeta(): String {
            val meta = readExact(DEVICE_NAME_LENGTH)
            val trimmed = meta.takeWhile { it != ZERO_BYTE }.toByteArray()
            return String(trimmed, Charsets.UTF_8).ifBlank { "device" }
        }
    }

    private val ZERO_BYTE: Byte = 0

    private const val SESSION_FLAG = 0x80
    private const val CONFIG_FLAG = 0x40
    private const val KEY_FLAG = 0x20
    private const val H264_CODEC_ID = 0x68323634

    /** The abstract socket the server listens on when no scid is passed. */
    private const val SOCKET_NAME = "scrcpy"
    private const val SERVER_VERSION = "4.0"
    private const val SERVER_REMOTE_PATH = "/data/local/tmp/mushrea_scrcpy_server"
    private const val DUMMY_BYTE_LENGTH = 1
    private const val DEVICE_NAME_LENGTH = 64

    /** Audio off, the phone listens (we connect), H.264, sane quality, quiet logs, self-cleaning. */
    private const val SERVER_OPTIONS =
        "log_level=warn max_size=1024 video_bit_rate=4000000 max_fps=30 audio=false tunnel_forward=true send_frame_meta=true cleanup=true"

    private const val DEFAULT_WIDTH = 854
    private const val DEFAULT_HEIGHT = 480
    private const val READ_TIMEOUT_MS = 30_000L
    private const val CONTROL_ACK_TIMEOUT_MS = 10_000
    private const val RESTART_DELAY_MS = 800L
    private const val SURFACE_WAIT_MS = 20_000L
    private const val SURFACE_POLL_MS = 100L
    private const val CONNECT_ATTEMPTS = 8
    private const val CONNECT_RETRY_MS = 500L
    private const val MAX_CONSECUTIVE_FAILURES = 3
}
