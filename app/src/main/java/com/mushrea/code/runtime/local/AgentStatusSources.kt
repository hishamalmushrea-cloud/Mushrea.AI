package com.mushrea.code.runtime.local

import com.mushrea.code.core.agent.AgentAuthState
import com.mushrea.code.core.runtime.RuntimeHealth
import com.mushrea.code.core.runtime.RuntimeLifecycle
import com.mushrea.code.runtime.LocalAgent
import com.mushrea.code.runtime.RuntimeState
import com.mushrea.code.runtime.agent.AgentAuthMapper
import com.mushrea.code.runtime.agent.AgentCapabilities
import com.mushrea.code.runtime.agent.AgentStatusSource
import com.mushrea.code.runtime.lifecycle.RuntimeLifecycleMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * OpenCode: the agent that runs as a long-lived local server.
 *
 * The four adapters in this file let [com.mushrea.code.runtime.agent.AgentManager] read agents alike.
 * Each one is a thin translation of the controller it wraps: no state is copied, no caching, no
 * polling - the flows are the controller's own, mapped into the shared vocabulary. Capabilities are
 * written from what the code actually implements (see each entry's comment for the file that
 * proves it), so a screen can offer only what exists.
 */
class OpenCodeAgentStatusSource(
    private val target: LocalRuntimeTarget,
) : AgentStatusSource {
    override val agent: LocalAgent = LocalAgent.OPEN_CODE

    override val capabilities =
        AgentCapabilities(
            // LocalRuntimeManager owns install/update/rollback of the shared sandbox and OpenCode binary.
            install = true,
            update = true,
            // Provider sign-in (OAuth/API key) is per provider and lives in provider settings, not here.
            signIn = false,
            permissionModes = emptyList(),
            // OpenCodeSystemPrompt + SystemPromptStore.
            systemPrompts = true,
            // OpenCodeBackend.mcpServers(), surfaced by the MCP settings screen.
            mcp = true,
            // The only agent with a start/stop lifecycle of its own.
            serverLifecycle = true,
        )

    override val lifecycle: Flow<RuntimeLifecycle> = target.state.map(RuntimeLifecycleMapper::fromRuntimeState)

    override val auth: Flow<AgentAuthState> = flowOf(AgentAuthState.Unknown)

    override val health: Flow<RuntimeHealth> = target.state.map(RuntimeLifecycleMapper::healthOf)

    override val version: Flow<String?> =
        target.state.map { state -> (state as? RuntimeState.Connected)?.version }

    override val error: Flow<String?> =
        target.state.map { state -> (state as? RuntimeState.Failed)?.message }

    /**
     * Re-reads the on-disk status without touching the running server: [LocalRuntimeTarget.connect]
     * would restart the event stream and drop an in-flight reply, which is exactly what the catalog
     * refresh avoids.
     */
    override suspend fun refresh() {
        target.refreshLocalState()
    }
}

/** Claude Code: installed into the shared sandbox, one process per turn, browser sign-in. */
class ClaudeAgentStatusSource(
    private val controller: ClaudeCodeController,
) : AgentStatusSource {
    override val agent: LocalAgent = LocalAgent.CLAUDE_CODE

    override val capabilities =
        AgentCapabilities(
            install = true,
            update = true,
            signIn = true,
            permissionModes = ClaudePermissionMode.entries.map { it.name },
            systemPrompts = true,
            mcp = true,
            serverLifecycle = false,
        )

    override val lifecycle: Flow<RuntimeLifecycle> = controller.state.map(RuntimeLifecycleMapper::fromClaudeCode)

    override val auth: Flow<AgentAuthState> = controller.state.map { AgentAuthMapper.fromClaude(it.auth) }

    override val version: Flow<String?> = controller.state.map { it.version }

    override val error: Flow<String?> = controller.state.map { (it.install as? ClaudeInstallStatus.Failed)?.message }

    override suspend fun refresh() = controller.refresh()
}

/** Antigravity: Debian rootfs + `agy` in a PTY, Google sign-in, permission modes. */
class AntigravityAgentStatusSource(
    private val controller: AntigravityController,
) : AgentStatusSource {
    override val agent: LocalAgent = LocalAgent.ANTIGRAVITY

    override val capabilities =
        AgentCapabilities(
            install = true,
            update = true,
            signIn = true,
            permissionModes = AntigravityPermissionMode.entries.map { it.name },
            // Antigravity has no per-chat system prompt presets; Claude and OpenCode do.
            systemPrompts = false,
            mcp = true,
            serverLifecycle = false,
        )

    override val lifecycle: Flow<RuntimeLifecycle> = controller.state.map(RuntimeLifecycleMapper::fromAntigravity)

    override val auth: Flow<AgentAuthState> = controller.state.map { AgentAuthMapper.fromAntigravity(it.auth) }

    override val version: Flow<String?> = controller.state.map { it.version }

    override val error: Flow<String?> = controller.state.map { it.error }

    override suspend fun refresh() = controller.refresh()
}

/** Codex: install + ChatGPT/API-key sign-in, no update path and no permission modes. */
class CodexAgentStatusSource(
    private val controller: CodexController,
) : AgentStatusSource {
    override val agent: LocalAgent = LocalAgent.CODEX

    override val capabilities =
        AgentCapabilities(
            install = true,
            // CodexController has no update(); reinstalling is the only path today.
            update = false,
            signIn = true,
            permissionModes = emptyList(),
            systemPrompts = false,
            mcp = true,
            serverLifecycle = false,
        )

    override val lifecycle: Flow<RuntimeLifecycle> = controller.state.map(RuntimeLifecycleMapper::fromCodex)

    override val auth: Flow<AgentAuthState> = controller.state.map { AgentAuthMapper.fromCodex(it.signedIn) }

    override val version: Flow<String?> = controller.state.map { it.version }

    override val error: Flow<String?> = controller.state.map { (it.install as? CodexInstallStatus.Failed)?.message }

    override suspend fun refresh() = controller.refresh()
}
