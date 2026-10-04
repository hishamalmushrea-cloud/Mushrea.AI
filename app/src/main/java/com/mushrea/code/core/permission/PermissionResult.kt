package com.mushrea.code.core.permission

/**
 * The one answer the Permission Center gives, with enough information to explain it.
 *
 * It is not a boolean on purpose. A refusal has to say *which rule* refused it (so the agent and the
 * user can act on it), and a confirmation has to carry how much friction is required — "the user
 * answered a prompt" and "the user confirmed a high-risk operation explicitly" are different
 * records, and the audit keeps them apart.
 *
 * [decidedBy] is the policy id that produced the level, or `center` when a safety rule in
 * [PermissionCenter] overrode it; the tests assert on it, and it makes a contradictory policy
 * visible in the field instead of in a bug report.
 */
data class PermissionResult(
    val level: ConfirmationLevel,
    val reason: String,
    val decidedBy: String,
    /** True when a stored user override changed the level the subsystem declared. */
    val overridden: Boolean = false,
) {
    /** True when the operation may run without asking the user. */
    val isAllowed: Boolean get() = level.isAuto

    /** True when execution is blocked outright. */
    val isDenied: Boolean get() = level == ConfirmationLevel.DENY

    /** True when the user must answer before the operation runs. */
    val needsConfirmation: Boolean get() = level.needsUserAnswer

    /**
     * The projection the device bridge and its audit log already speak.
     *
     * Keeping this conversion in one place is what let the existing device pipeline (confirmation
     * prompt, `DeviceAuditLog` format 4, verification) stay untouched while the decision itself
     * moved behind the center.
     */
    fun asDecision(): PermissionDecision =
        when (level) {
            ConfirmationLevel.AUTO -> PermissionDecision.Allow(reason)
            ConfirmationLevel.CONFIRM,
            ConfirmationLevel.STRONG_CONFIRM,
            -> PermissionDecision.Confirm(level, reason)
            ConfirmationLevel.DENY -> PermissionDecision.Deny(reason)
        }

    companion object {
        /** The id [decidedBy] carries when one of the center's own safety rules decided. */
        const val DECIDED_BY_CENTER = "center"
    }
}
