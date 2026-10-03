package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.ExecutionEffect
import com.mushrea.code.core.execution.ExecutionGoal
import com.mushrea.code.core.execution.ExecutionInvocation
import com.mushrea.code.core.execution.ExecutionLog
import com.mushrea.code.core.execution.ExecutionOperation
import com.mushrea.code.core.execution.ExecutionPlan
import com.mushrea.code.core.execution.ExecutionPlanner
import com.mushrea.code.core.execution.ExecutionPolicy
import com.mushrea.code.core.execution.ExecutionProvider
import com.mushrea.code.core.execution.ExecutionProviderRegistry
import com.mushrea.code.core.execution.ExecutionRecipe
import com.mushrea.code.core.execution.ExecutionRecord
import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.execution.ExecutionResult
import com.mushrea.code.core.execution.ExecutionStage
import com.mushrea.code.core.execution.ExecutionTarget
import com.mushrea.code.core.execution.PlanIntent
import com.mushrea.code.core.execution.PlanStep
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

/** One route the bridge tried for a goal, and how it ended. */
data class RouteAttempt(
    val candidateId: String,
    val steps: List<String>,
    val stage: ExecutionStage?,
    val reason: String,
)

/**
 * What happened to a goal.
 *
 * [attempts] is the fallback chain, in order: the first entry is the route the planner preferred, and
 * each later entry is a route that was tried because the one before it failed - with the failure that
 * sent the caller there. [result] is the outcome of the last route tried (null when nothing could be
 * planned at all), and [plan] is the route that was chosen first.
 */
data class GoalOutcome(
    val plan: ExecutionPlan,
    val result: ExecutionResult?,
    val attempts: List<RouteAttempt>,
) {
    val succeeded: Boolean get() = result?.ok == true

    val verified: Boolean get() = result?.verified == true

    /** The last failure, in the words the agent has to act on. */
    val failureReason: String
        get() =
            result?.failureReason?.takeIf(String::isNotBlank)
                ?: result?.message.orEmpty().takeIf { !succeeded }
                ?: plan.blockedReason()
}

/**
 * The peer-device bridge: everything the app knows how to do with another Android phone over
 * wireless debugging, behind one object.
 *
 * It is the layer the Device Agent talks to, so the agent never sees QR codes, mDNS instance names,
 * ports or TLS - it asks for capabilities, plans a goal and runs it on a named device. The parts it
 * composes are deliberately separate: [PeerAdbSession] does pairing/connection, the registered
 * execution providers do the work, and [ExecutionPlanner] decides which provider and which route fit
 * what the device reported.
 *
 * Four invariants hold at this level, and all four are about not lying to the user:
 *  * **no execution on an unverified device.** A device that has not answered a real command is not
 *    a target, whatever its socket says. The registry state is the gate.
 *  * **the classifier can only raise friction.** A request that under-declares what its command does
 *    is re-stated as destructive before the gate is asked; a request that claims read-only for a
 *    writing command is refused outright by the provider. Nothing in this chain can lower a level.
 *  * **every route goes through the same gate.** A route chosen by the planner, a fallback after a
 *    failure and a verification follow-up are all ordinary requests: Permission Center, then provider.
 *    There is no second path to adb from here.
 *  * **a failure is not the end of the plan.** When a route fails on the device, the next feasible
 *    route is tried and the reason the first one was abandoned is recorded - so the agent can explain
 *    what it did instead of reporting "unsupported".
 */
class PeerAdbBridge(
    private val session: PeerAdbSession,
    private val registry: PeerDeviceRegistry,
    private val providers: List<ExecutionProvider>,
    private val planner: ExecutionPlanner = ExecutionPlanner(providers),
    val log: ExecutionLog = ExecutionLog(),
    private val gate: PeerExecutionGate = PeerExecutionGate { _, _ -> null },
) {
    private val providerRegistry: ExecutionProviderRegistry = ExecutionProviderRegistry(providers)

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

    /**
     * Re-probes the device and stores what it answered.
     *
     * The new measurement is **merged onto** what the device already reported, so a probe that only
     * reaches part of the phone cannot erase a capability an earlier probe proved; the returned report
     * is the merged one, which is also what the planner will see.
     */
    suspend fun refreshCapabilities(serial: String): Result<CapabilityReport> {
        val device = registry.find(serial) ?: return Result.failure(unknownDevice(serial))
        val probe = runCatching { session.probe(device.serial) }
        return probe.fold(
            onSuccess = { report ->
                val merged = device.capabilityReport().merge(report.capabilities)
                registry.capabilities(device.serial, merged)
                Result.success(merged)
            },
            onFailure = { throwable ->
                Result.failure(PeerAdbFailure(PeerAdbErrorClassifier.classify(throwable.message.orEmpty())))
            },
        )
    }

    // ---- planning ------------------------------------------------------------------------------

    /** Resolves intents against what a device reported; the agent gets a plan, not a guess. */
    fun plan(
        intents: List<PlanIntent>,
        capabilities: CapabilityReport,
    ): ExecutionPlan = planner.plan(intents, capabilities)

    /** Resolves a goal - in words, or naming a recipe - against a named device's measurements. */
    fun plan(
        goal: ExecutionGoal,
        capabilities: CapabilityReport,
    ): ExecutionPlan = planner.plan(goal, capabilities)

    /** Every feasible route for a goal, best first: what [executeGoal] walks when one fails. */
    fun routes(
        goal: ExecutionGoal,
        capabilities: CapabilityReport,
    ): List<ExecutionPlan> {
        val recipe = planner.recipeFor(goal) ?: return listOf(planner.plan(goal, capabilities))
        // The blocked form names the missing capability, so a goal with no feasible route still
        // answers with a reason rather than an empty list.
        return planner.plans(recipe, goal, capabilities).ifEmpty { listOf(planner.plan(goal, capabilities)) }
    }

    /** The recipe catalogue the agent can choose from, and the providers that are registered. */
    fun recipes(): List<ExecutionRecipe> = planner.catalogue()

    fun providerIds(): List<String> = providerRegistry.all.map { it.id }

    // ---- execution -----------------------------------------------------------------------------

    /**
     * Runs one request against a verified device.
     *
     * Order is the whole safety story: the device must be registered and connected, the request's
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
        // The provider is resolved *before* the gate so the decision can name the route it is about;
        // resolving is pure, so nothing has run yet. A no-provider plan is refused without asking the
        // user a question nobody could carry out.
        val choice = providerRegistry.select(effective.target.transport, effective.operation, effective.providerId)
        val provider =
            choice.provider
                ?: run {
                    val reason = choice.reason
                    record(device, effective, ExecutionResult.rejected(reason), "no provider")
                    return ExecutionResult.rejected(reason)
                }
        val routed =
            effective.copy(
                providerId = effective.providerId ?: provider.id,
                capabilityHint = effective.capabilityHint.ifBlank { provider.capabilityFor(effective.operation) },
            )
        val refusal = gate.allow(routed, device)
        if (refusal != null) {
            record(device, routed, ExecutionResult.rejected(refusal), "refused by policy")
            return ExecutionResult.rejected(refusal)
        }
        val result = runCatching { provider.execute(routed) }.getOrElse { throwable ->
            ExecutionResult.transportFailed(
                message = throwable.message ?: "the provider failed",
                errorCode = PeerAdbErrorCode.UNKNOWN_FAILURE.name,
            )
        }
        record(device, routed, result, if (result.verified) "verified" else "not requested")
        return result
    }

    /**
     * Runs one resolved step.
     *
     * A step is a request that already carries its own operation, invocation and effect, so this is
     * the single funnel the planner's output goes through - and the only way a planned step reaches a
     * provider. Policy is passed separately because only the caller knows the user's answer.
     */
    suspend fun executeStep(
        step: PlanStep,
        target: ExecutionTarget,
        policy: ExecutionPolicy = ExecutionPolicy(),
    ): ExecutionResult = execute(step.request(target, policy))

    /**
     * Runs a goal, walking the feasible routes until one succeeds.
     *
     * Each route is planned against the device's *current* report, executed step by step, and verified
     * when the caller asked for it (the recipe's own follow-up command, or the caller's). When a route
     * fails, the next one is tried and the failure that sent the caller there is kept in the record -
     * never a silent retry, and never "unsupported" while an untried route exists.
     */
    suspend fun executeGoal(
        goal: ExecutionGoal,
        policy: ExecutionPolicy = ExecutionPolicy(),
        maxRoutes: Int = MAX_ROUTES,
    ): GoalOutcome {
        val device =
            registry.find(goal.targetId)
                ?: return GoalOutcome(
                    plan = planner.plan(goal, CapabilityReport.unknown()),
                    result =
                        ExecutionResult.rejected(
                            "no peer device is registered as '${goal.targetId}'",
                            errorCode = PeerAdbErrorCode.DEVICE_OFFLINE.name,
                        ),
                    attempts = emptyList(),
                )
        if (!device.connected) {
            return GoalOutcome(
                plan = planner.plan(goal, device.capabilityReport()),
                result =
                    ExecutionResult.rejected(
                        "${device.label} is ${device.state} - connect it before running this",
                        errorCode = PeerAdbErrorCode.DEVICE_OFFLINE.name,
                    ),
                attempts = emptyList(),
            )
        }
        val routes = routes(goal, device.capabilityReport())
        val attempts = mutableListOf<RouteAttempt>()
        var lastResult: ExecutionResult? = null
        routes.take(maxRoutes.coerceAtLeast(1)).forEachIndexed { index, plan ->
            val skipped = attempts.joinToString(" | ") { attempt -> "${attempt.candidateId} failed: ${attempt.reason}" }
            if (plan.steps.isEmpty()) {
                // A plan with no steps is a blocker, not a success: nothing would run.
                attempts += RouteAttempt("route-${index + 1}", emptyList(), null, plan.blockedReason())
                return@forEachIndexed
            }
            val stepResults = mutableListOf<ExecutionResult>()
            for (step in plan.steps) {
                // The step's own fallback note ("bin:pm is missing; bin:cmd reaches the same result")
                // travels with the result, so the agent can explain the route it took.
                val executed = executeStep(step, device.target(), policy)
                val result = if (executed.fallback.isBlank()) executed.copy(fallback = step.fallbackReason) else executed
                lastResult = result
                if (!result.ok) {
                    attempts +=
                        RouteAttempt(
                            candidateId = plan.steps.firstOrNull()?.candidateId.orEmpty().ifBlank { "route-${index + 1}" },
                            steps = plan.steps.map { it.description },
                            stage = result.stage,
                            reason = result.failureReason.ifBlank { result.message },
                        )
                    break
                }
                stepResults += result
            }
            if (stepResults.size == plan.steps.size) {
                val verified = verifySteps(plan, device, policy, skipped, lastResult)
                val finalResult =
                    (verified ?: lastResult)?.let { result ->
                        if (skipped.isBlank() || result.fallback.isNotBlank()) result else result.copy(fallback = skipped)
                    }
                attempts +=
                    RouteAttempt(
                        candidateId = plan.steps.firstOrNull()?.candidateId.orEmpty().ifBlank { "route-${index + 1}" },
                        steps = plan.steps.map { it.description },
                        stage = finalResult?.stage,
                        reason = if (skipped.isBlank()) "first route the planner chose" else skipped,
                    )
                return GoalOutcome(plan, finalResult, attempts)
            }
        }
        return GoalOutcome(
            plan = routes.firstOrNull() ?: planner.plan(goal, device.capabilityReport()),
            result = lastResult,
            attempts = attempts,
        )
    }

    /**
     * Runs the follow-up a plan asked for, and marks the outcome verified only when it passed.
     *
     * Verification is a *second* request through the same gate: a recipe's own check command runs as a
     * read-only shell line, so nothing here can be used to smuggle a write past policy.
     */
    private suspend fun verifySteps(
        plan: ExecutionPlan,
        device: PeerDevice,
        policy: ExecutionPolicy,
        skipped: String,
        operationResult: ExecutionResult?,
    ): ExecutionResult? {
        val step = plan.steps.lastOrNull() ?: return null
        if (!step.verify || step.verifyCommand.isBlank()) return null
        val check = plan.steps.last().request(
            target = device.target(),
            policy = policy.copy(verify = false, timeoutMillis = VERIFY_TIMEOUT_MILLIS),
        )
        val verification =
            execute(
                check.copy(
                    operation = ExecutionOperation.SHELL,
                    invocation = ExecutionInvocation(step.verifyCommand),
                    effect = ExecutionEffect(mutatesTarget = false),
                ),
            )
        if (!verification.ok) {
            // The operation's own result is what is reported - a failed *check* is not a failed command,
            // and the exit code of the check must not be shown as the exit code of the work.
            val ran = operationResult ?: verification
            return ran.copy(
                stage = ran.stage,
                message = "the command ran, but the follow-up check did not confirm it: ${verification.message}",
                failureReason = "unverified: ${verification.failureReason.ifBlank { verification.message }}",
                fallback = skipped,
            )
        }
        return verification.copy(
            stage = ExecutionStage.VERIFIED,
            message = "verified on ${device.label}: ${step.verifyCommand}",
            failureReason = "",
            fallback = skipped,
        )
    }

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
                providerId = result.providerId.ifBlank { request.providerId ?: providerForId(request) },
                operation = request.operation,
                commandIdentity = ExecutionRecord.identity(request.invocation.command, request.invocation.arguments),
                stage = result.stage,
                exitCode = result.exitCode,
                durationMillis = result.durationMillis,
                verification = verification,
                errorCode = result.errorCode,
                error =
                    if (result.stage == ExecutionStage.SUCCEEDED || result.stage == ExecutionStage.VERIFIED) {
                        ""
                    } else {
                        result.message
                    },
                capability = result.capability,
                fallback = result.fallback,
                failureReason = result.failureReason,
            ),
        )
    }

    private fun providerForId(request: ExecutionRequest): String =
        providerRegistry.select(request.target.transport, request.operation, request.providerId).provider?.id.orEmpty()

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
        const val VERIFY_TIMEOUT_MILLIS = 30_000L
        const val MAX_ROUTES = 4
    }
}
