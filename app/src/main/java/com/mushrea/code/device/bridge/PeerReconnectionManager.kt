package com.mushrea.code.device.bridge

import com.mushrea.code.core.connectivity.Endpoint
import com.mushrea.code.core.connectivity.EndpointSource
import com.mushrea.code.core.peer.PeerDevice
import com.mushrea.code.core.peer.PeerIdentityDigest
import kotlinx.coroutines.delay

/**
 * How hard, and how patiently, to try to get a device back.
 *
 * Wireless debugging is a *mobile* target: the phone sleeps, Wi-Fi hands over, the port changes when
 * the feature is switched on again, and the announcement stops when the radio naps. Reconnecting
 * therefore cannot be one attempt with a long timeout - it has to be a few attempts, spaced out, with
 * discovery in between, and it has to stop.
 *
 * The delays grow geometrically ([initialDelayMillis] then ×[factor]) up to [maxDelayMillis]. The
 * bound matters as much as the growth: a background loop that retries forever is exactly the battery
 * and privacy problem this project refuses to ship.
 */
data class ReconnectPolicy(
    val attempts: Int = 4,
    val initialDelayMillis: Long = 2_000,
    val maxDelayMillis: Long = 60_000,
    val factor: Int = 2,
) {
    init {
        require(attempts >= 1) { "at least one attempt is needed" }
        require(factor >= 1) { "the delay cannot shrink on retry" }
    }

    /** The wait before attempt [index] (1-based), capped at [maxDelayMillis]. */
    fun delayBefore(index: Int): Long {
        if (index <= 1) return 0
        var value = initialDelayMillis
        repeat(index - 2) { value = (value * factor).coerceAtMost(maxDelayMillis) }
        return value.coerceAtMost(maxDelayMillis)
    }

    companion object {
        /** The default used when the owner asks for a reconnect: a few tries, under a minute and a half. */
        val default: ReconnectPolicy = ReconnectPolicy()

        /** A single, immediate attempt - for a call that must answer now. */
        val once: ReconnectPolicy = ReconnectPolicy(attempts = 1)
    }
}

/** Where a reconnection attempt is: the same ladder, every time, in the report. */
enum class ReconnectionState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    VERIFYING,

    /** This route did not answer (or answered as somebody else); another one may. */
    FAILED_ATTEMPT,
    READY,
    GAVE_UP,
}

/** One attempt, with what it was doing and what it learned. */
data class ReconnectionStep(
    val index: Int,
    val state: ReconnectionState,
    val detail: String,
    val endpoint: Endpoint? = null,
    val waitedMillis: Long = 0,
)

/**
 * The outcome of a reconnection run.
 *
 * [state] is [ReconnectionState.READY] only when a real command answered through the channel - an
 * `adb connect` that returned success is a socket, not a device, and the ladder deliberately has a
 * [ReconnectionState.VERIFYING] rung between the two.
 */
data class ReconnectionReport(
    val serial: String,
    val state: ReconnectionState,
    val steps: List<ReconnectionStep> = emptyList(),
    val device: PeerDevice? = null,
    val reason: String = "",
) {
    val ready: Boolean get() = state == ReconnectionState.READY
    val waitedMillis: Long get() = steps.sumOf { it.waitedMillis }

    fun summary(): String =
        if (ready) {
            "reconnected to $serial in ${steps.size} attempt(s)"
        } else {
            "could not reconnect to $serial: ${reason.ifBlank { "no route answered" }}"
        }
}

/**
 * Gets a known device back, in the order that wastes the least: an address that worked before, then
 * discovery, then a longer wait - never a tight loop.
 *
 * Everything the manager needs is injected, which is what keeps it honest and testable:
 *  * [discover] asks the transport where the device is *now* (an mDNS announcement, a hand-over);
 *  * [connect] runs the transport's own connect-and-verify and answers with the device it proved.
 * The manager itself never touches `adb`, mDNS or a socket - it decides *when* and *in what order*, and
 * it is the only place that decides how long to wait.
 *
 * Identity is enforced, not assumed: when both the remembered device and the freshly connected device
 * have an identity digest and the two differ, the route is not adopted. A phone that happens to occupy
 * a stale address is not this phone, and silently using it is how a tool ends up running commands on
 * the wrong device.
 */
class PeerReconnectionManager(
    private val device: (String) -> PeerDevice?,
    private val discover: suspend (PeerDevice) -> List<Endpoint>,
    private val connect: suspend (PeerDevice, Endpoint) -> Result<PeerDevice>,
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val onAttempt: (ReconnectionStep) -> Unit = {},
) {
    suspend fun reconnect(
        serial: String,
        policy: ReconnectPolicy = ReconnectPolicy.default,
        extraEndpoints: List<Endpoint> = emptyList(),
    ): ReconnectionReport {
        val known = device(serial)
            ?: return ReconnectionReport(serial, ReconnectionState.GAVE_UP, reason = "no device with that identity")
        val steps = mutableListOf<ReconnectionStep>()
        val tried = mutableSetOf<String>()
        var lastFailure = ""

        for (index in 1..policy.attempts) {
            val wait = policy.delayBefore(index)
            if (wait > 0) sleep(wait)
            val candidates = candidatesFor(known, index, extraEndpoints, tried)
            if (candidates.isEmpty()) {
                val step = ReconnectionStep(index, ReconnectionState.DISCOVERING, "no route to try", waitedMillis = wait)
                steps += step
                onAttempt(step)
                lastFailure = "no route to try"
                continue
            }
            for (endpoint in candidates) {
                tried += endpoint.key
                val connecting = ReconnectionStep(index, ReconnectionState.CONNECTING, "trying $endpoint", endpoint, wait)
                steps += connecting
                onAttempt(connecting)
                val result = runCatching { connect(known, endpoint) }.getOrElse { Result.failure(it) }
                val deviceResult = result.getOrNull()
                if (deviceResult == null) {
                    lastFailure = result.exceptionOrNull()?.message ?: "connect failed"
                    val failed = ReconnectionStep(index, ReconnectionState.FAILED_ATTEMPT, lastFailure, endpoint)
                    steps += failed
                    onAttempt(failed)
                    continue
                }
                if (identityChanged(known, deviceResult)) {
                    lastFailure = "a different device answered at $endpoint"
                    val refused = ReconnectionStep(index, ReconnectionState.FAILED_ATTEMPT, lastFailure, endpoint)
                    steps += refused
                    onAttempt(refused)
                    continue
                }
                val verifying = ReconnectionStep(index, ReconnectionState.VERIFYING, "a command answered at $endpoint", endpoint)
                steps += verifying
                onAttempt(verifying)
                return ReconnectionReport(serial, ReconnectionState.READY, steps, deviceResult)
            }
        }
        return ReconnectionReport(serial, ReconnectionState.GAVE_UP, steps, reason = lastFailure.ifBlank { "no route answered" })
    }

    /**
     * The routes for this attempt: the first attempt starts from memory, and only a failed one is worth
     * a discovery round.
     *
     * That ordering is the whole cost story. Asking the transport to discover means restarting an mDNS
     * browse and waiting for an announcement, which is exactly the work that must not happen on every
     * retry of a phone that is simply asleep in the next room. So attempt one is cheap and often right;
     * from attempt two the discovery answers are tried first (they are the newest truth), and anything
     * already tried in this run is not retried.
     */
    private suspend fun candidatesFor(
        known: PeerDevice,
        attempt: Int,
        extraEndpoints: List<Endpoint>,
        tried: Set<String>,
    ): List<Endpoint> {
        val remembered = remember(known) + extraEndpoints
        val ordered =
            if (attempt == 1) {
                remembered
            } else {
                runCatching { discover(known) }.getOrDefault(emptyList()) + remembered
            }
        return ordered.filter { it.usable && it.key !in tried }.distinctBy(Endpoint::key)
    }

    private fun remember(known: PeerDevice): List<Endpoint> {
        val current =
            if (known.host.isNotBlank() && known.port > 0) {
                listOf(Endpoint(known.host, known.port, EndpointSource.REMEMBERED))
            } else {
                emptyList()
            }
        return current + known.endpoints()
    }

    private fun identityChanged(
        known: PeerDevice,
        connected: PeerDevice,
    ): Boolean {
        val before = known.identityKey.ifBlank { PeerIdentityDigest.of(known.serial) }
        val after = connected.identityKey.ifBlank { PeerIdentityDigest.of(connected.serial) }
        return before.isNotBlank() && after.isNotBlank() && before != after
    }
}
