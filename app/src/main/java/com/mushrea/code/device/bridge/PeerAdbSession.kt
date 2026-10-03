package com.mushrea.code.device.bridge

import com.mushrea.code.runtime.local.AdbShellRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** A failure that already knows its vocabulary, so callers stop re-guessing from the text. */
class PeerAdbFailure(
    val error: PeerAdbError,
) : Exception("${error.code}: ${error.detail}")

/** Discovery, behind an interface so the pairing flow can be tested without a radio. */
interface PeerServiceDiscovery {
    fun browse(type: PeerAdbServiceType): Flow<PeerAdbService>
}

/**
 * The pairing and connection flow: announcement in, a verified device out.
 *
 * It deliberately does *not* execute anything else and does not decide policy - it is the part that
 * talks to `adb` and to mDNS, and every step it takes is one AOSP documents:
 *
 *  1. the host shows a QR whose `S:` field names the instance it wants the phone to publish;
 *  2. the phone's camera handler starts a pairing server under exactly that name;
 *  3. the host pairs against *that* service - not the first one it sees, because the phone usually
 *     has a second pairing service open for its own six-digit dialog, and pairing against it fails
 *     with a protocol fault while looking like a wrong code;
 *  4. the phone publishes `_adb-tls-connect._tcp`; the host connects to it;
 *  5. the host runs one real command on the device and only then calls it verified.
 *
 * Everything happens through [runner] (the `adb` client inside the on-device runtime), so the TLS
 * handshake and the key exchange are the real adb's, not a re-implementation.
 */
class PeerAdbSession(
    private val runner: AdbShellRunner,
    private val discovery: PeerServiceDiscovery,
    private val registry: PeerDeviceRegistry,
    private val capabilities: PeerCapabilityDiscovery = PeerCapabilityDiscovery(runner),
) {
    /** A fresh QR pairing session: the payload to display, with a name this session will match on. */
    fun beginPairingQr(): PeerAdbPairingPayload = PeerAdbPairingPayload.random()

    /**
     * Waits for the pairing service the QR asked for, pairs, then connects and verifies.
     *
     * [pairingTimeoutMillis] bounds the wait for the *announcement* (it only exists while the other
     * phone's pairing dialog is open, so a user who has not scanned yet is the normal case, not an
     * error). [connectTimeoutMillis] bounds the wait for the connection service after pairing.
     */
    suspend fun pairWithQr(
        payload: PeerAdbPairingPayload,
        pairingTimeoutMillis: Long = PAIRING_WAIT_MILLIS,
        connectTimeoutMillis: Long = CONNECT_WAIT_MILLIS,
    ): Result<PeerDevice> {
        val pairingService =
            awaitService(PeerAdbServiceType.PAIRING, pairingTimeoutMillis) { service ->
                service.matches(payload.serviceName)
            } ?: return failure(
                PeerAdbErrorCode.PAIRING_SERVICE_NOT_FOUND,
                "no _adb-tls-pairing._tcp announcement matched '${payload.serviceName}' within " +
                    "${pairingTimeoutMillis / 1000}s",
            )
        registry.discovered(pairingService)
        val paired = pair(pairingService, payload.password) ?: return pairedFailure(pairingService)
        registry.paired(paired)
        return connectAfterPair(pairingService, paired, connectTimeoutMillis)
    }

    /**
     * The six-digit path: the user reads the code and the address off the other phone's screen.
     *
     * The same pairing as the QR path - only the secret's origin differs - so the two share
     * [pair] and [connectAfterPair] and cannot drift apart.
     */
    suspend fun pairWithCode(
        host: String,
        port: Int,
        code: String,
        connectTimeoutMillis: Long = CONNECT_WAIT_MILLIS,
    ): Result<PeerDevice> {
        val service = PeerAdbService(PeerAdbServiceType.PAIRING, instanceName = "", host = host, port = port)
        val paired = pair(service, code) ?: return pairedFailure(service)
        registry.paired(paired)
        return connectAfterPair(service, paired, connectTimeoutMillis)
    }

    /**
     * Reconnects a device that was paired before.
     *
     * Pairing is not repeated: the key lives in the runtime's keystore, so all that is needed is the
     * *current* connection service - the port changes every time wireless debugging is toggled, which
     * is why the known device is matched on its serial/instance name and not on a stored port.
     */
    suspend fun reconnect(
        serial: String,
        timeoutMillis: Long = CONNECT_WAIT_MILLIS,
    ): Result<PeerDevice> {
        val known =
            registry.find(serial)
                ?: return failure(PeerAdbErrorCode.DEVICE_OFFLINE, "no known device with serial '$serial'")
        val service =
            awaitService(PeerAdbServiceType.CONNECT, timeoutMillis) { candidate ->
                AdbOutputParser.serialMatches(known, candidate)
            }
        val host = service?.host ?: known.host
        val port = service?.port ?: known.port
        if (host.isBlank() || port <= 0) {
            return failure(
                PeerAdbErrorCode.CONNECT_SERVICE_NOT_FOUND,
                "no _adb-tls-connect._tcp announcement for ${known.label} within ${timeoutMillis / 1000}s and no " +
                    "last known address",
            )
        }
        return connectAndVerify(host = host, port = port, preferredInstance = service?.instanceName)
    }

    /**
     * `adb connect` plus the only thing that turns it into a usable device: a command that answered.
     *
     * The serial is resolved from `adb devices -l` (the instance's predicted serial first, then the
     * single device that was not there before). When it cannot be resolved uniquely, this fails
     * instead of guessing - aiming the next command at the wrong phone is worse than an error.
     */
    suspend fun connectAndVerify(
        host: String,
        port: Int,
        preferredInstance: String? = null,
    ): Result<PeerDevice> {
        val before = adbSerials()
        val output = runCatching { runner.runShellOnIo(AdbCommandLine.connect(host, port), CONNECT_TIMEOUT_SECONDS) }
        if (output.isFailure) {
            return resultFailure(
                PeerAdbErrorClassifier.classify(output.exceptionOrNull()?.message.orEmpty(), PeerAdbErrorCode.ADB_CONNECT_FAILED),
            )
        }
        val text = output.getOrNull()?.output.orEmpty()
        val after = adbDevices()
        val resolved = AdbOutputParser.resolveSerial(after, before, preferredInstance)
        return when {
            resolved == null && !AdbOutputParser.connected(text) ->
                resultFailure(PeerAdbErrorClassifier.classify(text, PeerAdbErrorCode.ADB_CONNECT_FAILED))
            resolved == null ->
                failure(
                    PeerAdbErrorCode.DEVICE_OFFLINE,
                    "connected to $host:$port but no unique device entry matched it in `adb devices -l`: " +
                        after.joinToString(", ") { "${it.serial}=${it.state}" }.ifBlank { "(the list was empty)" },
                )
            else -> verifyReachable(resolved, host, port)
        }
    }

    /** One real command decides whether the channel is usable, and only then is the device verified. */
    private suspend fun verifyReachable(
        serial: String,
        host: String,
        port: Int,
    ): Result<PeerDevice> {
        val probe = capabilities.probe(serial)
        if (!probe.reachable) {
            return failure(
                PeerAdbErrorCode.ADB_CONNECT_FAILED,
                "$serial is listed but did not answer a shell command; the connection is not usable yet",
            )
        }
        val withCapabilities =
            registry.connected(serial, host, port, probe.identity).withCapabilities(probe.capabilities)
        return Result.success(
            registry.verified(serial, probe.identity).copy(capabilities = withCapabilities.capabilities),
        )
    }

    /** Drops the TCP link. The pairing stays, so a later reconnect needs no code. */
    suspend fun disconnect(device: PeerDevice): Result<Unit> {
        val output =
            runCatching {
                if (device.host.isBlank() || device.port <= 0) {
                    runner.runShellOnIo("adb disconnect", DISCONNECT_TIMEOUT_SECONDS)
                } else {
                    runner.runShellOnIo(AdbCommandLine.disconnect(device.host, device.port), DISCONNECT_TIMEOUT_SECONDS)
                }
            }
        registry.state(device.serial, PeerDeviceState.DISCONNECTED)
        return output.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { throwable -> Result.failure(PeerAdbFailure(PeerAdbErrorClassifier.classify(throwable.message.orEmpty()))) },
        )
    }

    /** Probes the device's identity and capabilities (one batched shell round trip). */
    suspend fun probe(serial: String): PeerProbeReport = capabilities.probe(serial)

    /** `adb devices -l`, parsed. */
    suspend fun adbDevices(): List<AdbDeviceLine> {
        val output = runCatching { runner.runShellOnIo(AdbCommandLine.devices(), DEVICES_TIMEOUT_SECONDS) }.getOrNull()
        return AdbOutputParser.devices(output?.output.orEmpty())
    }

    private suspend fun adbSerials(): Set<String> = adbDevices().map { it.serial }.toSet()

    /** Runs the pairing against one service, or null when adb did not report success. */
    private suspend fun pair(
        service: PeerAdbService,
        code: String,
    ): PeerAdbService? {
        val output = runCatching { runner.runShellOnIo(AdbCommandLine.pair(service.host, service.port, code), PAIR_TIMEOUT_SECONDS) }
        val text = output.getOrNull()?.output.orEmpty()
        lastPairOutput = text.trim()
        return if (AdbOutputParser.pairedSuccessfully(text)) service else null
    }

    private fun pairedFailure(service: PeerAdbService): Result<PeerDevice> {
        val output = lastPairOutput
        val code =
            if (output.lowercase().contains("incorrect") || output.lowercase().contains("wrong")) {
                PeerAdbErrorCode.PAIRING_CODE_INVALID
            } else {
                PeerAdbErrorCode.PAIRING_FAILED
            }
        return failure(code, output.ifBlank { "pairing with ${service.host}:${service.port} did not succeed" })
    }

    /** The last raw pairing output, kept only to explain a failure. */
    @Volatile
    private var lastPairOutput: String = ""

    /** Connects the service the phone advertises after pairing (or adopts adb's own auto-connect). */
    private suspend fun connectAfterPair(
        pairingService: PeerAdbService,
        paired: PeerAdbService,
        timeoutMillis: Long,
    ): Result<PeerDevice> {
        val connectService =
            awaitService(PeerAdbServiceType.CONNECT, timeoutMillis) { candidate ->
                candidate.host == pairingService.host || candidate.instanceName.contains(paired.instanceName, ignoreCase = true)
            }
        if (connectService == null) {
            // adb's own auto-connect may already have the device; adopt it rather than failing.
            val resolved = AdbOutputParser.resolveSerial(adbDevices(), emptySet(), paired.instanceName)
            if (resolved != null) {
                val probe = capabilities.probe(resolved)
                if (probe.reachable) {
                    return Result.success(
                        registry.verified(resolved, probe.identity).withCapabilities(probe.capabilities),
                    )
                }
            }
            return failure(
                PeerAdbErrorCode.CONNECT_SERVICE_NOT_FOUND,
                "paired with ${paired.host}, but no _adb-tls-connect._tcp announcement appeared within " +
                    "${timeoutMillis / 1000}s (keep the Wireless debugging screen on)",
            )
        }
        return connectAndVerify(connectService.host, connectService.port, connectService.instanceName)
    }

    /** The first announcement matching [predicate], or null when [timeoutMillis] passes. */
    private suspend fun awaitService(
        type: PeerAdbServiceType,
        timeoutMillis: Long,
        predicate: (PeerAdbService) -> Boolean,
    ): PeerAdbService? = withTimeoutOrNull(timeoutMillis) { discovery.browse(type).firstOrNull(predicate) }

    private fun failure(
        code: PeerAdbErrorCode,
        detail: String,
    ): Result<PeerDevice> = Result.failure(PeerAdbFailure(PeerAdbError(code, detail, PeerAdbErrorClassifier.nextStepFor(code))))

    private fun resultFailure(error: PeerAdbError): Result<PeerDevice> = Result.failure(PeerAdbFailure(error))

    private companion object {
        const val PAIRING_WAIT_MILLIS = 90_000L
        const val CONNECT_WAIT_MILLIS = 45_000L
        const val PAIR_TIMEOUT_SECONDS = 40L
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val DISCONNECT_TIMEOUT_SECONDS = 10L
        const val DEVICES_TIMEOUT_SECONDS = 15L
    }
}
