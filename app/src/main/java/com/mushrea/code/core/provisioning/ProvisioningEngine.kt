package com.mushrea.code.core.provisioning

/**
 * What a provisioning run needs from the layer that owns the transports.
 *
 * The engine knows the *order* and the *classification*; the host knows how a step is carried out on
 * this platform (mDNS, `adb pair`, a USB hand-over, a settings write). Keeping that split means the
 * flow can be tested exhaustively without a radio, and a new transport is a new host method - never a
 * new branch in the flow.
 */
interface ProvisioningHost {
    val id: String

    /** Everything the planner may decide from - measured, never assumed. */
    suspend fun facts(request: ProvisioningRequest): ProvisioningFacts

    /** Carries one step out. Must classify its result rather than throwing for an expected failure. */
    suspend fun run(
        step: ProvisioningStep,
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): StepOutcome
}

/**
 * Runs a provisioning plan and reports what is *proven*, step by step.
 *
 * Four behaviours are the point of this class, and each of them is a rule the project holds elsewhere:
 *
 *  * **the flow stops at the user, not at a guess.** A step the platform cannot do ends the run with
 *    `RequiresUserAction` and the single instruction that unblocks it; nothing after it is executed and
 *    nothing before it is discarded.
 *  * **facts are re-read after they change.** Pairing, connecting and writing a setting all move the
 *    picture (identity, capabilities, routes), and a plan built on the old picture would be a plan for
 *    a device we are no longer talking to.
 *  * **a failure is classified, not swallowed.** `Unsupported` does not end the run (a device whose
 *    settings cannot be written still gets connected), while `Failed` on a step that others depend on
 *    does - and the report keeps both.
 *  * **the end state is measured.** The status is derived from the *final* facts (what was proven),
 *    never from "the last step returned something".
 */
class ProvisioningEngine(
    private val host: ProvisioningHost,
    private val planner: ProvisioningPlanner = ProvisioningPlanner(),
) {
    suspend fun provision(request: ProvisioningRequest): ProvisioningReport {
        val attemptsAllowed = request.maxAttempts.coerceAtLeast(1)
        var facts = host.facts(request)
        val results = mutableListOf<StepResult>()
        var attempt = 1

        while (attempt <= attemptsAllowed) {
            val plan = planner.plan(request, facts)
            var failed = false
            var stopped = false
            for (step in plan.steps) {
                if (stopped) break
                val outcome = if (step.needsUser) StepOutcome.NeedsUser(step.detail, step.instruction) else run(step, request, facts)
                results += StepResult(step, outcome)
                when (outcome) {
                    is StepOutcome.NeedsUser -> stopped = true
                    is StepOutcome.Failed -> {
                        failed = true
                        stopped = true
                    }
                    // Unsupported is a fact about the environment, not an end of the flow: the steps
                    // that do not depend on it are still worth running.
                    is StepOutcome.Unsupported -> Unit
                    is StepOutcome.Skipped -> Unit
                    is StepOutcome.Completed -> if (refreshesFacts(step)) facts = host.facts(request)
                }
            }
            // A retry is only meaningful when something actually changed under us, and only after a
            // failure the host might route around the second time. Sleeping belongs to the reconnection
            // manager, not here: this loop must stay a pure sequence of decisions.
            val worthRetrying = failed && attempt < attemptsAllowed && facts.routes.isNotEmpty()
            if (!worthRetrying) break
            attempt++
            facts = host.facts(request)
        }

        val finalFacts = host.facts(request)
        return ProvisioningReport(
            targetId = request.targetId,
            status = status(results, finalFacts),
            readiness = finalFacts.readiness,
            steps = results,
            routes = finalFacts.routes,
            capabilities = finalFacts.capabilities,
            attempts = attempt,
            summary = summarise(results, finalFacts),
        )
    }

    private suspend fun run(
        step: ProvisioningStep,
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): StepOutcome = runCatching { host.run(step, request, facts) }.getOrElse { throwable ->
        StepOutcome.Failed(throwable.message ?: "${step.id} failed without a message")
    }

    /** Steps whose outcome can change what the planner would decide next. */
    private fun refreshesFacts(step: ProvisioningStep): Boolean =
        when (step.kind) {
            ProvisioningStepKind.PAIR,
            ProvisioningStepKind.CONNECT,
            ProvisioningStepKind.VERIFY,
            ProvisioningStepKind.CAPABILITIES,
            ProvisioningStepKind.PERSIST,
            ProvisioningStepKind.TEST_EXECUTION,
            -> true
            else -> false
        }

    private fun status(
        results: List<StepResult>,
        facts: ProvisioningFacts,
    ): ProvisioningStatus {
        val needsUser = results.any { it.outcome is StepOutcome.NeedsUser }
        val failed = results.any { it.outcome is StepOutcome.Failed }
        val completed = results.count { it.outcome is StepOutcome.Completed }
        return when {
            needsUser -> ProvisioningStatus.NEEDS_USER
            failed && completed > 0 -> ProvisioningStatus.PARTIAL
            failed -> ProvisioningStatus.FAILED
            facts.readiness.atLeast(DeviceReadiness.EXECUTION_VERIFIED) -> ProvisioningStatus.PROVISIONED
            completed > 0 -> ProvisioningStatus.PARTIAL
            else -> ProvisioningStatus.FAILED
        }
    }

    private fun summarise(
        results: List<StepResult>,
        facts: ProvisioningFacts,
    ): String {
        val counts = results.groupingBy { it.outcome::class.simpleName.orEmpty() }.eachCount()
        val parts = counts.entries.joinToString(", ") { (name, count) -> "$count ${name.lowercase()}" }
        val route = facts.routes.firstOrNull()?.let { candidate -> "over ${candidate.endpoint}" }.orEmpty()
        return listOf("readiness ${facts.readiness.name.lowercase()}", route, parts).filter(String::isNotBlank).joinToString(" | ")
    }
}
