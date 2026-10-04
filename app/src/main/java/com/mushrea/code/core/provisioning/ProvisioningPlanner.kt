package com.mushrea.code.core.provisioning

import com.mushrea.code.core.connectivity.NetworkScope
import com.mushrea.code.core.connectivity.RouteCandidate
import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.core.peer.PeerTrust

/**
 * Turns "connect to this device and set it up for remote work" into an ordered list of steps.
 *
 * The planner is not an AI and does not know any device: it reads the facts (what was proven, what the
 * device reported it can do, what the network looks like) and writes down the steps that follow from
 * them. That is what keeps the flow general - a device that needs no pairing, a transport that needs
 * a private network, a phone whose settings cannot be written by anyone but its owner are all just
 * different facts, not different code paths.
 *
 * Three rules are load-bearing:
 *
 *  1. **Nothing is assumed about Android.** A step that needs a switch only the device's owner can
 *     flip is planned as [ProvisioningStep.automated] = false with the single instruction that flips
 *     it - never skipped silently, never faked.
 *  2. **A public address is not a route.** A candidate on [NetworkScope.PUBLIC_INTERNET] is only
 *     planned when the caller explicitly allowed it; otherwise the plan says the private network is
 *     what has to come up first.
 *  3. **Persisting is a capability, not a promise.** The steps that make an arrangement survive a
 *     reboot are only planned when the device reported the programs they need.
 */
class ProvisioningPlanner(
    /** Steps whose whole point is longevity; a caller that wants one command can turn them off. */
    private val persistenceStepsEnabled: Boolean = true,
) {
    fun plan(
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): ProvisioningPlan {
        val steps = mutableListOf<ProvisioningStep>()
        val routes = usableRoutes(request, facts)

        steps += discoverStep(facts, routes)
        pairStep(request, facts)?.let(steps::add)
        steps += connectStep(routes)
        steps += verifyStep(routes)
        capabilityStep(facts)?.let(steps::add)
        remoteAccessStep(facts, routes)?.let(steps::add)
        if (persistenceStepsEnabled && request.persistence && request.purpose != ProvisioningPurpose.TEST) {
            // Nothing that can be persisted is not a user action and not a failure: it is a fact the
            // report states, which is why this step is automated and the host answers it.
            steps += persistSteps(facts).ifEmpty { listOf(persistenceImpossibleStep()) }
        }
        executionStep(request, facts)?.let(steps::add)
        steps += registerStep()
        return ProvisioningPlan(steps, note = planNote(request, facts, routes))
    }

    /**
     * The routes this plan is allowed to use, best first.
     *
     * A route on a public address is dropped unless the caller opted in, and the drop is recorded in
     * the plan's note rather than being invisible: "there was a way to reach it, and we refused it on
     * purpose" is a different sentence from "there was no way".
     */
    private fun usableRoutes(
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): List<RouteCandidate> =
        facts.routes.filter { candidate ->
            request.allowPublicRoutes || candidate.scope != NetworkScope.PUBLIC_INTERNET
        }

    private fun planNote(
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
        routes: List<RouteCandidate>,
    ): String {
        val refused = facts.routes.count { it.scope == NetworkScope.PUBLIC_INTERNET }
        val refusedNote =
            if (refused > 0 && !request.allowPublicRoutes) {
                " ($refused public route(s) not used: adb is not exposed to the open internet by default)"
            } else {
                ""
            }
        return when {
            routes.isEmpty() -> "no usable route to ${request.targetId}$refusedNote"
            else -> "${routes.size} route(s) available, best: ${routes.first().endpoint}$refusedNote"
        }
    }

    private fun discoverStep(
        facts: ProvisioningFacts,
        routes: List<RouteCandidate>,
    ): ProvisioningStep =
        ProvisioningStep(
            id = "discover",
            kind = ProvisioningStepKind.DISCOVER,
            title = "Find out how the device can be reached",
            detail =
                if (routes.isEmpty()) {
                    "no announcement, remembered address or handed-over address reaches ${facts.targetId} yet"
                } else {
                    routes.joinToString("; ") { candidate -> "${candidate.endpoint} via ${candidate.transportId}" }
                },
            mutatesTarget = false,
        )

    /**
     * Pairing, only when there is nothing to pair with yet.
     *
     * A device we already trust is not asked again - that is the whole point of remembering trust - and
     * a code the user already read turns the step from "please scan this" into an automated one.
     */
    private fun pairStep(
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): ProvisioningStep? {
        if (facts.trust.mayReconnect) return null
        if (facts.trust == PeerTrust.REVOKED) {
            return ProvisioningStep(
                id = "pair",
                kind = ProvisioningStepKind.PAIR,
                title = "Pair again",
                detail = "the trust in this device was revoked",
                automated = false,
                instruction = "This device was revoked. Scan the pairing QR from Devices to trust it again.",
            )
        }
        val code = request.pairingCode?.trim().orEmpty()
        return if (code.isNotEmpty()) {
            ProvisioningStep(
                id = "pair",
                kind = ProvisioningStepKind.PAIR,
                title = "Pair with the code the user read",
                detail = "a six-digit pairing code was supplied, so pairing needs no further user action",
                mutatesTarget = true,
            )
        } else {
            ProvisioningStep(
                id = "pair",
                kind = ProvisioningStepKind.PAIR,
                title = "Pair with the device",
                detail = "Android requires a human to accept the key on the target phone the first time",
                automated = false,
                mutatesTarget = true,
                instruction =
                    "On the phone: Settings -> Developer options -> Wireless debugging -> Pair device with pairing " +
                        "code, then send that code (and its address/port) to this tool - or scan the QR from Devices.",
            )
        }
    }

    private fun connectStep(routes: List<RouteCandidate>): ProvisioningStep =
        ProvisioningStep(
            id = "connect",
            kind = ProvisioningStepKind.CONNECT,
            title = "Open the channel",
            detail =
                routes.firstOrNull()?.let { candidate -> "over ${candidate.endpoint} (${candidate.reason})" }
                    ?: "no route yet",
            mutatesTarget = false,
        )

    private fun verifyStep(routes: List<RouteCandidate>): ProvisioningStep =
        ProvisioningStep(
            id = "verify",
            kind = ProvisioningStepKind.VERIFY,
            title = "Prove the channel with a real command",
            detail = "one shell line has to come back before this device counts as connected (${routes.size} route(s) to try)",
            mutatesTarget = false,
        )

    private fun capabilityStep(facts: ProvisioningFacts): ProvisioningStep? =
        if (facts.readiness.atLeast(DeviceReadiness.CAPABILITIES_VERIFIED) && facts.capabilities.names.isNotEmpty()) {
            null
        } else {
            ProvisioningStep(
                id = "capabilities",
                kind = ProvisioningStepKind.CAPABILITIES,
                title = "Measure what the device can do",
                detail = "one probe: programs, applets, services, filesystem, package manager, build facts, exit codes",
                mutatesTarget = false,
            )
        }

    /**
     * The step this architecture refuses to fake: remote access needs a switch on the target.
     *
     * There is no public Android API that turns wireless debugging on for the user (and the mechanisms
     * that exist require the target to run an app that was granted a signature-level permission). So
     * when no route exists at all, the plan asks for exactly that one thing instead of reporting
     * "unsupported".
     */
    private fun remoteAccessStep(
        facts: ProvisioningFacts,
        routes: List<RouteCandidate>,
    ): ProvisioningStep? {
        if (routes.isNotEmpty()) return null
        val wirelessKnown = facts.capabilities.status(CapabilityNames.debugging("wireless_enabled"))
        val instruction =
            when {
                wirelessKnown == CapabilityStatus.MISSING ->
                    "On the target phone: turn on Developer options -> Wireless debugging and keep the screen " +
                        "open while this runs (the platform switches it off when it is left idle)."
                facts.extra["usb_attached"] == "yes" ->
                    "The phone is on USB but no TCP route exists yet. Let this tool bootstrap the TCP listener " +
                        "over USB (it needs your confirmation), or turn Wireless debugging on."
                else ->
                    "On the target phone: turn on Developer options -> Wireless debugging, then pair (QR or code). " +
                        "If the two phones are on different networks, bring up the private network first."
            }
        return ProvisioningStep(
            id = "enable-remote-access",
            kind = ProvisioningStepKind.ENABLE_REMOTE_ACCESS,
            title = "Ask for the one switch the platform cannot flip",
            detail = "remote debugging is a user-facing setting on the target, by design",
            automated = false,
            instruction = instruction,
        )
    }

    /** The step a device with nothing to persist still gets, so the report can say why. */
    private fun persistenceImpossibleStep(): ProvisioningStep =
        ProvisioningStep(
            id = "persist",
            kind = ProvisioningStepKind.PERSIST,
            title = "Keep the arrangement after a reboot",
            detail = "the device did not report the programs the persistence settings need",
            automated = true,
        )

    /**
     * What can be made to survive a reboot or a network change, from what the device reported.
     *
     * Each of these is a real, documented Android setting written over the channel that already works:
     * keeping Wi-Fi awake while charging (`wifi_sleep_policy`), keeping the screen awake while plugged
     * in (`stay_on_while_plugged_in`), and disabling battery-driven dozing of the network. They are
     * offered only when the programs they need exist, they are all marked as changing the target (so
     * they pass the Permission Center), and they are skippable.
     */
    private fun persistSteps(facts: ProvisioningFacts): List<ProvisioningStep> {
        val hasSettings = facts.capabilities.status(CapabilityNames.binary("settings")) == CapabilityStatus.AVAILABLE
        val hasSvc = facts.capabilities.status(CapabilityNames.binary("svc")) == CapabilityStatus.AVAILABLE
        if (!hasSettings && !hasSvc) return emptyList()
        val steps = mutableListOf<ProvisioningStep>()
        if (hasSettings) {
            steps +=
                ProvisioningStep(
                    id = "persist-wifi",
                    kind = ProvisioningStepKind.PERSIST,
                    title = "Keep Wi-Fi awake",
                    detail = "settings put global wifi_sleep_policy 2 so the radio does not nap while remote",
                    mutatesTarget = true,
                )
            steps +=
                ProvisioningStep(
                    id = "persist-awake",
                    kind = ProvisioningStepKind.PERSIST,
                    title = "Keep the phone awake while charging",
                    detail = "settings put global stay_on_while_plugged_in 3 for a device that sits on a charger",
                    mutatesTarget = true,
                )
        }
        if (hasSvc) {
            steps +=
                ProvisioningStep(
                    id = "persist-port",
                    kind = ProvisioningStepKind.PERSIST,
                    title = "Offer a stable TCP port",
                    detail =
                        "when the target's adbd can be asked for tcpip, the randomised wireless-debugging port stops " +
                            "mattering for later sessions",
                    mutatesTarget = true,
                    skippable = true,
                )
        }
        return steps
    }

    /**
     * One command through the *execution* path, because provisioning and execution are different code
     * and only this step proves the second one works.
     */
    private fun executionStep(
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): ProvisioningStep? {
        if (request.purpose == ProvisioningPurpose.TEST && !facts.reachable) return null
        val shellAvailable = facts.capabilities.status(CapabilityNames.SHELL) == CapabilityStatus.AVAILABLE
        if (facts.capabilities.names.isNotEmpty() && !shellAvailable) {
            return ProvisioningStep(
                id = "test-execution",
                kind = ProvisioningStepKind.TEST_EXECUTION,
                title = "Run one command end to end",
                detail = "the device reported no shell, so nothing can be executed on it yet",
                automated = false,
                instruction = "The phone answered the probe but reported no shell; remote execution is not possible on it.",
            )
        }
        return ProvisioningStep(
            id = "test-execution",
            kind = ProvisioningStepKind.TEST_EXECUTION,
            title = "Run one command end to end",
            detail = "a read-only command through Permission Center, the bridge, the provider and adb",
            mutatesTarget = false,
        )
    }

    private fun registerStep(): ProvisioningStep =
        ProvisioningStep(
            id = "register",
            kind = ProvisioningStepKind.REGISTER,
            title = "Remember the device",
            detail = "identity, trust, transport, routes and readiness, so the next session starts further along",
            mutatesTarget = false,
        )
}
