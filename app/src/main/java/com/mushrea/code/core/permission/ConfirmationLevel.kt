package com.mushrea.code.core.permission

/**
 * How strictly a permission gate treats an action.
 *
 * It lives in `core` because both the device tools and the runtime permission prompts classify
 * themselves with it, and the one decision layer (see [PermissionDecision]) has to speak about both
 * without knowing which layer asked.
 */
enum class ConfirmationLevel {
    /** Runs immediately without asking the user. */
    AUTO,

    /** Asks the user once (notification with Allow / Reject actions) before running. */
    CONFIRM,

    /** Asks the user and requires an explicit acknowledgment every single time. */
    STRONG,
}
