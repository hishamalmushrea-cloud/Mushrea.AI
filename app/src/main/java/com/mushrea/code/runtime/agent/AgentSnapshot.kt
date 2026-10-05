package com.mushrea.code.runtime.agent

import com.mushrea.code.core.agent.AgentAuthState
import com.mushrea.code.core.runtime.RuntimeHealth
import com.mushrea.code.core.runtime.RuntimeLifecycle
import com.mushrea.code.runtime.LocalAgent

/**
 * What one agent can actually do, read from the app's own wiring rather than guessed from its name.
 *
 * Every field here answers a question a screen would otherwise answer with a `when (agent)` - which
 * agents can be updated, which have permission modes to choose, which one runs a server that must
 * be started and stopped. Keeping it in one value means a new agent cannot silently inherit another
 * agent's assumptions.
 *
 * Only capabilities the code implements today are listed as true; anything else is false and its
 * screen simply does not offer it.
 */
data class AgentCapabilities(
    /** The agent can be installed into the shared Linux sandbox from this app. */
    val install: Boolean = false,
    /** The agent has an update path (`update()`), as opposed to reinstall-only. */
    val update: Boolean = false,
    /** The agent has its own sign-in flow. */
    val signIn: Boolean = false,
    /** Option ids of the permission modes the agent accepts; empty when it has none. */
    val permissionModes: List<String> = emptyList(),
    /** The agent carries per-chat system prompt presets. */
    val systemPrompts: Boolean = false,
    /** The app manages MCP servers for this agent. */
    val mcp: Boolean = false,
    /**
     * The agent runs as a long-lived local server that must be started and stopped (OpenCode), as
     * opposed to a process per turn (Claude Code, Antigravity, Codex).
     */
    val serverLifecycle: Boolean = false,
) {
    val hasPermissionModes: Boolean get() = permissionModes.isNotEmpty()
}

/**
 * Everything the app knows about one agent at one instant.
 *
 * The agent half of `RuntimeSnapshot`: same lifecycle and health vocabulary, plus the sign-in state,
 * the version and the capabilities that only agents have. A screen renders from this object instead
 * of from whichever controller happens to own the agent.
 */
data class AgentSnapshot(
    val agent: LocalAgent,
    val lifecycle: RuntimeLifecycle = RuntimeLifecycle.Unknown,
    val health: RuntimeHealth = RuntimeHealth.UNKNOWN,
    val auth: AgentAuthState = AgentAuthState.Unknown,
    val version: String? = null,
    val error: String? = null,
    val capabilities: AgentCapabilities = AgentCapabilities(),
) {
    /** True while an install/update is in flight, so no second operation may be dispatched. */
    val busy: Boolean get() = lifecycle.busy

    /**
     * Installed and usable: files are in place and, when the agent needs a sign-in, it is signed in.
     *
     * This is the rule the four agent screens each re-derived; it is stated once here.
     */
    val ready: Boolean
        get() {
            val installed =
                when (lifecycle) {
                    is RuntimeLifecycle.Running, RuntimeLifecycle.Installed, RuntimeLifecycle.Stopped -> true
                    else -> false
                }
            if (!installed) return false
            return !capabilities.signIn || auth.signedIn
        }

    /** Whether a session can be opened right now. */
    val usable: Boolean get() = ready && lifecycle.usable
}
