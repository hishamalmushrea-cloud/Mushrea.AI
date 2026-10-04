package com.mushrea.code.core.provisioning

import com.mushrea.code.core.connectivity.ConnectivityReport
import com.mushrea.code.core.connectivity.RouteCandidate
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.peer.PeerTrust

/**
 * How far a remote device has been taken, from "we heard about it" to "we can run things on it".
 *
 * This is deliberately finer than the peer registry's state machine, and it is not a replacement for
 * it: the registry answers "is this channel usable *now*" and this answers "what has been *proven*
 * about this device". Two of the levels cannot be claimed from a socket at all - [IDENTIFIED] needs
 * the device to have answered with its identity, and [EXECUTION_VERIFIED] needs a command to have run
 * through the whole path - which is the difference the platform refuses to blur.
 */
enum class DeviceReadiness {
    DISCOVERED,
    REACHABLE,
    PAIRED,
    CONNECTED,

    /** The device answered and its identity came back: it is *this* phone, not just a socket. */
    IDENTIFIED,

    /** Its capabilities were measured, so a plan can be written for it. */
    CAPABILITIES_VERIFIED,

    /** A command ran end to end through the execution path and came back. */
    EXECUTION_VERIFIED,

    /** Everything above holds and the route survives a reconnect. */
    READY,
    ;

    val ordinalValue: Int get() = ordinal

    fun atLeast(other: DeviceReadiness): Boolean = ordinal >= other.ordinal

    companion object {
        /**
         * The level from what was actually proven.
         *
         * Every input is a measurement, never an intention: [connected] means `adb` reported the
         * device, [identified] means it answered with its identity, [measured] means a probe
         * completed, and [executed] means a command ran through the execution path.
         */
        fun of(
            known: Boolean,
            connected: Boolean = false,
            identified: Boolean = false,
            measured: Boolean = false,
            executed: Boolean = false,
            trusted: Boolean = false,
        ): DeviceReadiness =
            when {
                executed -> DeviceReadiness.EXECUTION_VERIFIED
                measured -> DeviceReadiness.CAPABILITIES_VERIFIED
                identified -> DeviceReadiness.IDENTIFIED
                connected -> DeviceReadiness.CONNECTED
                trusted -> DeviceReadiness.PAIRED
                known -> DeviceReadiness.DISCOVERED
                else -> DeviceReadiness.DISCOVERED
            }
    }
}

/** The work a provisioning step represents. The host decides *how*; the planner decides *whether*. */
enum class ProvisioningStepKind {
    /** Find out how the target can be reached right now (announcements, transferred addresses, memory). */
    DISCOVER,

    /** Exchange or present the credentials that make the target trust this host. */
    PAIR,

    /** Open the channel over the chosen route. */
    CONNECT,

    /** Prove the channel with a real command. */
    VERIFY,

    /** Measure what the target can do. */
    CAPABILITIES,

    /** Ask the user for the one thing the platform cannot do for them (the Android-side switch). */
    ENABLE_REMOTE_ACCESS,

    /** Make the arrangement survive a reboot, a network change and a new port. */
    PERSIST,

    /** Run one command through the *execution* path, not the provisioning path. */
    TEST_EXECUTION,

    /** Write the device into the registry as a known peer. */
    REGISTER,
}

/**
 * One step of a provisioning flow, with the reason it is there.
 *
 * [automated] is the honest half of the design: a step the platform cannot carry out by itself is
 * still a step - it is handed to the user as [instruction] instead of being silently dropped, and the
 * report says `RequiresUserAction` rather than pretending the goal was reached.
 */
data class ProvisioningStep(
    val id: String,
    val kind: ProvisioningStepKind,
    val title: String,
    val detail: String,
    val automated: Boolean = true,
    val instruction: String = "",
    val mutatesTarget: Boolean = false,
    val skippable: Boolean = true,
) {
    val needsUser: Boolean get() = !automated
}

/** What one step produced. Five outcomes, and none of them is "probably worked". */
sealed interface StepOutcome {
    val detail: String

    /** Done, and here is the evidence. */
    data class Completed(
        override val detail: String,
        val evidence: String = "",
    ) : StepOutcome

    /** Not applicable here, and why - a decision, not a failure. */
    data class Skipped(
        override val detail: String,
    ) : StepOutcome

    /** The platform cannot do this part; [instruction] is the one thing the user has to do. */
    data class NeedsUser(
        override val detail: String,
        val instruction: String,
    ) : StepOutcome

    /** The environment does not allow it at all (an API level, an OEM restriction, a missing program). */
    data class Unsupported(
        override val detail: String,
    ) : StepOutcome

    /** It was attempted and it failed, with the reason to act on. */
    data class Failed(
        override val detail: String,
        val errorCode: String = "",
    ) : StepOutcome
}

/** One step's result, as the report prints it. */
data class StepResult(
    val step: ProvisioningStep,
    val outcome: StepOutcome,
) {
    val completed: Boolean get() = outcome is StepOutcome.Completed
}

/** Why the device is being provisioned - it decides which steps are worth attempting. */
enum class ProvisioningPurpose {
    /** Long-lived remote control: persistence matters. */
    REMOTE_CONTROL,

    /** Move files now: `sync` and a writable path matter, persistence does not. */
    FILE_TRANSFER,

    /** Read logs, state and packages: read-only steps only. */
    DIAGNOSTICS,

    /** A one-off check that the link works. */
    TEST,
}

/**
 * The ask: "connect to this device and set it up for remote work".
 *
 * [hints] are routes the caller already knows (a typed address, a stored one, an address another
 * channel handed over); [allowPublicRoutes] is an explicit opt-in that a plan refuses otherwise.
 * [pairingCode] is present only when the user has already read the code off the target's screen, which
 * is what turns pairing from a user action into an automated step.
 */
data class ProvisioningRequest(
    val targetId: String,
    val names: Set<String> = emptySet(),
    val hints: List<com.mushrea.code.core.connectivity.Endpoint> = emptyList(),
    val purpose: ProvisioningPurpose = ProvisioningPurpose.REMOTE_CONTROL,
    val persistence: Boolean = true,
    val pairingCode: String? = null,
    val allowPublicRoutes: Boolean = false,
    val maxAttempts: Int = 2,
)

/**
 * Everything the planner is allowed to decide from.
 *
 * All of it is measured (see [DeviceReadiness.of]); nothing here is an intention. The `extra` map is
 * the extension point for facts a future transport cares about (a relay's health, a VPN's state) so
 * the planner can keep growing without changing its signature.
 */
data class ProvisioningFacts(
    val targetId: String,
    val readiness: DeviceReadiness = DeviceReadiness.DISCOVERED,
    val trust: PeerTrust = PeerTrust.UNKNOWN,
    val capabilities: CapabilityReport = CapabilityReport.unknown(),
    val connectivity: ConnectivityReport = ConnectivityReport.unknown(),
    val routes: List<RouteCandidate> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
) {
    val known: Boolean get() = readiness != DeviceReadiness.DISCOVERED || trust != PeerTrust.UNKNOWN

    val reachable: Boolean get() = readiness.atLeast(DeviceReadiness.CONNECTED)

    fun fact(name: String): String = extra[name].orEmpty()
}

/** A plan is a list of steps, ordered, each justified. */
data class ProvisioningPlan(
    val steps: List<ProvisioningStep>,
    val note: String = "",
) {
    val needsUser: Boolean get() = steps.any(ProvisioningStep::needsUser)

    fun stepsOf(kind: ProvisioningStepKind): List<ProvisioningStep> = steps.filter { it.kind == kind }

    fun summary(): String = if (steps.isEmpty()) "nothing to do" else steps.joinToString(" then ") { it.id }
}

/** How the whole flow ended. */
enum class ProvisioningStatus {
    /** Every step that mattered completed, and execution is proven. */
    PROVISIONED,

    /** The flow stopped on a step only the user can take; everything before it is done. */
    NEEDS_USER,

    /** Some steps completed and one failed; the channel may still be usable - the report says how far. */
    PARTIAL,

    /** Nothing could be done (no route, refused, unsupported). */
    FAILED,
    ;

    val succeeded: Boolean get() = this == PROVISIONED || this == NEEDS_USER
}

/**
 * The outcome of a provisioning attempt, in the shape the agent prints and the user reads.
 *
 * The report never says "connected" on the strength of a socket: [readiness] is computed from what was
 * proven, [routes] says what was tried and why, and every step carries its own outcome so a partial
 * result is explained line by line.
 */
data class ProvisioningReport(
    val targetId: String,
    val status: ProvisioningStatus,
    val readiness: DeviceReadiness,
    val steps: List<StepResult> = emptyList(),
    val routes: List<RouteCandidate> = emptyList(),
    val capabilities: CapabilityReport = CapabilityReport.unknown(),
    val attempts: Int = 1,
    val summary: String = "",
) {
    val needsUser: StepResult? get() = steps.firstOrNull { it.outcome is StepOutcome.NeedsUser }

    val failure: StepResult? get() = steps.firstOrNull { it.outcome is StepOutcome.Failed }

    /** What to tell the user, in one sentence: the instruction, the failure, or the route used. */
    fun headline(): String =
        when {
            needsUser != null -> (needsUser?.outcome as StepOutcome.NeedsUser).instruction
            failure != null -> (failure?.outcome as StepOutcome.Failed).detail
            else -> summary.ifBlank { routes.firstOrNull()?.let { "reached over ${it.transportId}" }.orEmpty() }
        }
}
