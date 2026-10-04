package com.mushrea.code.device.permission

import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionActor
import com.mushrea.code.core.permission.PermissionDecision
import com.mushrea.code.core.permission.PermissionDomain
import com.mushrea.code.core.permission.PermissionPolicy
import com.mushrea.code.core.permission.PermissionRequest
import com.mushrea.code.core.permission.PermissionResult
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource
import com.mushrea.code.device.DeviceActionFirewall
import com.mushrea.code.device.tool.DeviceToolCatalog
import com.mushrea.code.device.tool.ToolFamily

/**
 * The device catalog's rules, plugged into the Permission Center (P2).
 *
 * It does **not** replace [ToolPermissionPolicy]: that class still owns the device's own reasoning
 * (the tool table, the stored overrides, the Read-Only reader list and the sensitive-tap escalation)
 * and is called here unchanged, so every barrier the Device Agent had keeps working. This adapter
 * only does the two things the center needs:
 *
 *  * it answers with the center's vocabulary ([PermissionResult]) instead of the bridge's decision
 *    type, and names itself in `decidedBy` so the audit shows which policy decided;
 *  * it derives the routing facts the request carries — domain from the tool family, risk from the
 *    catalog — so the caller does not restate them and cannot restate them wrongly: a request whose
 *    domain disagrees with the catalog family is refused rather than evaluated.
 *
 * Returning `null` (unknown tool, wrong domain) is deliberate: the center turns it into a denial, so
 * an action that is not in the catalog can never fall through to AUTO — the fail-open hole the
 * Phase 1 audit found in `DeviceActionFirewall` is closed at the decision layer.
 *
 * @param overrides the user's stored per-tool overrides, read through a provider because the Device
 *   Agent screen edits them while the bridge runs.
 */
class DeviceToolPolicy(
    private val overrides: () -> Map<String, ConfirmationLevel> = { emptyMap() },
) : PermissionPolicy {
    override val id: String = ID

    override val domains: Set<PermissionDomain> =
        setOf(
            PermissionDomain.DEVICE,
            PermissionDomain.SCREEN,
            PermissionDomain.FILES,
            PermissionDomain.NETWORK,
            PermissionDomain.USB,
            PermissionDomain.SSH,
            PermissionDomain.REMOTE,
        )

    override fun evaluate(request: PermissionRequest): PermissionResult? {
        val tool = DeviceToolCatalog.tool(request.operation) ?: return null
        if (domainForFamily(tool.family) != request.domain) return null

        val stored = overrides()
        val policy = ToolPermissionPolicy(stored, readOnly = request.readOnly)
        val decision =
            policy.decide(
                action = request.operation,
                tapLabel = request.tapLabel,
                actor = actorFor(request.source),
            )
        val level =
            when (decision) {
                is PermissionDecision.Allow -> ConfirmationLevel.AUTO
                is PermissionDecision.Confirm -> decision.level
                is PermissionDecision.Deny -> ConfirmationLevel.DENY
            }
        val overridden =
            level != tool.confirmation && stored.keys.any { it.equals(tool.id, ignoreCase = true) }
        return PermissionResult(
            level = level,
            reason = decision.reason,
            decidedBy = id,
            overridden = overridden,
        )
    }

    companion object {
        const val ID = "device.tools"

        /** The domain a catalog family routes to; the center has one policy per domain. */
        fun domainForFamily(family: ToolFamily): PermissionDomain =
            when (family) {
                ToolFamily.SCREEN -> PermissionDomain.SCREEN
                ToolFamily.FILES -> PermissionDomain.FILES
                ToolFamily.NETWORK -> PermissionDomain.NETWORK
                ToolFamily.USB,
                ToolFamily.SERIAL,
                ToolFamily.HUB,
                ToolFamily.MTP,
                -> PermissionDomain.USB
                ToolFamily.SSH -> PermissionDomain.SSH
                ToolFamily.REMOTE -> PermissionDomain.REMOTE
                // Calls, mirroring, Bluetooth, payloads, Termux, the safety stop, the audit reader,
                // status, context and the peer catalog tools are all "the device itself" and share
                // one domain. Peer *execution* is a second, transport-level decision - the peer
                // policy answers for `PermissionDomain.PEER_DEVICE`, and the peer bridge asks it
                // with the operation's real effect rather than with a tool id.
                else -> PermissionDomain.DEVICE
            }

        /**
         * The domain for [action], or [PermissionDomain.DEVICE] when it is not in the catalog.
         *
         * Unknown actions are routed to the device policy on purpose: the policy then returns null
         * for them and the center refuses them with a reason, instead of the caller needing a
         * special case.
         */
        fun domainForAction(action: String): PermissionDomain =
            DeviceToolCatalog.tool(action)?.let { domainForFamily(it.family) } ?: PermissionDomain.DEVICE

        /** True for the one action that must stay reachable while the emergency stop is set. */
        fun isSafetyOperation(action: String): Boolean = action == DeviceActionFirewall.ACTION_STOP

        /**
         * The request the device bridge builds for one command.
         *
         * The risk and the domain are read from the catalog rather than passed in, so a caller
         * cannot misroute a tool or understate its risk, and an action that is *not* in the catalog
         * still becomes a request (with the highest risk and `mutatesState = true`) — the policy
         * then refuses it, instead of the bridge having to special-case unknown actions.
         */
        fun deviceRequest(
            action: String,
            source: PermissionSource,
            readOnly: Boolean,
            emergencyStop: Boolean,
            tapLabel: String? = null,
            target: String? = null,
        ): PermissionRequest {
            val tool = DeviceToolCatalog.tool(action)
            return PermissionRequest(
                domain = domainForAction(action),
                operation = action,
                source = source,
                target = target,
                risk = tool?.risk ?: PermissionRisk.HIGH,
                mutatesState = tool?.readOnly?.not() ?: true,
                readOnly = readOnly,
                emergencyStop = emergencyStop,
                safetyOperation = isSafetyOperation(action),
                tapLabel = tapLabel,
            )
        }

        private fun actorFor(source: PermissionSource): PermissionActor =
            when (source) {
                PermissionSource.USER -> PermissionActor.USER
                PermissionSource.SYSTEM -> PermissionActor.SYSTEM
                PermissionSource.AGENT,
                PermissionSource.SCHEDULE,
                -> PermissionActor.AGENT
            }
    }
}
