package com.mushrea.code.device.permission

import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionActor
import com.mushrea.code.core.permission.PermissionDecision
import com.mushrea.code.device.DeviceActionFirewall
import com.mushrea.code.device.tool.DeviceToolCatalog

/**
 * The decision the Device Agent asks before it runs anything.
 *
 * It composes what used to be scattered checks - the tool table, the user's stored overrides, the
 * Read-Only switch, and the sensitive-tap escalation - into one answer with a reason. The bridge
 * calls it once and then only executes, confirms, or refuses; it no longer decides on its own.
 *
 * Order matters and is deliberate:
 *
 * 1. an id that is not a tool is **denied** (the old firewall fell through to AUTO for unknown ids,
 *    a fail-open hole that is now closed at the decision layer);
 * 2. Read-Only mode denies anything outside the reader list - the switch is a policy, so it wins
 *    over an override that would otherwise allow the action;
 * 3. otherwise the level comes from the tool table, with the user's stored override applied;
 * 4. a tap is re-classified against the element it targets, so "Pay now" is never an auto tap.
 */
class ToolPermissionPolicy(
    private val overrides: Map<String, ConfirmationLevel> = emptyMap(),
    private val readOnly: Boolean = false,
) {
    /** The firewall's classification, reused so the level rules stay in one place. */
    private val firewall = DeviceActionFirewall(overrides)

    /**
     * Decides one command.
     *
     * @param action the action id the bridge received.
     * @param tapLabel the visible label of the element a tap targets, when the action is a tap.
     */
    fun decide(
        action: String,
        tapLabel: String? = null,
        actor: PermissionActor = PermissionActor.AGENT,
    ): PermissionDecision {
        val tool =
            DeviceToolCatalog.tool(action)
                ?: return PermissionDecision.Deny("unknown tool: \"$action\" is not in the tool catalog")

        // The emergency stop is the one action that must stay reachable while everything else is
        // blocked: it is what the user presses to end a runaway task.
        val isStop = tool.id == DeviceActionFirewall.ACTION_STOP
        if (readOnly && !isStop && !DeviceActionFirewall.isAllowedInReadOnly(tool.id)) {
            return PermissionDecision.Deny(
                "Read-Only mode is on: \"${tool.id}\" can change state and was not run. " +
                    "Turn the read-only switch off in the Device Agent screen if you really want it.",
            )
        }

        val level =
            if (tool.id == DeviceActionFirewall.ACTION_TAP) {
                firewall.levelForTap(tapLabel)
            } else {
                firewall.levelFor(tool.id)
            }

        val who = if (actor == PermissionActor.AGENT) "the agent" else actor.name.lowercase()
        return when (level) {
            ConfirmationLevel.AUTO ->
                PermissionDecision.Allow("${tool.id} is an automatic tool for $who (risk ${tool.risk})")

            ConfirmationLevel.CONFIRM,
            ConfirmationLevel.STRONG_CONFIRM,
            -> {
                val why =
                    if (tool.id == DeviceActionFirewall.ACTION_TAP && level == ConfirmationLevel.CONFIRM) {
                        "the tapped control looks sensitive"
                    } else {
                        "risk ${tool.risk}"
                    }
                PermissionDecision.Confirm(level, "$who asked for ${tool.id}: $why")
            }
        }
    }
}
