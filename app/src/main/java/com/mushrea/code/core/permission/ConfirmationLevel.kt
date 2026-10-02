package com.mushrea.code.core.permission

/**
 * The one decision vocabulary of the Permission Center (P2).
 *
 * Every subsystem that needs an authorization answer — the device tool catalog, the runtime
 * lifecycle, the agent's own permission prompts — is evaluated to one of these four values by
 * [PermissionCenter]. Nothing outside the center is allowed to invent a fifth outcome or to decide
 * by itself that a level is "close enough" to allow.
 *
 * It lives in `core` because the device tools, the schedule runner and the runtime all classify
 * themselves with it, and the one decision layer has to speak about all of them without knowing
 * which layer asked.
 */
enum class ConfirmationLevel {
    /** Runs immediately without asking the user. */
    AUTO,

    /** Asks the user once (notification with Allow / Reject actions) before running. */
    CONFIRM,

    /**
     * Asks the user and requires an explicit acknowledgment every single time: high-risk operations
     * that must never become "remembered" approvals.
     */
    STRONG_CONFIRM,

    /**
     * Does not run at all under the current policy, whoever asks and however it is reached.
     *
     * A denial is a *policy* outcome, not an error: the [PermissionResult] that carries it always
     * says which rule refused. The only exception in the whole design is the emergency-stop action
     * itself, which stays reachable while everything else is denied.
     */
    DENY,
    ;

    /** True when the operation may run without asking the user. */
    val isAuto: Boolean get() = this == AUTO

    /** True when the operation needs a user answer before it may run. */
    val needsUserAnswer: Boolean get() = this == CONFIRM || this == STRONG_CONFIRM

    companion object {
        /**
         * Parses a stored or transmitted name, accepting the pre-P2 spelling of [STRONG_CONFIRM].
         *
         * `DeviceAgentStore` persisted user overrides as `"STRONG"` before the unified vocabulary;
         * without this the stored override would silently stop matching and a tap the user had
         * raised to strong confirmation would fall back to its catalog level.
         */
        fun parseOrNull(raw: String?): ConfirmationLevel? =
            when (raw?.trim()?.uppercase()) {
                null, "" -> null
                "AUTO" -> AUTO
                "CONFIRM" -> CONFIRM
                "STRONG", "STRONG_CONFIRM" -> STRONG_CONFIRM
                "DENY" -> DENY
                else -> null
            }
    }
}
