package com.mushrea.code.core.permission

/**
 * The answer one permission layer gives before anything runs.
 *
 * Until this existed, "may this run, and who asked?" was answered in five different places: the
 * bridge's own read-only check, the firewall's level lookup, per-tool policies (`TermuxCommandPolicy`,
 * `PayloadGuard`, the sensitive-tap keywords), the user's stored overrides, and the stop flag. A
 * caller could pass four of them and reach the executor anyway. A single value with a stated reason
 * means a caller either obeyed one decision or can be shown not to have.
 *
 * [Deny] carries the reason because a refusal must be explainable to both the agent and the user;
 * [Confirm] carries the level because the level itself is the record of how much friction was asked
 * for.
 */
sealed interface PermissionDecision {
    /** Why this answer was given; shown to the agent on denial and written to the audit log. */
    val reason: String

    /** Runs without asking the user. */
    data class Allow(
        override val reason: String,
    ) : PermissionDecision

    /** Runs only after the user answers a prompt of [level] strength. */
    data class Confirm(
        val level: ConfirmationLevel,
        override val reason: String,
    ) : PermissionDecision

    /** Does not run; [reason] says which policy refused it. */
    data class Deny(
        override val reason: String,
    ) : PermissionDecision

    /** True when no user interaction is needed. */
    val allowed: Boolean get() = this is Allow

    /** True when execution is blocked outright. */
    val denied: Boolean get() = this is Deny

    /** One word for the audit log and the UI: allow, confirm or deny. */
    val label: String
        get() =
            when (this) {
                is Allow -> "allow"
                is Confirm -> "confirm"
                is Deny -> "deny"
            }
}

/**
 * Who asked for the action.
 *
 * The device channel cannot authenticate its writer: the agent writes a command file and the app
 * reads it, so [AGENT] is a *claimed* identity, not a verified one, and the audit log says so. It is
 * still worth recording, because "an agent asked and the user allowed it" and "the app did it on its
 * own" are different events.
 */
enum class PermissionActor {
    /** The coding agent, through its tool call (or a schedule that runs one). */
    AGENT,

    /** The user, through the app's own controls. */
    USER,

    /** The app itself, without a request (a readiness probe, a retry, a boot receiver). */
    SYSTEM,
}
