package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.ExecutionOperation
import com.mushrea.code.core.execution.ExecutionProvider
import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.execution.ExecutionResult
import com.mushrea.code.core.execution.ExecutionStage
import com.mushrea.code.core.execution.ExecutionTransport
import com.mushrea.code.runtime.local.AdbShellRunner

/**
 * The peer-ADB execution provider: whatever `adb` can do to another phone, in one place.
 *
 * This is where the platform stops being a list of tools. A request names an *operation*
 * ([ExecutionOperation.SHELL], `EXEC`, `PUSH`, `PULL`, `INSTALL`, `SCRIPT`, `PROBE`) and the provider
 * turns it into the `adb` line that does it - so the agent's ceiling is what adb and the device can
 * do, not what somebody added to a table. Adding a capability later means adding a provider (or a
 * method on the device's own shell), never re-cutting the bridge.
 *
 * Two rules the provider enforces itself, because both are safety properties rather than policy:
 *
 *  1. **a serial is required.** Every command carries `-s <serial>`; the runtime's own device is on
 *     the same adb server, so an unqualified command is how the wrong phone gets modified.
 *  2. **the declared effect is checked against the command.** A request claiming "read-only" for a
 *     line the classifier reads as destructive or writing is refused outright - policy can be lied
 *     to, the command itself cannot. Everything else is the Permission Center's call, made before
 *     this provider is ever reached.
 */
class PeerAdbProvider(
    private val runner: AdbShellRunner,
) : ExecutionProvider {
    override val id: String = ID

    override val transport: ExecutionTransport = ExecutionTransport.PEER_ADB

    /** Every listed operation is served: the transport is generic on purpose. */
    override fun supports(operation: ExecutionOperation): Boolean = operation in SUPPORTED

    override fun requirements(operation: ExecutionOperation): Set<String> =
        when (operation) {
            ExecutionOperation.EXEC -> setOf(CapabilityNames.EXEC_OUT)
            ExecutionOperation.SCRIPT -> setOf(CapabilityNames.SHELL)
            ExecutionOperation.PUSH, ExecutionOperation.PULL -> setOf(CapabilityNames.SYNC)
            else -> setOf(CapabilityNames.SHELL)
        }

    /**
     * A device without `exec-out` can still run the same program through its shell; the planner
     * rewrites the step instead of reporting the device as incapable.
     */
    override fun alternative(
        operation: ExecutionOperation,
        missing: String,
    ): ExecutionOperation? =
        when {
            operation == ExecutionOperation.EXEC && missing == CapabilityNames.EXEC_OUT -> ExecutionOperation.SHELL
            else -> null
        }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val serial = request.target.id
        val conflict = PeerExecutionGuard.check(request)
        if (conflict != null) {
            return ExecutionResult.rejected(conflict, errorCode = PeerAdbErrorCode.PERMISSION_REQUIRED.name)
        }
        val line =
            runCatching { commandFor(serial, request) }.getOrElse { failure ->
                return ExecutionResult.rejected(failure.message ?: "the request cannot be turned into an adb command")
            }
        val startedAt = System.currentTimeMillis()
        val outcome =
            runCatching {
                runner.runShellOnIo(line, (request.policy.timeoutMillis / 1000).coerceAtLeast(MIN_TIMEOUT_SECONDS))
            }
        val duration = System.currentTimeMillis() - startedAt
        val route = Route(id, serial, capabilityFor(request.operation))
        return outcome.fold(
            onSuccess = { result ->
                val stage =
                    when {
                        result.exitCode == 0 -> ExecutionStage.SUCCEEDED
                        else -> ExecutionStage.COMMAND_FAILED
                    }
                val failure =
                    if (stage == ExecutionStage.SUCCEEDED) {
                        null
                    } else {
                        PeerAdbErrorClassifier.classify(result.output, PeerAdbErrorCode.COMMAND_FAILED)
                    }
                ExecutionResult(
                    stage = stage,
                    exitCode = result.exitCode,
                    stdout = result.output,
                    message =
                        if (stage == ExecutionStage.SUCCEEDED) {
                            "${request.operation} completed on $serial"
                        } else {
                            failure?.let { "${it.code}: ${it.detail}" }.orEmpty()
                        },
                    durationMillis = duration,
                    errorCode = if (stage == ExecutionStage.SUCCEEDED) null else PeerAdbErrorCode.COMMAND_FAILED.name,
                    correlationId = request.correlationId,
                )
                    .withRoute(route.providerId, route.targetId, route.capability)
                    .withFailureReason(failure?.let { "${it.code}: ${it.detail}" }.orEmpty())
            },
            onFailure = { throwable ->
                val error =
                    PeerAdbErrorClassifier.classify(
                        throwable.message.orEmpty(),
                        PeerAdbErrorCode.RUNTIME_UNAVAILABLE,
                    )
                ExecutionResult.transportFailed(
                    message = "${error.code}: ${error.detail}",
                    errorCode = error.code.name,
                    durationMillis = duration,
                ).copy(correlationId = request.correlationId, stderr = error.nextStep)
                    .withRoute(route.providerId, route.targetId, route.capability)
                    .withFailureReason("${error.code}: ${error.detail}")
            },
        )
    }

    /** The route a result names: who ran it, where, and with which capability. */
    private data class Route(
        val providerId: String,
        val targetId: String,
        val capability: String,
    )

    /**
     * Builds the `adb` line for one request.
     *
     * `SCRIPT` travels as a heredoc on adb's stdin with a quoted delimiter, so the runtime's shell
     * never expands anything inside it and the *device's* shell runs it as written.
     */
    internal fun commandFor(
        serial: String,
        request: ExecutionRequest,
    ): String {
        val invocation = request.invocation
        return when (request.operation) {
            ExecutionOperation.SHELL, ExecutionOperation.PROBE ->
                AdbCommandLine.shell(serial, invocation.command)
            ExecutionOperation.EXEC -> {
                val argv = (listOf(invocation.command) + invocation.arguments).joinToString(" ") { AdbCommandLine.quote(it) }
                AdbCommandLine.execOut(serial, argv)
            }
            ExecutionOperation.SCRIPT -> {
                val interpreter = invocation.interpreter?.takeIf(String::isNotBlank) ?: "sh"
                val delimiter = "MUSHREA_EOF_${Math.abs(invocation.command.hashCode())}"
                AdbCommandLine.shell(serial, "$interpreter -s <<'$delimiter'\n${invocation.command}\n$delimiter")
            }
            ExecutionOperation.PUSH ->
                AdbCommandLine.adb(
                    serial,
                    "push",
                    invocation.files.firstOrNull()?.localPath.orEmpty(),
                    invocation.files.firstOrNull()?.remotePath.orEmpty(),
                ).also { require(invocation.files.isNotEmpty()) { "push needs a file pair" } }
            ExecutionOperation.PULL ->
                AdbCommandLine.adb(
                    serial,
                    "pull",
                    invocation.files.firstOrNull()?.remotePath.orEmpty(),
                    invocation.files.firstOrNull()?.localPath.orEmpty(),
                ).also { require(invocation.files.isNotEmpty()) { "pull needs a file pair" } }
            ExecutionOperation.INSTALL ->
                AdbCommandLine.adb(serial, "install", "-r", invocation.files.firstOrNull()?.localPath.orEmpty())
                    .also { require(invocation.files.isNotEmpty()) { "install needs an apk path" } }
        }
    }

    companion object {
        const val ID = "peer-adb"

        private const val MIN_TIMEOUT_SECONDS = 5L

        private val SUPPORTED =
            setOf(
                ExecutionOperation.PROBE,
                ExecutionOperation.SHELL,
                ExecutionOperation.EXEC,
                ExecutionOperation.PUSH,
                ExecutionOperation.PULL,
                ExecutionOperation.INSTALL,
                ExecutionOperation.SCRIPT,
            )
    }
}

/**
 * The one check that cannot be delegated to policy: whether the request's own declaration matches
 * the command it carries.
 *
 * Returns a refusal reason, or null when the request is coherent. Only a *false* claim of read-only
 * is refused - a destructive command that declares itself destructive is a legitimate, confirmed
 * request and goes to the gate.
 */
object PeerExecutionGuard {
    /**
     * What the request would do, from its operation and its command.
     *
     * The file operations cannot be classified by reading a command line - they *are* the command -
     * so their effect is stated here instead of guessed: pulling reads, pushing writes, installing
     * replaces an app on someone else's phone.
     */
    fun verdictFor(request: ExecutionRequest): PeerCommandVerdict =
        when (request.operation) {
            ExecutionOperation.PULL -> PeerCommandVerdict(PeerCommandClass.READ_ONLY, "pull", "pull only reads the target")
            ExecutionOperation.PROBE -> PeerCommandVerdict(PeerCommandClass.READ_ONLY, "probe", "a probe only reads facts")
            ExecutionOperation.PUSH -> PeerCommandVerdict(PeerCommandClass.STATE_CHANGING, "push", "push writes a file on the target")
            ExecutionOperation.INSTALL ->
                PeerCommandVerdict(PeerCommandClass.DESTRUCTIVE, "install", "an install replaces an app on the target")
            ExecutionOperation.EXEC -> PeerCommandClassifier.classify(request.invocation.command, request.invocation.arguments)
            else -> PeerCommandClassifier.classify(request.invocation.command)
        }

    fun check(request: ExecutionRequest): String? {
        val verdict = verdictFor(request)
        val claimsReadOnly = !request.effect.mutatesTarget
        return if (claimsReadOnly && verdict.mutatesTarget) {
            "the request declares a read-only ${request.operation} but '${verdict.program}' changes the target " +
                "(matched: ${verdict.rule}); re-send it with the effect it really has"
        } else {
            null
        }
    }
}
