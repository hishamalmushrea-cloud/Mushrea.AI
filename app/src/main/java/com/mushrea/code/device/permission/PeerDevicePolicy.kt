package com.mushrea.code.device.permission

import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.execution.PlanStep
import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionDomain
import com.mushrea.code.core.permission.PermissionPolicy
import com.mushrea.code.core.permission.PermissionRequest
import com.mushrea.code.core.permission.PermissionResult
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource

/**
 * The peer operations the platform names. They are *names for context*, not an allow-list: anything
 * else in the peer namespace is still decided, by its effect (see [PeerDevicePolicy.levelFor]).
 */
object PeerOperations {
    /** Look for devices announcing themselves on the network. Reads only. */
    const val DISCOVER = "peer.discover"

    /** Pair with a device (QR or six-digit code). */
    const val PAIR = "peer.pair"

    /** Open the ADB channel to a device that is already paired. */
    const val CONNECT = "peer.connect"

    /** Close the channel. The pairing stays. */
    const val DISCONNECT = "peer.disconnect"

    /** Read a device's identity and capabilities. Reads only. */
    const val INFO = "peer.info"

    /** Run something on the device: shell, program, script, file transfer or install. */
    const val EXEC = "peer.exec"

    /**
     * Set a device up for remote work: discover a route, pair if needed, connect, verify, measure,
     * persist and prove execution.
     *
     * It is a session operation with a *writing tail*: the settings steps inside it are separate
     * execution requests that carry their own effect, so the permission decision for "provision" is
     * about opening and arranging a channel, and the decision for each change is about the change.
     */
    const val PROVISION = "peer.provision"

    /** Get a known device back after it slept, moved or changed its port. Bounded retries. */
    const val RECONNECT = "peer.reconnect"

    /** Read how a device can be reached right now: the candidate routes and the host's networking. */
    const val ENDPOINTS = "peer.endpoints"

    /** Every namespace this policy answers for. */
    const val PREFIX = "peer."

    val SESSION_OPERATIONS = setOf(DISCOVER, PAIR, CONNECT, DISCONNECT, INFO, EXEC, PROVISION, RECONNECT, ENDPOINTS)

    val ALL: Set<String> = SESSION_OPERATIONS
}

/**
 * The peer-device rules, plugged into the Permission Center.
 *
 * This policy is deliberately *not* a list of allowed commands, and deliberately not a list of
 * allowed operation names either. Commands there are unbounded - the whole point of the peer
 * transport is that the agent's reach equals what adb and the other phone can do - so a command table
 * would either block capabilities nobody thought of or (worse) grow into a second, unmaintained
 * policy. The same argument applies one level up: a future provider (a second transport, a script
 * host, a device type nobody has written yet) will produce operation names this file has never heard
 * of, and refusing those for their *name* would make the policy the ceiling of the platform.
 *
 * So the decision is made on what a request actually is:
 *
 *  * **what it does to the other phone** ([PermissionRequest.mutatesState], [PermissionRequest.destructive],
 *    [PermissionRequest.risk]) - a read needs no friction, a write is confirmed once, and something
 *    that can lose data needs the strong confirmation;
 *  * **who asked** ([PermissionRequest.source]) - a user tapping *Connect* is not a scheduled run
 *    reconnecting at 03:00;
 *  * **whether the user already authorized this call** ([PermissionRequest.preAuthorized]) - a tool
 *    call the user confirmed is not confirmed twice, while a destructive one still is, because that is
 *    what "strong" means;
 *  * **the route** ([PermissionRequest.capability], [PermissionRequest.providerId]) - context the
 *    decision and the audit record carry, never a gate.
 *
 * What is *not* here is just as deliberate: read-only mode, the emergency stop and the sensitive-UI
 * escalation are enforced centrally by the center, so no policy - including this one - can be the
 * weak link. The command itself never decides *whether* something may run either: [PeerCommandClassifier]
 * reads it and can only raise the friction (by raising the risk the request carries).
 */
class PeerDevicePolicy : PermissionPolicy {
    override val id: String = ID

    override val domains: Set<PermissionDomain> = setOf(PermissionDomain.PEER_DEVICE)

    override fun evaluate(request: PermissionRequest): PermissionResult? {
        // Not a peer operation: this policy has no opinion, and the center routes it elsewhere. A peer
        // *name* the policy has never seen is handled - by its effect, not refused for being unknown.
        if (!request.operation.startsWith(PeerOperations.PREFIX)) return null
        val level = levelFor(request)
        return PermissionResult(level = level, reason = reasonFor(request, level), decidedBy = id)
    }

    /**
     * The level, from the effect and the context.
     *
     * The named session steps keep their specific meaning (pairing enrolls this app's key on another
     * phone, connecting only opens a channel); everything else - including an operation a future
     * provider introduces - is decided by what it declares and what it costs.
     */
    private fun levelFor(request: PermissionRequest): ConfirmationLevel =
        when (request.operation) {
            PeerOperations.DISCOVER, PeerOperations.INFO, PeerOperations.ENDPOINTS -> ConfirmationLevel.AUTO
            PeerOperations.PAIR, PeerOperations.CONNECT, PeerOperations.DISCONNECT,
            PeerOperations.PROVISION, PeerOperations.RECONNECT,
            ->
                if (request.source == PermissionSource.USER || request.preAuthorized) {
                    ConfirmationLevel.AUTO
                } else {
                    ConfirmationLevel.CONFIRM
                }
            else -> effectLevel(request)
        }

    /** The general rule: reading is free, writing is confirmed once, damage needs the strong one. */
    private fun effectLevel(request: PermissionRequest): ConfirmationLevel =
        when {
            request.destructive || request.risk == PermissionRisk.HIGH -> ConfirmationLevel.STRONG_CONFIRM
            !request.mutatesState -> ConfirmationLevel.AUTO
            request.source == PermissionSource.USER || request.preAuthorized -> ConfirmationLevel.AUTO
            else -> ConfirmationLevel.CONFIRM
        }

    private fun reasonFor(
        request: PermissionRequest,
        level: ConfirmationLevel,
    ): String {
        val subject = request.target?.takeIf(String::isNotBlank)?.let { " on $it" }.orEmpty()
        val route = request.capability?.takeIf(String::isNotBlank)?.let { " with $it" }.orEmpty()
        return when (level) {
            ConfirmationLevel.AUTO ->
                if (!request.mutatesState) {
                    "${request.operation}$subject$route only reads the other phone"
                } else {
                    "${request.operation}$subject$route is covered by the authorization the user already gave"
                }
            ConfirmationLevel.CONFIRM ->
                "${request.operation}$subject$route changes the other phone and is confirmed once"
            ConfirmationLevel.STRONG_CONFIRM ->
                "${request.operation}$subject$route can lose data or take over the other phone: it needs an explicit confirmation"
            ConfirmationLevel.DENY -> "${request.operation} is not allowed on the other phone"
        }
    }

    companion object {
        const val ID = "peer.device"

        /**
         * The request the bridge's session steps ask (discover, pair, connect, disconnect, info).
         *
         * None of them mutate the other phone, but pairing and connecting are still confirmed when
         * the app acts on its own - a scheduled job must not be able to enroll a new device silently.
         */
        fun sessionRequest(
            operation: String,
            source: PermissionSource,
            target: String? = null,
            emergencyStop: Boolean = false,
            readOnly: Boolean = false,
            preAuthorized: Boolean = false,
        ): PermissionRequest =
            PermissionRequest(
                domain = PermissionDomain.PEER_DEVICE,
                operation = operation,
                source = source,
                target = target,
                risk = PermissionRisk.MEDIUM,
                // Only pairing and provisioning write anything: pairing installs this app's key on the
                // other phone as a trust record, and provisioning may pair *and* write settings on it.
                // Connecting, disconnecting, reading and reconnecting change nothing on either side,
                // which is why Read-Only blocks *enrolling* a new phone but not using one the user has
                // already paired - otherwise the peer feature would be unusable in the default
                // Read-Only mode while every writing command stayed blocked anyway.
                mutatesState = operation == PeerOperations.PAIR || operation == PeerOperations.PROVISION,
                readOnly = readOnly,
                emergencyStop = emergencyStop,
                preAuthorized = preAuthorized,
            )

        /**
         * The request one execution asks, built from what the operation will actually do.
         *
         * [mutatesState], [destructive] and [risk] come from the classifier (via the bridge), which is
         * why a destructive command cannot arrive here as a harmless one; [capability] and [providerId]
         * are the route the decision is about.
         */
        fun execRequest(
            execution: ExecutionRequest,
            readOnly: Boolean,
            emergencyStop: Boolean,
            preAuthorized: Boolean,
            capability: String? = null,
            providerId: String? = null,
        ): PermissionRequest =
            PermissionRequest(
                domain = PermissionDomain.PEER_DEVICE,
                operation = PeerOperations.EXEC,
                source = execution.policy.requestedBy,
                target = execution.target.label.ifBlank { execution.target.id },
                risk = execution.effect.risk,
                mutatesState = execution.effect.mutatesTarget,
                readOnly = readOnly,
                emergencyStop = emergencyStop,
                preAuthorized = preAuthorized,
                destructive = execution.effect.destructive,
                capability = capability,
                providerId = providerId,
            )

        /**
         * The request one planned step asks.
         *
         * Same rules as [execRequest], but the effect, the operation and the capability come from the
         * step the planner resolved - so what policy weighs is what will really run, including for a
         * step that came out of a recipe the policy has never seen.
         */
        fun stepRequest(
            step: PlanStep,
            execution: ExecutionRequest,
            readOnly: Boolean,
            emergencyStop: Boolean,
            preAuthorized: Boolean,
        ): PermissionRequest =
            execRequest(
                execution =
                    execution.copy(
                        operation = step.operation,
                        invocation = step.invocation,
                        effect = step.effect,
                    ),
                readOnly = readOnly,
                emergencyStop = emergencyStop,
                preAuthorized = preAuthorized,
                capability = step.capability,
                providerId = step.providerId,
            )
    }
}
