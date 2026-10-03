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

    companion object {
        /** A refusal decided before any device was touched. */
        fun rejected(message: String, errorCode: String? = null): ExecutionResult =
            ExecutionResult(stage = ExecutionStage.REJECTED, message = message, errorCode = errorCode)

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
