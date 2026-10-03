package com.mushrea.code.core.execution

/** What the caller wants done, before anything has decided whether it is possible. */
data class PlanIntent(
    val operation: ExecutionOperation,
    val transport: ExecutionTransport,
    val description: String = "",
    val verify: Boolean = false,
    val providerId: String? = null,
)

/** One resolved step: a provider that exists, an operation it supports, and what it needs. */
data class PlanStep(
    val providerId: String,
    val transport: ExecutionTransport,
    val operation: ExecutionOperation,
    val description: String,
    val requirements: Set<String>,
    val verify: Boolean,
    val rewrittenFrom: ExecutionOperation? = null,
)

/** Why a step could not be planned, in the words the caller has to act on. */
data class PlanBlocker(
    val operation: ExecutionOperation,
    val transport: ExecutionTransport,
    val capability: String?,
    val reason: String,
)

/** A plan is feasible only when every step resolved; a partial plan is never executed silently. */
data class ExecutionPlan(
    val steps: List<PlanStep>,
    val blockers: List<PlanBlocker>,
) {
    val feasible: Boolean get() = blockers.isEmpty() && steps.isNotEmpty()

    /** One line naming what stopped the plan, for the agent and for the user. */
    fun blockedReason(): String =
        blockers.joinToString("; ") { blocker ->
            blocker.capability?.let { "${blocker.operation} needs $it: ${blocker.reason}" }
                ?: "${blocker.operation}: ${blocker.reason}"
        }
}

/**
 * Turns intents into steps a provider can actually run.
 *
 * The planner is deliberately not an AI. Deciding *what* to achieve is the agent's job; deciding
 * whether the chosen path exists on this device is a fact question, and answering it with facts is
 * what keeps the agent from inventing commands that a phone without `cmd` or `python3` cannot run.
 * So the planner does three things and no more:
 *
 *  1. **resolves** each intent to a provider that serves its transport and supports its operation;
 *  2. **checks** the operation's requirements against the target's [CapabilityReport], and asks the
 *     provider for an alternative when one is missing (a device without `exec-out` can still run
 *     `sh`, and the plan says so instead of failing);
 *  3. **blocks** honestly when neither the operation nor any alternative is supported, naming the
 *     capability that is missing - which is the difference between "unsupported" and "this phone has
 *     no `uiautomator`".
 *
 * A plan with any blocker is not feasible; the caller decides whether to drop the step, ask the user,
 * or choose another goal. Nothing here executes, prompts or persists.
 */
class ExecutionPlanner(private val providers: List<ExecutionProvider>) {
    /** The provider that would serve [intent], or null when none does. */
    fun providerFor(intent: PlanIntent): ExecutionProvider? =
        providers.firstOrNull { provider ->
            provider.id == intent.providerId && provider.transport == intent.transport
        }
            ?: providers.firstOrNull { provider ->
                intent.providerId == null &&
                    provider.transport == intent.transport &&
                    provider.supports(intent.operation)
            }

    /** Resolves one intent, or explains why it cannot be resolved. */
    fun step(
        intent: PlanIntent,
        capabilities: CapabilityReport,
    ): Result<PlanStep> {
        val provider =
            providerFor(intent)
                ?: return Result.failure(
                    IllegalStateException("no ${intent.transport} provider is registered for ${intent.operation}"),
                )
        if (!provider.supports(intent.operation)) {
            return Result.failure(IllegalStateException("provider ${provider.id} cannot ${intent.operation}"))
        }
        return resolveStep(intent, provider, capabilities)
    }

    /** The capability half of [step]: what the target can do decides the operation that is planned. */
    private fun resolveStep(
        intent: PlanIntent,
        provider: ExecutionProvider,
        capabilities: CapabilityReport,
    ): Result<PlanStep> {
        val required = provider.requirements(intent.operation)
        // Only a *measured* missing capability blocks or rewrites a step. `UNKNOWN` means the probe
        // never answered, which is not evidence against the device - the provider then reports the
        // real failure instead of the platform inventing an impossibility.
        val missing = required.firstOrNull { capabilities.missing(it) }
        if (missing == null) {
            return Result.success(
                PlanStep(
                    providerId = provider.id,
                    transport = provider.transport,
                    operation = intent.operation,
                    description = intent.description,
                    requirements = required,
                    verify = intent.verify,
                ),
            )
        }
        val alternative = provider.alternative(intent.operation, missing)?.takeIf(provider::supports)
        return if (alternative == null) {
            Result.failure(MissingCapabilityException(intent.operation, missing))
        } else {
            Result.success(
                PlanStep(
                    providerId = provider.id,
                    transport = provider.transport,
                    operation = alternative,
                    description = intent.description,
                    requirements = provider.requirements(alternative),
                    verify = intent.verify,
                    rewrittenFrom = intent.operation,
                ),
            )
        }
    }

    /** Resolves every intent; blockers do not stop the others from resolving. */
    fun plan(
        intents: List<PlanIntent>,
        capabilities: CapabilityReport,
    ): ExecutionPlan {
        val steps = mutableListOf<PlanStep>()
        val blockers = mutableListOf<PlanBlocker>()
        intents.forEach { intent ->
            step(intent, capabilities).fold(
                onSuccess = { steps.add(it) },
                onFailure = { failure ->
                    val missing = (failure as? MissingCapabilityException)?.capability
                    blockers.add(
                        PlanBlocker(
                            operation = intent.operation,
                            transport = intent.transport,
                            capability = missing,
                            reason = failure.message ?: "provider refused the step",
                        ),
                    )
                },
            )
        }
        return ExecutionPlan(steps = steps, blockers = blockers)
    }

    /** Raised when the target *reported* a capability as missing (an unproven one does not block). */
    class MissingCapabilityException(
        val operation: ExecutionOperation,
        val capability: String,
    ) : Exception("$operation needs $capability")
}
