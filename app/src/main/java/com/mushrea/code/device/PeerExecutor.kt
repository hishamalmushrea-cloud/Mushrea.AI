package com.mushrea.code.device

import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.core.execution.ExecutionEffect
import com.mushrea.code.core.execution.ExecutionFile
import com.mushrea.code.core.execution.ExecutionGoal
import com.mushrea.code.core.execution.ExecutionInvocation
import com.mushrea.code.core.execution.ExecutionOperation
import com.mushrea.code.core.execution.ExecutionPlan
import com.mushrea.code.core.execution.ExecutionPolicy
import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.execution.ExecutionResult
import com.mushrea.code.core.execution.ExecutionStage
import com.mushrea.code.core.execution.ExecutionTarget
import com.mushrea.code.core.execution.ExecutionTransport
import com.mushrea.code.core.execution.PlanIntent
import com.mushrea.code.core.peer.PeerDevice
import com.mushrea.code.core.permission.PermissionCenter
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource
import com.mushrea.code.device.bridge.GoalOutcome
import com.mushrea.code.device.bridge.PeerAdbBridge
import com.mushrea.code.device.bridge.PeerAdbErrorClassifier
import com.mushrea.code.device.bridge.PeerAdbFailure
import com.mushrea.code.device.bridge.PeerCommandClassifier
import com.mushrea.code.device.bridge.PeerCommandVerdict
import com.mushrea.code.device.permission.PeerDevicePolicy
import com.mushrea.code.device.permission.PeerOperations
import com.mushrea.code.device.tool.OutcomeVerification
import org.json.JSONArray
import org.json.JSONObject

/**
 * One parsed `peer_execute` call: the operation, what runs, and what the caller says it costs.
 *
 * Kept as a small value so the parsing, the request construction and the result payload stay three
 * short functions instead of one long one - and so the request the policy sees can be printed in a
 * test without a device.
 */
private data class PeerOperationCall(
    val operation: ExecutionOperation,
    val invocation: ExecutionInvocation,
    val effect: ExecutionEffect,
    val reason: String,
    val timeoutMillis: Long,
    val verifyCommand: String,
)

/**
 * The Device Agent's peer-phone executor: the bridge between the agent's tool call and
 * [PeerAdbBridge].
 *
 * It is deliberately thin, and deliberately *generic*: one method ([executeOperation]) runs anything
 * the peer speaks (shell line, program, script, push, pull, install), because the agent's reach must
 * equal what the other phone can do rather than what somebody enumerated here. The session methods
 * (pair, connect, capabilities, plan) exist so the agent can set up and inspect a device by itself
 * once the user has allowed the pairing.
 *
 * What this class does *not* do is decide anything: the device tool gate (catalog risk +
 * confirmation) runs before it in [DeviceAgentBridge], the peer policy is asked here for the session
 * steps, and the execution path asks it again inside the bridge - the one place a peer command is
 * actually governed. Nothing here talks to `adb`.
 */
class PeerExecutor(
    private val store: DeviceAgentStore,
    private val permissionCenter: PermissionCenter,
    /** Resolved lazily: the peer platform is built by the application, which may still be starting. */
    private val bridgeProvider: () -> PeerAdbBridge?,
) {
    private fun bridge(): PeerAdbBridge =
        bridgeProvider() ?: throw DeviceFileAgent.DeviceAgentError(
            "the peer device platform is not available yet - open Mushrea Code once and try again",
        )

    // ---- session ------------------------------------------------------------------------------

    /** Every known phone, with what is known about it. Read-only. */
    suspend fun executeDevices(): JSONObject.() -> Unit {
        val devices = bridge().devices()
        val array = JSONArray()
        devices.forEach { device -> array.put(device.toJson()) }
        return {
            put("devices", array)
            put("summary", if (devices.isEmpty()) "no peer phone has been paired yet" else "${devices.size} peer phone(s) known")
            OutcomeVerification.apply(
                this,
                OutcomeVerification.passed("read from the peer registry, which is filled from real mDNS announcements and adb results"),
            )
        }
    }

    /**
     * Starts a QR pairing session and returns the payload for the user to scan with the other phone.
     *
     * No command is sent anywhere: the payload is what the *other* phone's wireless-debugging screen
     * (or any QR scanner on it) needs in order to start its own pairing server under the name we ask
     * for. The phones must be on the same network.
     */
    suspend fun executePairQr(): JSONObject.() -> Unit {
        refusal(PeerOperations.PAIR, target = null)?.let { throw DeviceFileAgent.DeviceAgentError(it) }
        val payload = bridge().beginPairingQr()
        return {
            put("payload", payload.encode())
            put("service_name", payload.serviceName)
            put(
                "instructions",
                "on the other phone open Developer options > Wireless debugging > Pair device with QR code " +
                    "and scan this code; keep that screen open until this app connects",
            )
            put("next", "call peer_connect once the phone appears in peer_devices")
            put("summary", "pairing QR ready for ${payload.serviceName}")
        }
    }

    /** Pairs with the six-digit code the other phone shows, for when its QR scanner is unavailable. */
    suspend fun executePairCode(params: JSONObject): JSONObject.() -> Unit {
        val host = params.optString("host").trim()
        val port = params.optInt("port")
        val code = params.optString("code").trim()
        if (host.isBlank()) {
            throw DeviceFileAgent.DeviceAgentError("host is required (the IP on the other phone's wireless-debugging screen)")
        }
        if (port <= 0) throw DeviceFileAgent.DeviceAgentError("port is required (the pairing port shown next to the code)")
        if (code.isBlank()) throw DeviceFileAgent.DeviceAgentError("code is required (the six digits on the other phone)")
        refusal(PeerOperations.PAIR, target = "$host:$port")?.let { throw DeviceFileAgent.DeviceAgentError(it) }
        val device = bridge().pairWithCode(host, port, code).getOrElse { throw failure(it) }
        return {
            put("device", device.toJson())
            put("summary", "paired with ${device.label}")
        }
    }

    /** Opens the ADB channel to a phone that is already paired (the port is rediscovered, not remembered). */
    suspend fun executeConnect(params: JSONObject): JSONObject.() -> Unit {
        val serial = requireSerial(params)
        refusal(PeerOperations.CONNECT, target = serial)?.let { throw DeviceFileAgent.DeviceAgentError(it) }
        val device = bridge().reconnect(serial).getOrElse { throw failure(it) }
        val capabilities = bridge().device(serial)?.capabilityReport() ?: CapabilityReport.unknown()
        return {
            put("device", device.toJson())
            put("capabilities", capabilities.toJson())
            put("summary", "connected to ${device.label}; ${capabilitySummary(capabilities)}")
        }
    }

    suspend fun executeDisconnect(params: JSONObject): JSONObject.() -> Unit {
        val serial = requireSerial(params)
        refusal(PeerOperations.DISCONNECT, target = serial)?.let { throw DeviceFileAgent.DeviceAgentError(it) }
        bridge().disconnect(serial).getOrElse { throw failure(it) }
        return {
            put("serial", serial)
            put("summary", "disconnected $serial (the pairing is kept)")
        }
    }

    /** Re-probes what the phone can actually do: binaries, applets, properties, exit codes. */
    suspend fun executeCapabilities(params: JSONObject): JSONObject.() -> Unit {
        val serial = requireSerial(params)
        val report = bridge().refreshCapabilities(serial).getOrElse { throw failure(it) }
        return {
            put("serial", serial)
            put("capabilities", report.toJson())
            put("summary", capabilitySummary(report))
            OutcomeVerification.apply(
                this,
                OutcomeVerification.passed("the phone answered a probe of its own shell, binaries and properties"),
            )
        }
    }

    /**
     * Turns what the agent wants into the steps the device can actually run, or names the blocker.
     *
     * Three ways in, one answer: a **recipe** (`recipe` + `parameters`), a goal in words (`goal`), or a
     * list of raw operations (`operations`). The recipe form is the general one - an objective nobody
     * shipped a tool for is still planned from what the phone reported it can do - and the raw form
     * stays for callers that already know which primitive they want.
     */
    suspend fun executePlan(params: JSONObject): JSONObject.() -> Unit {
        val serial = requireSerial(params)
        val device =
            bridge().device(serial) ?: throw DeviceFileAgent.DeviceAgentError(
                "no peer phone is registered as '$serial'; call peer_devices first",
            )
        val capabilities = device.capabilityReport()
        val recipeId = params.optString("recipe").trim().ifBlank { null }
        val goalText = params.optString("goal").trim()
        if (recipeId != null || goalText.isNotBlank()) {
            val goal = readGoal(params, serial)
            val routes = bridge().routes(goal, capabilities)
            val plan = routes.first()
            val candidates =
                JSONArray().apply {
                    plan.candidates.forEach { line -> put(line) }
                }
            val routesJson =
                JSONArray().apply {
                    routes.forEach { route ->
                        put(
                            JSONObject()
                                .put("recipe", route.recipeId)
                                .put("steps", JSONArray(route.steps.map { step -> step.description }))
                                .put("blocked", route.blockedReason()),
                        )
                    }
                }
            return {
                put("recipe", plan.recipeId)
                put("parameters", JSONObject(plan.parameters))
                put("candidates", candidates)
                put("routes", routesJson)
                planPayload(this, plan, device.label)
            }
        }
        val intents = readIntents(params)
        if (intents.isEmpty()) {
            // Nothing to plan is not an error: the answer is what *can* be asked for. That is how an
            // agent with no pre-existing tool for a request discovers the objective it needs.
            val recipes =
                JSONArray().apply {
                    bridge().recipes().forEach { recipe ->
                        put(
                            JSONObject()
                                .put("recipe", recipe.id)
                                .put("title", recipe.title)
                                .put("description", recipe.description)
                                .put("parameters", JSONArray(recipe.parameters.map { parameter -> parameter.name }))
                                .put("keywords", JSONArray(recipe.keywords)),
                        )
                    }
                }
            return {
                put("recipes", recipes)
                put("summary", "${recipes.length()} recipe(s) are available; ask for one by id or in words via goal")
            }
        }
        val plan = bridge().plan(intents, capabilities)
        return { planPayload(this, plan, device.label) }
    }

    /** The plan half of a `peer_plan` answer: the route, the trail, and the blocker's way out. */
    private fun planPayload(
        payload: JSONObject,
        plan: ExecutionPlan,
        label: String,
    ) {
        val steps = JSONArray()
        plan.steps.forEach { step ->
            steps.put(
                JSONObject()
                    .put("provider", step.providerId)
                    .put("operation", step.operation.name)
                    .put("description", step.description)
                    .put("capability", step.capability)
                    .put("requires", JSONArray(step.requirements.toList()))
                    .put("unmeasured", JSONArray(step.unproven))
                    .put("rewritten_from", step.rewrittenFrom?.name)
                    .put("fallback_reason", step.fallbackReason),
            )
        }
        val blockers = JSONArray()
        plan.blockers.forEach { blocker ->
            blockers.put(
                JSONObject()
                    .put("operation", blocker.operation.name)
                    .put("capability", blocker.capability)
                    .put("reason", blocker.reason)
                    .put("hint", blocker.hint),
            )
        }
        payload
            .put("feasible", plan.feasible)
            .put("steps", steps)
            .put("blockers", blockers)
            .put(
                "summary",
                if (plan.feasible) {
                    "${plan.steps.size} step(s) are possible on $label: ${plan.summary()}"
                } else {
                    plan.blockedReason()
                },
            )
    }

    // ---- execution ----------------------------------------------------------------------------

    /**
     * Runs one operation on one peer phone.
     *
     * The parameters are data, never a shell string the caller assembled by hand: the request carries
     * the operation, the program or shell line, its arguments and its files, and the provider turns
     * that into a quoted `adb` invocation. `verify_command` is an optional follow-up that actually
     * runs on the phone afterwards - only when it succeeds is the result reported as verified.
     */
    suspend fun executeOperation(params: JSONObject): JSONObject.() -> Unit {
        val serial = requireSerial(params)
        val recipeId = params.optString("recipe").trim().ifBlank { null }
        if (recipeId != null || params.optString("goal").isNotBlank()) return executeGoal(params, serial)
        val call = parseCall(params)
        val target = bridge().device(serial)?.target() ?: ExecutionTarget(id = serial, transport = ExecutionTransport.PEER_ADB)
        val request =
            ExecutionRequest(
                operation = call.operation,
                target = target,
                invocation = call.invocation,
                effect = call.effect,
                policy =
                    ExecutionPolicy(
                        requestedBy = PermissionSource.AGENT,
                        reason = call.reason,
                        timeoutMillis = call.timeoutMillis,
                        verify = call.verifyCommand.isNotBlank(),
                        // The user has just answered this tool call's own confirmation prompt, so the
                        // peer policy is not asked the same question twice. A destructive command is
                        // still strong-confirmed: that level is never inheritable.
                        preAuthorized = true,
                    ),
            )
        val result = bridge().execute(request)
        val verification = call.verifyCommand.takeIf(String::isNotBlank)?.let { followUp -> verify(serial, followUp, result) }
        if (result.stage != ExecutionStage.SUCCEEDED && result.stage != ExecutionStage.VERIFIED) {
            // Reported as a failure *before* a payload exists: the bridge turns a thrown error into a
            // result the agent reads, and a failure must never arrive as a successful payload.
            throw DeviceFileAgent.DeviceAgentError(summaryOf(result) + routeOf(result))
        }
        return resultPayload(result, verification)
    }

    /**
     * Runs an **objective**, not a command: the planner picks the route the phone can take, the bridge
     * walks the feasible routes until one works, and every route it abandoned is reported with the
     * reason. This is the path that means the agent never needs a tool per command: a recipe exists for
     * what is common, and anything else goes through `shell.run` or a raw operation.
     */
    private suspend fun executeGoal(
        params: JSONObject,
        serial: String,
    ): JSONObject.() -> Unit {
        val goal = readGoal(params, serial)
        val verifyCommand = params.optString("verify_command").trim()
        val outcome =
            bridge().executeGoal(
                goal = goal.copy(verify = goal.verify || verifyCommand.isNotBlank()),
                policy =
                    ExecutionPolicy(
                        requestedBy = PermissionSource.AGENT,
                        reason = params.optString("reason"),
                        timeoutMillis = params.optLong("timeout_seconds", 60L).coerceIn(5L, 1_800L) * 1_000L,
                        verify = goal.verify || verifyCommand.isNotBlank(),
                        // The user has just answered the tool call's own confirmation, so the center is
                        // not asked the same question twice; a destructive step is still strong-confirmed.
                        preAuthorized = true,
                    ),
            )
        val result = outcome.result
        if (result == null || !result.ok) {
            throw DeviceFileAgent.DeviceAgentError(
                "${outcome.plan.blockedReason().ifBlank { outcome.failureReason }}${routeTrail(outcome)}",
            )
        }
        val followUp = verifyCommand.takeIf(String::isNotBlank)?.let { command -> verify(serial, command, result) }
        return resultPayload(result, followUp, outcome)
    }

    /** The goal description a recipe call carries: the recipe name, the words, and the parameters. */
    private fun readGoal(
        params: JSONObject,
        serial: String,
    ): ExecutionGoal =
        ExecutionGoal(
            description = params.optString("goal").trim(),
            transport = ExecutionTransport.PEER_ADB,
            targetId = serial,
            recipeId = params.optString("recipe").trim().ifBlank { null },
            parameters = readParameters(params),
            verify = params.optBoolean("verify", false),
        )

    /** Recipe parameters as a string map; a number or boolean is accepted and stringified. */
    private fun readParameters(params: JSONObject): Map<String, String> {
        val values = params.optJSONObject("parameters") ?: return emptyMap()
        return values.keys().asSequence().associateWith { key -> values.opt(key)?.toString().orEmpty() }
    }

    private fun resultPayload(
        result: ExecutionResult,
        verification: OutcomeVerification?,
        outcome: GoalOutcome? = null,
    ): JSONObject.() -> Unit =
        {
            put("stage", result.stage.name)
            put("exit_code", result.exitCode)
            put("duration_ms", result.durationMillis)
            put("stdout", trim(result.stdout))
            put("stderr", trim(result.stderr))
            put("message", result.message)
            put("error_code", result.errorCode)
            put("correlation_id", result.correlationId)
            // The route, so the agent can say *how* it did it: which provider, which capability, what
            // was skipped on the way, and - when it failed - why. Never invented: empty when unknown.
            put("provider", result.providerId)
            put("target", result.targetId)
            put("capability", result.capability)
            put("fallback", result.fallback.ifBlank { outcome?.plan?.steps?.firstOrNull()?.fallbackReason.orEmpty() })
            put("failure_reason", result.failureReason)
            outcome?.let { goal ->
                put("recipe", goal.plan.recipeId)
                put("plan", JSONArray(goal.plan.steps.map { step -> step.description }))
                put(
                    "routes",
                    JSONArray(
                        goal.attempts.map { attempt ->
                            JSONObject()
                                .put("candidate", attempt.candidateId)
                                .put("stage", attempt.stage?.name)
                                .put("reason", attempt.reason)
                        },
                    ),
                )
            }
            put("verified", verification?.verified ?: result.verified)
            put("verification", verification?.detail ?: result.message)
            put("summary", summaryOf(result) + routeOf(result))
            OutcomeVerification.apply(
                this,
                verification ?: OutcomeVerification.unverified("the command ran; nothing independently confirmed its effect"),
            )
        }

    /** The route as a sentence tail, so a summary reads "… on the other phone (via peer-adb / bin:pm)". */
    private fun routeOf(result: ExecutionResult): String {
        if (result.providerId.isBlank()) return ""
        val capability = result.capability.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()
        val skipped = result.fallback.takeIf(String::isNotBlank)?.let { " - $it" }.orEmpty()
        return " (via ${result.providerId}$capability)$skipped"
    }

    /** Every route a goal tried, when more than one was needed - the fallback chain, in order. */
    private fun routeTrail(outcome: GoalOutcome): String =
        outcome.attempts
            .takeIf { attempts -> attempts.size > 1 }
            ?.joinToString(prefix = "; routes tried: ", separator = " | ") { attempt ->
                "${attempt.candidateId}${attempt.stage?.let { stage -> " ($stage)" }.orEmpty()}: ${attempt.reason}"
            }
            .orEmpty()

    /**
     * Runs the caller's follow-up command and reports what it proved.
     *
     * Verification here means exactly one thing: a second command ran on the phone and exited zero.
     * What that proves is the caller's statement (`verify_command` is theirs), so the record quotes
     * the check's own outcome rather than claiming the effect.
     */
    private suspend fun verify(
        serial: String,
        verifyCommand: String,
        result: ExecutionResult,
    ): OutcomeVerification {
        val bridge = bridge()
        if (!result.ok) {
            return OutcomeVerification.unverified("the operation itself did not succeed (${result.stage}), so no follow-up ran")
        }
        val target = bridge.device(serial)?.target() ?: ExecutionTarget(id = serial, transport = ExecutionTransport.PEER_ADB)
        val followUp =
            bridge.execute(
                ExecutionRequest(
                    operation = ExecutionOperation.SHELL,
                    target = target,
                    invocation = ExecutionInvocation(verifyCommand),
                    effect = ExecutionEffect(mutatesTarget = false, risk = PermissionRisk.LOW),
                    policy =
                        ExecutionPolicy(
                            reason = "verify the previous operation on $serial",
                            timeoutMillis = 30_000,
                            preAuthorized = true,
                        ),
                ),
            )
        val firstLine = followUp.stdout.lineSequence().firstOrNull()?.take(120).orEmpty()
        return if (followUp.exitCode == 0 && followUp.stage == ExecutionStage.SUCCEEDED) {
            OutcomeVerification.passed("the follow-up check exited 0" + if (firstLine.isBlank()) "" else ": $firstLine")
        } else {
            OutcomeVerification.unverified(
                "the follow-up check did not pass (${followUp.stage}, exit ${followUp.exitCode}): ${followUp.message.take(160)}",
            )
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * The session half of the peer policy.
     *
     * Execution is governed inside the bridge, but pairing/connecting/disconnecting are decisions the
     * agent can ask for directly - so they go through the same center and the same policy, and a
     * refusal is quoted rather than worked around.
     */
    private fun refusal(
        operation: String,
        target: String?,
    ): String? {
        val decision =
            permissionCenter.decide(
                PeerDevicePolicy.sessionRequest(
                    operation = operation,
                    source = PermissionSource.AGENT,
                    target = target,
                    emergencyStop = store.stopRequested(),
                    readOnly = store.readOnlyMode(),
                    preAuthorized = true,
                ),
            )
        return if (decision.isDenied) decision.reason else null
    }

    private fun parseCall(params: JSONObject): PeerOperationCall {
        val operationName = params.optString("operation").trim()
        val operation =
            ExecutionOperation.entries.firstOrNull { it.name.equals(operationName, ignoreCase = true) }
                ?: throw DeviceFileAgent.DeviceAgentError(
                    "operation must be one of ${ExecutionOperation.entries.joinToString { it.name }}",
                )
        val command = params.optString("command")
        val files = readFiles(params)
        val needsCommand =
            when (operation) {
                ExecutionOperation.PUSH, ExecutionOperation.PULL, ExecutionOperation.INSTALL -> false
                else -> true
            }
        if (needsCommand && command.isBlank()) {
            throw DeviceFileAgent.DeviceAgentError("command is required for ${operation.name}")
        }
        if (!needsCommand && files.isEmpty()) {
            throw DeviceFileAgent.DeviceAgentError("files is required for ${operation.name} (a list of {local_path, remote_path})")
        }
        val invocation =
            ExecutionInvocation(
                command = command,
                arguments =
                    params.optJSONArray("arguments")
                        ?.let { array -> (0 until array.length()).map { array.optString(it) } }
                        .orEmpty(),
                interpreter = params.optString("interpreter").ifBlank { null },
                files = files,
            )
        return PeerOperationCall(
            operation = operation,
            invocation = invocation,
            effect = declaredEffect(operation, invocation),
            reason = params.optString("reason"),
            timeoutMillis = params.optLong("timeout_seconds", 30L).coerceIn(5L, 1_800L) * 1_000L,
            verifyCommand = params.optString("verify_command"),
        )
    }

    /**
     * What the request claims to do.
     *
     * The claim is read from the command by the same classifier the bridge uses, so an agent cannot
     * arrive here declaring `rm -rf` as harmless - and for the operations whose command *is* the
     * effect (push, pull, install) the claim is stated by the operation.
     */
    private fun declaredEffect(
        operation: ExecutionOperation,
        invocation: ExecutionInvocation,
    ): ExecutionEffect =
        when (operation) {
            ExecutionOperation.PULL, ExecutionOperation.PROBE -> ExecutionEffect(mutatesTarget = false, risk = PermissionRisk.LOW)
            ExecutionOperation.PUSH -> ExecutionEffect(mutatesTarget = true, risk = PermissionRisk.MEDIUM)
            ExecutionOperation.INSTALL -> ExecutionEffect(mutatesTarget = true, destructive = true, risk = PermissionRisk.HIGH)
            ExecutionOperation.EXEC -> verdictEffect(PeerCommandClassifier.classify(invocation.command, invocation.arguments))
            else -> verdictEffect(PeerCommandClassifier.classify(invocation.command))
        }

    private fun verdictEffect(verdict: PeerCommandVerdict): ExecutionEffect =
        ExecutionEffect(
            mutatesTarget = verdict.mutatesTarget,
            destructive = verdict.requiresStrongConfirmation,
            risk = verdict.risk,
        )

    private fun readIntents(params: JSONObject): List<PlanIntent> =
        params.optJSONArray("operations")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val raw = array.optString(index).trim()
                ExecutionOperation.entries
                    .firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?.let { operation ->
                        PlanIntent(operation = operation, transport = ExecutionTransport.PEER_ADB, description = raw)
                    }
            }
        }.orEmpty()

    private fun requireSerial(params: JSONObject): String {
        val serial = params.optString("serial").trim()
        if (serial.isBlank()) {
            throw DeviceFileAgent.DeviceAgentError(
                "serial is required: there is no default phone. Call peer_devices to see the serials of the paired phones.",
            )
        }
        return serial
    }

    private fun readFiles(params: JSONObject): List<ExecutionFile> =
        params.optJSONArray("files")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val file = array.optJSONObject(index) ?: return@mapNotNull null
                ExecutionFile(
                    localPath = file.optString("local_path"),
                    remotePath = file.optString("remote_path"),
                )
            }
        }.orEmpty()

    private fun summaryOf(result: ExecutionResult): String =
        when (result.stage) {
            ExecutionStage.VERIFIED -> "verified on the other phone: ${result.message}"
            ExecutionStage.SUCCEEDED -> "the command ran on the other phone: ${result.message}"
            ExecutionStage.COMMAND_FAILED -> "the command ran on the other phone and failed (exit ${result.exitCode}): ${result.message}"
            ExecutionStage.TRANSPORT_FAILED -> "the connection to the other phone failed: ${result.message}"
            ExecutionStage.REJECTED -> "refused before anything ran: ${result.message}"
        }

    private fun failure(throwable: Throwable): DeviceFileAgent.DeviceAgentError =
        if (throwable is PeerAdbFailure) {
            val error = throwable.error
            DeviceFileAgent.DeviceAgentError("${error.code}: ${error.detail} - ${error.nextStep}")
        } else {
            DeviceFileAgent.DeviceAgentError(
                PeerAdbErrorClassifier.classify(throwable.message.orEmpty()).let { "${it.code}: ${it.detail}" },
            )
        }

    private fun capabilitySummary(report: CapabilityReport): String {
        val available = report.all.count { it.status == CapabilityStatus.AVAILABLE }
        val missing = report.all.count { it.status == CapabilityStatus.MISSING }
        return "$available capability reports available, $missing missing"
    }

    private fun trim(text: String): String = if (text.length > MAX_OUTPUT) text.takeLast(MAX_OUTPUT) else text

    private companion object {
        const val MAX_OUTPUT = 8_000
    }
}

/** One device as the agent sees it: identity first, addresses second, capabilities as counts. */
internal fun PeerDevice.toJson(): JSONObject =
    JSONObject()
        .put("serial", serial)
        .put("label", label)
        .put("state", state.name)
        .put("ready", state.readyForExecution)
        .put("host", host)
        .put("port", port)
        .put("model", model)
        .put("manufacturer", manufacturer)
        .put("android", androidVersion)
        .put("sdk", sdk)
        .put("abi", abi)
        .put("last_seen_millis", lastSeenMillis)
        .put("capabilities", capabilities.size)

/** The capability report as a name → status map, so the agent can plan against facts. */
internal fun CapabilityReport.toJson(): JSONObject =
    JSONObject().apply {
        all.forEach { capability ->
            put(capability.name, capability.status.name.lowercase())
        }
    }
