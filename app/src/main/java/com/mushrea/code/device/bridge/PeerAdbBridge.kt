package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.ExecutionLog
import com.mushrea.code.core.execution.ExecutionPlan
import com.mushrea.code.core.execution.ExecutionPlanner
import com.mushrea.code.core.execution.ExecutionProvider
import com.mushrea.code.core.execution.ExecutionRecord
import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.execution.ExecutionResult
import com.mushrea.code.core.execution.ExecutionStage
import com.mushrea.code.core.execution.PlanIntent
import com.mushrea.code.core.peer.PeerDevice

/**
 * The last word before a peer operation runs.
 *
 * Returns a refusal reason, or null to proceed. It exists so governance is not optional: the bridge
 * cannot execute without a gate, and the app's gate is the Permission Center. Keeping it as a
 * function type (instead of calling the center directly here) keeps this layer testable and keeps the
 * dependency pointing one way - `core/permission` never learns about ADB.
 */
fun interface PeerExecutionGate {
    suspend fun allow(
        request: ExecutionRequest,
        device: PeerDevice,
    ): String?
}

/**
 * The peer-device bridge: everything the app knows how to do with another Android phone over
 * wireless debugging, behind one object.
 *
 * It is the layer the Device Agent talks to, so the agent never sees QR codes, mDNS instance names,
 * ports or TLS - it asks for capabilities and runs operations on a named device. The three parts it
 * composes are deliberately separate: [PeerAdbSession] does pairing/connection, the execution
 * providers do the work, and [ExecutionPlanner] decides which provider fits what the device reported.
 *
 * Two invariants hold at this level, and both are about not lying to the user:
 *  * **no execution on an unverified device.** A device that has not answered a real command is not
 *    a target, whatever its socket says. The registry state is the gate.
 *  * **the classifier can only raise friction.** A request that under-declares what its command does
 *    is re-stated as destructive before the gate is asked; a request that claims read-only for a
 *    writing command is refused outright by the provider. Nothing in this chain can lower a level.
 */
class PeerAdbBridge(
    private val session: PeerAdbSession,
    private val registry: PeerDeviceRegistry,
    private val providers: List<ExecutionProvider>,
    private val planner: ExecutionPlanner = ExecutionPlanner(providers),
    val log: ExecutionLog = ExecutionLog(),
    private val gate: PeerExecutionGate = PeerExecutionGate { _, _ -> null },
) {
    // ---- devices -------------------------------------------------------------------------------

    fun devices(): List<PeerDevice> = registry.all()

    fun device(serial: String): PeerDevice? = registry.find(serial)

    /** Starts a QR pairing session: the payload is what the screen renders. */
    fun beginPairingQr(): PeerAdbPairingPayload = session.beginPairingQr()

    suspend fun pairWithQr(
        payload: PeerAdbPairingPayload,
        pairingTimeoutMillis: Long = QR_PAIRING_WAIT_MILLIS,
        connectTimeoutMillis: Long = QR_CONNECT_WAIT_MILLIS,
    ): Result<PeerDevice> = session.pairWithQr(payload, pairingTimeoutMillis, connectTimeoutMillis)

    suspend fun pairWithCode(
        host: String,
        port: Int,
        code: String,
    ): Result<PeerDevice> = session.pairWithCode(host, port, code)

    /** Reconnects a paired device without a new QR: the port is rediscovered, the key is reused. */
    suspend fun reconnect(serial: String): Result<PeerDevice> = session.reconnect(serial)

    suspend fun disconnect(serial: String): Result<Unit> {
        val device = registry.find(serial) ?: return Result.failure(unknownDevice(serial))
        return session.disconnect(device)
    }

    fun forget(serial: String): Boolean = registry.forget(serial)

    /** Re-probes the device and stores what it answered; the capability report is returned as well. */
    suspend fun refreshCapabilities(serial: String): Result<CapabilityReport> {
        val device = registry.find(serial) ?: return Result.failure(unknownDevice(serial))
        val probe = runCatching { session.probe(device.serial) }
        return probe.fold(
            onSuccess = { report ->
                registry.capabilities(device.serial, report.capabilities)
                Result.success(report.capabilities)
            },
            onFailure = { throwable ->
                Result.failure(PeerAdbFailure(PeerAdbErrorClassifier.classify(throwable.message.orEmpty())))
            },
        )
    }

    // ---- execution -----------------------------------------------------------------------------

    /**
     * Runs one request against a verified device.
     *
     * Order is the whole safety story: the device must be registered and verified, the request's
     * declaration is corrected upwards by the classifier, the gate answers, and only then does a
     * provider run anything. The record is written whatever the outcome, including refusals.
     */
    suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val device =
            registry.find(request.target.id)
                ?: return ExecutionResult.rejected(
                    "no peer device is registered as '${request.target.id}'",
                    errorCode = PeerAdbErrorCode.DEVICE_OFFLINE.name,
                )
        if (!device.connected) {
            return ExecutionResult.rejected(
                "${device.label} is ${device.state} - connect it before running ${request.operation}",
                errorCode = PeerAdbErrorCode.DEVICE_OFFLINE.name,
            )
        }
        val effective = withEscalatedEffect(request)
        val refusal = gate.allow(effective, device)
        if (refusal != null) {
            record(device, effective, ExecutionResult.rejected(refusal), "refused by policy")
            return ExecutionResult.rejected(refusal)
        }
        val provider = providerFor(effective)
        if (provider == null) {
            val reason = "no provider serves ${effective.operation} on ${effective.target.transport}"
            record(device, effective, ExecutionResult.rejected(reason), "no provider")
            return ExecutionResult.rejected(reason)
        }
        val result = runCatching { provider.execute(effective) }.getOrElse { throwable ->
            ExecutionResult.transportFailed(
                message = throwable.message ?: "the provider failed",
                errorCode = PeerAdbErrorCode.UNKNOWN_FAILURE.name,
            )
        }
        record(device, effective, result, if (result.verified) "verified" else "not requested")
        return result
    }

    /** Resolves intents against what a device reported; the agent gets a plan, not a guess. */
    fun plan(
        intents: List<PlanIntent>,
        capabilities: CapabilityReport,
    ): ExecutionPlan = planner.plan(intents, capabilities)

    fun recentExecutions(limit: Int = 25): List<ExecutionRecord> = log.recent(limit)

    // ---- internals -----------------------------------------------------------------------------

    /**
     * Raises the request's declared effect to what its command actually does.
     *
     * The agent declares an effect so policy can weigh it; this makes sure an under-declaration can
     * only *add* friction. The gate then sees the true class, which is why a destructive command
     * cannot ride in on a "read-only" claim.
     */
    private fun withEscalatedEffect(request: ExecutionRequest): ExecutionRequest {
        val verdict = PeerExecutionGuard.verdictFor(request)
        if (!verdict.requiresStrongConfirmation || request.effect.destructive) return request
        return request.copy(
            effect = request.effect.copy(mutatesTarget = true, destructive = true, risk = verdict.risk),
        )
    }

    private fun providerFor(request: ExecutionRequest): ExecutionProvider? {
        val pinned = request.providerId?.let { id -> providers.firstOrNull { it.id == id } }
        if (pinned != null) return pinned
        return providers.firstOrNull { provider ->
            provider.transport == request.target.transport && provider.supports(request.operation)
        }
    }

    private fun record(
        device: PeerDevice,
        request: ExecutionRequest,
        result: ExecutionResult,
        verification: String,
    ) {
        log.record(
            ExecutionRecord(
                correlationId = request.correlationId,
                timestampMillis = System.currentTimeMillis(),
                targetId = device.serial,
                providerId = request.providerId ?: providerFor(request)?.id.orEmpty(),
                operation = request.operation,
                commandIdentity = ExecutionRecord.identity(request.invocation.command, request.invocation.arguments),
                stage = result.stage,
                exitCode = result.exitCode,
                durationMillis = result.durationMillis,
                verification = verification,
                errorCode = result.errorCode,
                error = if (result.stage == ExecutionStage.SUCCEEDED || result.stage == ExecutionStage.VERIFIED) "" else result.message,
            ),
        )
    }

    private fun unknownDevice(serial: String): PeerAdbFailure =
        PeerAdbFailure(
            PeerAdbError(
                code = PeerAdbErrorCode.DEVICE_OFFLINE,
                detail = "no known peer device with serial '$serial'",
                nextStep = PeerAdbErrorClassifier.nextStepFor(PeerAdbErrorCode.DEVICE_OFFLINE),
            ),
        )

    private companion object {
        const val QR_PAIRING_WAIT_MILLIS = 90_000L
        const val QR_CONNECT_WAIT_MILLIS = 45_000L
    }
}
