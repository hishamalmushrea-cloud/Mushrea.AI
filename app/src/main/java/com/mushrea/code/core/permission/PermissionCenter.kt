package com.mushrea.code.core.permission

/**
 * The one place a subsystem asks "may this run?".
 *
 * ```text
 * request → routing → policy evaluation → safety rules → decision → (confirmation) → allow/deny
 * ```
 *
 * What the center owns, and why it is not left to the subsystems:
 *
 *  * **emergency stop** — while it is set, every request is refused except the stop action itself,
 *    so no subsystem can decide for itself that its own operation is "important enough";
 *  * **Read-Only** — an allowed state-changing operation is converted to a denial here, so a
 *    subsystem that forgot the switch cannot execute anyway (the device policy still produces its
 *    own, more precise refusal for its 90 tools; this is the safety net for every other domain);
 *  * **fail-closed routing** — a domain with no policy, or an operation a policy does not cover, is
 *    denied. That is what stops a newly added tool from running automatically because nobody wrote
 *    a rule for it.
 *
 * What the center does **not** do: it never executes, never shows a prompt and never talks to a
 * runtime. It returns a [PermissionResult]; the subsystem that asked is what runs, prompts, or
 * refuses, using the mechanisms it already has (the device bridge keeps its notification
 * confirmation, the schedule keeps its own watchdog and retries).
 *
 * @param policies the subsystem policies; two policies claiming the same domain is a wiring bug and
 *   throws at construction instead of silently letting the last one win.
 * @param onDecision optional listener for the audit trail — `(request, result)`. It cannot influence
 *   the decision (it runs after the answer is fixed and inside `runCatching`), so linking the audit
 *   can never become a second policy.
 */
class PermissionCenter(
    policies: List<PermissionPolicy>,
    private val onDecision: ((PermissionRequest, PermissionResult) -> Unit)? = null,
) {
    private val byDomain: Map<PermissionDomain, PermissionPolicy>

    init {
        val routing = LinkedHashMap<PermissionDomain, PermissionPolicy>()
        policies.forEach { policy ->
            policy.domains.forEach { domain ->
                val previous = routing.put(domain, policy)
                require(previous == null) {
                    "two permission policies claim $domain: ${previous?.id} and ${policy.id}"
                }
            }
        }
        byDomain = routing
    }

    /** The domains this center can answer for; a request outside it is denied, not allowed. */
    val domains: Set<PermissionDomain> get() = byDomain.keys

    /** The policy that answers for [domain], or null when the domain is not covered. */
    fun policyFor(domain: PermissionDomain): PermissionPolicy? = byDomain[domain]

    /** Answers one request and hands the pair to the audit listener, if one is wired. */
    fun decide(request: PermissionRequest): PermissionResult {
        val result = evaluate(request)
        onDecision?.let { listener -> runCatching { listener.invoke(request, result) } }
        return result
    }

    /**
     * The rules, in order, as one expression: an emergency stop outranks everything, a request no
     * policy covers is refused rather than run unchecked, and Read-Only is enforced here as well as
     * inside a subsystem's own policy because the device policy is no longer the only path into the
     * platform.
     *
     * The safety operation is exempt from Read-Only on purpose: the emergency stop changes state (it
     * clears the run), and blocking it while Read-Only is on would take away the one control that
     * stops a runaway task. That exemption already existed in `ToolPermissionPolicy`; it is stated
     * here so no future policy can lose it.
     */
    private fun evaluate(request: PermissionRequest): PermissionResult {
        val policy = byDomain[request.domain]
        val evaluated = policy?.evaluate(request)
        val stopped = request.emergencyStop && !request.safetyOperation
        val readOnlyBlocks = request.readOnly && request.mutatesState && !request.safetyOperation
        return when {
            stopped ->
                refused(
                    "the emergency stop is active: ${request.operation} was not run " +
                        "(only the stop action itself stays reachable)",
                )
            policy == null ->
                refused(
                    "no policy is registered for ${request.domain}: ${request.operation} is refused " +
                        "rather than run unchecked",
                )
            evaluated == null -> refused("policy ${policy.id} does not cover ${request.operation}")
            readOnlyBlocks && evaluated.isAllowed ->
                refused(
                    "Read-Only mode is on: ${request.operation} changes state and was not run. " +
                        "Turn the read-only switch off if you really want it.",
                )
            else -> evaluated
        }
    }

    private fun refused(reason: String): PermissionResult =
        PermissionResult(
            level = ConfirmationLevel.DENY,
            reason = reason,
            decidedBy = PermissionResult.DECIDED_BY_CENTER,
        )
}
