package com.mushrea.code.core.execution

/**
 * How far an execution got.
 *
 * The stage is the whole point of this type: a command that was sent, a command the target ran and a
 * command whose *effect* was confirmed are three different facts, and reporting them as one boolean
 * is how an agent ends up telling the user "done" about a failure.
 */
enum class ExecutionStage {
    /** Policy refused it - nothing was sent. */
    REJECTED,

    /** The channel itself failed: no device, TLS refused, socket timeout. */
    TRANSPORT_FAILED,

    /** The command ran and reported failure (non-zero exit, error text). */
    COMMAND_FAILED,

    /** The command ran and reported success; the effect is not proven. */
    SUCCEEDED,

    /** The command ran and a follow-up check confirmed the effect. */
    VERIFIED,
}

/**
 * What came back.
 *
 * [exitCode] is null when the transport failed before a command existed - not zero, because zero
 * means "the target ran it and it worked".
 */
data class ExecutionResult(
    val stage: ExecutionStage,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val message: String = "",
    val durationMillis: Long = 0,
    val errorCode: String? = null,
    val artifacts: List<String> = emptyList(),
    val correlationId: String = "",
    /**
     * Round 2: the route the result came from, so an agent can say *how* something was done.
     *
     * [capability] is the capability that answered the step (`bin:pm`, `interp:python3`), [providerId]
     * the provider that ran it, and [fallback] the route that was rejected on the way - empty when the
     * first choice worked. [failureReason] is set only for a failed stage, and is the reason the agent
     * acts on ("the device has no uiautomator", not "exit 1").
     */
    val providerId: String = "",
    val targetId: String = "",
    val capability: String = "",
    val fallback: String = "",
    val failureReason: String = "",
) {
    /** True only when a command ran and did not report failure. */
    val ok: Boolean get() = stage == ExecutionStage.SUCCEEDED || stage == ExecutionStage.VERIFIED

    /** True when the effect itself was confirmed, which is the only "it really happened". */
    val verified: Boolean get() = stage == ExecutionStage.VERIFIED

    /** True when nothing was executed because the request never reached a device. */
    val rejected: Boolean get() = stage == ExecutionStage.REJECTED

    fun withVerification(confirmed: Boolean, detail: String = ""): ExecutionResult =
        if (!ok || !confirmed) {
            this
        } else {
            copy(stage = ExecutionStage.VERIFIED, message = detail.ifBlank { message })
        }

    /** This result, stamped with the route that produced it: provider, target and capability. */
    fun withRoute(
        providerId: String,
        targetId: String,
        capability: String = "",
        fallback: String = "",
    ): ExecutionResult =
        copy(
            providerId = providerId.ifBlank { this.providerId },
            targetId = targetId.ifBlank { this.targetId },
            capability = capability.ifBlank { this.capability },
            fallback = fallback.ifBlank { this.fallback },
        )

    /** This result, with the reason a failure happened - never used to dress up a success. */
    fun withFailureReason(reason: String): ExecutionResult =
        if (ok || reason.isBlank()) this else copy(failureReason = reason, message = message.ifBlank { reason })

    companion object {
        /** A refusal decided before any device was touched. */
        fun rejected(message: String, errorCode: String? = null): ExecutionResult =
            ExecutionResult(stage = ExecutionStage.REJECTED, message = message, errorCode = errorCode)

        /** The target ran the command and reported failure: a different fact from a dead channel. */
        fun commandFailed(
            message: String,
            exitCode: Int?,
            stdout: String = "",
            errorCode: String? = null,
            durationMillis: Long = 0,
        ): ExecutionResult =
            ExecutionResult(
                stage = ExecutionStage.COMMAND_FAILED,
                exitCode = exitCode,
                stdout = stdout,
                message = message,
                errorCode = errorCode,
                durationMillis = durationMillis,
            ).withFailureReason(message)

        /** A channel-level failure; [errorCode] carries the transport's own vocabulary. */
        fun transportFailed(
            message: String,
            errorCode: String? = null,
            durationMillis: Long = 0,
        ): ExecutionResult =
            ExecutionResult(
                stage = ExecutionStage.TRANSPORT_FAILED,
                message = message,
                errorCode = errorCode,
                durationMillis = durationMillis,
            )
    }
}
