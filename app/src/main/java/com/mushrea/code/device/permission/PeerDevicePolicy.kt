package com.mushrea.code.device.permission

import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionDomain
import com.mushrea.code.core.permission.PermissionPolicy
import com.mushrea.code.core.permission.PermissionRequest
import com.mushrea.code.core.permission.PermissionResult
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource

/** The peer operations the policy answers for. Anything else is refused (fail-closed). */
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

    val SESSION_OPERATIONS = setOf(DISCOVER, PAIR, CONNECT, DISCONNECT, INFO, EXEC)

    val ALL: Set<String> = SESSION_OPERATIONS
}

/**
 * The peer-device rules, plugged into the Permission Center.
 *
 * This policy is deliberately *not* a list of allowed commands. Commands there are unbounded - the
 * whole point of the peer transport is that the agent's reach equals what adb and the other phone can
 * do - so a command table would either block capabilities nobody thought of or (worse) grow into a
 * second, unmaintained policy. Instead the policy reasons about the four things a decision actually
 * depends on:
 *
 *  * **what kind of operation** it is: a session step (pair/connect) is not an execution step;
 *  * **who asked**: a user tapping *Connect* is not a scheduled run reconnecting at 03:00;
 *  * **what the operation does to the other phone**: a read needs no friction, a write needs a
 *    confirmation, and a high-risk write needs the strong one;
 *  * **whether the user already authorized this call**: a tool call the user confirmed carries
 *    `preAuthorized`, so a state-changing command inside it is not confirmed twice - while a
 *    high-risk one still is, because that is what "strong" means.
 *
 * The command itself never decides *whether* something may run - [PeerCommandClassifier] reads it and
 * can only raise the friction (by raising the risk the request carries), and the center's read-only
 * and emergency-stop rules outrank everything here.
 */
class PeerDevicePolicy : PermissionPolicy {
    override val id: String = ID

    override val domains: Set<PermissionDomain> = setOf(PermissionDomain.PEER_DEVICE)

    override fun evaluate(request: PermissionRequest): PermissionResult? {
        if (request.operation !in PeerOperations.ALL) return null
        val level = levelFor(request)
        return PermissionResult(level = level, reason = reasonFor(request, level), decidedBy = id)
    }

    private fun levelFor(request: PermissionRequest): ConfirmationLevel =
        when (request.operation) {
            PeerOperations.DISCOVER, PeerOperations.INFO -> ConfirmationLevel.AUTO
            PeerOperations.PAIR, PeerOperations.CONNECT, PeerOperations.DISCONNECT ->
                if (request.source == PermissionSource.USER || request.preAuthorized) {
                    ConfirmationLevel.AUTO
                } else {
                    ConfirmationLevel.CONFIRM
                }
            PeerOperations.EXEC ->
                when {
                    !request.mutatesState -> ConfirmationLevel.AUTO
                    request.risk == PermissionRisk.HIGH -> ConfirmationLevel.STRONG_CONFIRM
                    request.preAuthorized -> ConfirmationLevel.AUTO
                    else -> ConfirmationLevel.CONFIRM
                }
            else -> ConfirmationLevel.DENY
        }

    private fun reasonFor(
        request: PermissionRequest,
        level: ConfirmationLevel,
    ): String {
        val subject = request.target?.takeIf(String::isNotBlank)?.let { " on $it" }.orEmpty()
        return when (level) {
            ConfirmationLevel.AUTO ->
                if (!request.mutatesState) {
                    "${request.operation}$subject only reads the other phone"
                } else {
                    "${request.operation}$subject is covered by the authorization the user already gave"
                }
            ConfirmationLevel.CONFIRM ->
                "${request.operation}$subject changes the other phone and is confirmed once"
            ConfirmationLevel.STRONG_CONFIRM ->
                "${request.operation}$subject can lose data or take over the other phone: it needs an explicit confirmation"
            ConfirmationLevel.DENY ->
                "${request.operation} is not an operation this policy allows"
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
                // Only pairing writes anything: it installs this app's key on the other phone as a
                // trust record. Connecting, disconnecting and reading change nothing on either side,
                // which is why Read-Only blocks *enrolling* a new phone but not using one the user
                // has already paired - otherwise the peer feature would be unusable in the default
                // Read-Only mode while every writing command stayed blocked anyway.
                mutatesState = operation == PeerOperations.PAIR,
                readOnly = readOnly,
                emergencyStop = emergencyStop,
                preAuthorized = preAuthorized,
            )

        /**
         * The request one execution asks, built from what the operation will actually do.
         *
         * [mutatesState] and [risk] come from the classifier (via the bridge), which is why a
         * destructive command cannot arrive here as a harmless one.
         */
        fun execRequest(
            execution: ExecutionRequest,
            readOnly: Boolean,
            emergencyStop: Boolean,
            preAuthorized: Boolean,
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
            )
    }
}
