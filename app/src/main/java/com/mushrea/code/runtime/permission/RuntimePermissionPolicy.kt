package com.mushrea.code.runtime.permission

import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionDomain
import com.mushrea.code.core.permission.PermissionPolicy
import com.mushrea.code.core.permission.PermissionRequest
import com.mushrea.code.core.permission.PermissionResult
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource

/**
 * The runtime domain's rules: the agent's own permission prompts, and the on-device runtime's
 * lifecycle.
 *
 * **Agent permission prompts.** The coding agent asks *its* runtime whether it may run one of its
 * own tools (edit a file, run a command). Until P2 the chat, the voice session and the schedule
 * runner each answered that with their own `if (autoAcceptPermissions)`, which made the app's
 * standing authorization an unwritten third policy. Here it is the `preAuthorized` input of one
 * request: with it the run answers `ONCE` on the user's behalf and the audit says so; without it
 * the answer is a denial, which the caller reads as "ask the user" — exactly the behaviour the three
 * call sites had, now decided in one place.
 *
 * **Runtime lifecycle.** Installing, starting, stopping and deleting the on-device Linux runtime is
 * state-changing and can download hundreds of megabytes. A user tap, the boot auto-start and a
 * scheduled run are allowed (that is what those paths do today, and a schedule that could not start
 * its own runtime could not run at all). An *agent* asking for it — a path that does not exist
 * today — is not waved through: it needs a confirmation, so a future tool cannot silently drive the
 * runtime by being added to the catalog.
 *
 * The policy never executes anything: the caller starts the runtime, or answers the prompt.
 */
class RuntimePermissionPolicy : PermissionPolicy {
    override val id: String = ID

    override val domains: Set<PermissionDomain> =
        setOf(PermissionDomain.AGENT_RUNTIME, PermissionDomain.RUNTIME_LIFECYCLE)

    override fun evaluate(request: PermissionRequest): PermissionResult? =
        when (request.domain) {
            PermissionDomain.AGENT_RUNTIME -> evaluateAgentPrompt(request)
            PermissionDomain.RUNTIME_LIFECYCLE -> evaluateLifecycle(request)
            else -> null
        }

    private fun evaluateAgentPrompt(request: PermissionRequest): PermissionResult? {
        if (request.operation != OP_AGENT_AUTO_ACCEPT) return null
        return if (request.preAuthorized) {
            PermissionResult(
                level = ConfirmationLevel.AUTO,
                reason =
                    "the user turned on auto-accept for agent permission prompts, so " +
                        "${request.operation} answers on their behalf for this ${labelOf(request.source)} request",
                decidedBy = id,
            )
        } else {
            PermissionResult(
                level = ConfirmationLevel.DENY,
                reason =
                    "auto-accept is off: the agent's permission prompt must be answered by the user " +
                        "(the denial only means the app will not answer for them)",
                decidedBy = id,
            )
        }
    }

    private fun evaluateLifecycle(request: PermissionRequest): PermissionResult? {
        if (request.operation !in LIFECYCLE_OPERATIONS) return null
        val target = request.target?.takeIf { it.isNotBlank() } ?: "the on-device runtime"
        return when (request.source) {
            PermissionSource.USER ->
                PermissionResult(
                    level = ConfirmationLevel.AUTO,
                    reason = "the user asked for ${request.operation} on $target",
                    decidedBy = id,
                )
            PermissionSource.SYSTEM ->
                PermissionResult(
                    level = ConfirmationLevel.AUTO,
                    reason = "the app ran ${request.operation} on $target as part of its own startup",
                    decidedBy = id,
                )
            PermissionSource.SCHEDULE ->
                PermissionResult(
                    level = ConfirmationLevel.AUTO,
                    reason =
                        "a scheduled run needs $target running; starting it is what makes the run " +
                            "possible and it does not install anything new",
                    decidedBy = id,
                )
            PermissionSource.AGENT ->
                PermissionResult(
                    level = ConfirmationLevel.CONFIRM,
                    reason =
                        "an agent asked for ${request.operation} on $target; runtime lifecycle " +
                            "operations are confirmed when an agent asks for them",
                    decidedBy = id,
                )
        }
    }

    companion object {
        const val ID = "runtime"

        const val OP_AGENT_AUTO_ACCEPT = "agent.permission.auto_accept"
        const val OP_LIFECYCLE_START = "runtime.lifecycle.start"
        const val OP_LIFECYCLE_INSTALL = "runtime.lifecycle.install"
        const val OP_LIFECYCLE_STOP = "runtime.lifecycle.stop"
        const val OP_LIFECYCLE_DELETE = "runtime.lifecycle.delete"

        val LIFECYCLE_OPERATIONS: Set<String> =
            setOf(OP_LIFECYCLE_START, OP_LIFECYCLE_INSTALL, OP_LIFECYCLE_STOP, OP_LIFECYCLE_DELETE)

        /**
         * The request a caller builds when the agent's runtime asks for a permission and the app
         * must decide whether to answer it on the user's behalf.
         */
        fun agentPromptRequest(
            source: PermissionSource,
            preAuthorized: Boolean,
            target: String? = null,
        ): PermissionRequest =
            PermissionRequest(
                domain = PermissionDomain.AGENT_RUNTIME,
                operation = OP_AGENT_AUTO_ACCEPT,
                source = source,
                target = target,
                risk = PermissionRisk.MEDIUM,
                mutatesState = true,
                preAuthorized = preAuthorized,
            )

        /** The request a caller builds before starting or stopping the on-device runtime. */
        fun lifecycleRequest(
            operation: String,
            source: PermissionSource,
            target: String? = null,
            readOnly: Boolean = false,
            emergencyStop: Boolean = false,
        ): PermissionRequest =
            PermissionRequest(
                domain = PermissionDomain.RUNTIME_LIFECYCLE,
                operation = operation,
                source = source,
                target = target,
                risk = PermissionRisk.MEDIUM,
                mutatesState = true,
                readOnly = readOnly,
                emergencyStop = emergencyStop,
            )

        /** A short word for the reason strings, so the audit reads as a sentence. */
        private fun labelOf(source: PermissionSource): String =
            when (source) {
                PermissionSource.USER -> "user"
                PermissionSource.AGENT -> "agent"
                PermissionSource.SCHEDULE -> "scheduled"
                PermissionSource.SYSTEM -> "app"
            }
    }
}
