package com.mushrea.code.runtime.agent

import com.mushrea.code.core.agent.AgentAuthState
import com.mushrea.code.core.runtime.RuntimeHealth
import com.mushrea.code.core.runtime.RuntimeLifecycle
import com.mushrea.code.runtime.LocalAgent
import com.mushrea.code.runtime.local.AntigravityAuthCoordinator
import com.mushrea.code.runtime.local.AntigravityControllerState
import com.mushrea.code.runtime.local.AntigravityInstallStatus
import com.mushrea.code.runtime.local.ClaudeAuthCoordinator
import com.mushrea.code.runtime.local.ClaudeCodeUiState
import com.mushrea.code.runtime.local.ClaudeInstallStatus
import com.mushrea.code.runtime.local.CodexInstallStatus
import com.mushrea.code.runtime.local.CodexUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the agent aggregation layer.
 *
 * They assert the questions the screens actually ask - "is this agent ready", "is a sign-in flow in
 * progress", "which agents can be updated" - rather than that the manager merely forwards a flow.
 */
class AgentManagerTest {
    private class FakeSource(
        override val agent: LocalAgent,
        override val capabilities: AgentCapabilities = AgentCapabilities(),
        lifecycle: RuntimeLifecycle = RuntimeLifecycle.Unknown,
        auth: AgentAuthState = AgentAuthState.Unknown,
        version: String? = null,
        error: String? = null,
    ) : AgentStatusSource {
        val lifecycleFlow = MutableStateFlow(lifecycle)
        val authFlow = MutableStateFlow(auth)
        val versionFlow = MutableStateFlow(version)
        val errorFlow = MutableStateFlow(error)
        var refreshes = 0

        override val lifecycle: Flow<RuntimeLifecycle> = lifecycleFlow
        override val auth: Flow<AgentAuthState> = authFlow
        override val health: Flow<RuntimeHealth> = MutableStateFlow(RuntimeHealth.UNKNOWN)
        override val version: Flow<String?> = versionFlow
        override val error: Flow<String?> = errorFlow

        override suspend fun refresh() {
            refreshes++
        }
    }

    private fun manager(vararg sources: AgentStatusSource) = AgentManager(sources.toList(), TestScope())

    // ---- discovery and readiness ---------------------------------------------------------------

    @Test
    fun `it knows exactly the agents it was given`() {
        val manager = manager(FakeSource(LocalAgent.CLAUDE_CODE), FakeSource(LocalAgent.CODEX))

        assertEquals(setOf(LocalAgent.CLAUDE_CODE, LocalAgent.CODEX), manager.agents)
        assertTrue(manager.knows(LocalAgent.CLAUDE_CODE))
        assertFalse(manager.knows(LocalAgent.OPEN_CODE))
    }

    @Test
    fun `an agent with no source is unknown rather than absent or ready`() {
        val manager = manager(FakeSource(LocalAgent.CLAUDE_CODE))

        val snapshot = manager.snapshot(LocalAgent.ANTIGRAVITY)

        assertEquals(LocalAgent.ANTIGRAVITY, snapshot.agent)
        assertEquals(RuntimeLifecycle.Unknown, snapshot.lifecycle)
        assertFalse(snapshot.ready)
        assertFalse(manager.isReady(LocalAgent.ANTIGRAVITY))
    }

    @Test
    fun `an installed agent that needs no sign-in is ready`() {
        val manager =
            manager(
                FakeSource(
                    LocalAgent.OPEN_CODE,
                    capabilities = AgentCapabilities(install = true, serverLifecycle = true, signIn = false),
                    lifecycle = RuntimeLifecycle.Installed,
                ),
            )

        assertTrue(manager.snapshot(LocalAgent.OPEN_CODE).ready)
    }

    @Test
    fun `an installed agent that needs a sign-in is not ready until it is signed in`() {
        val source =
            FakeSource(
                LocalAgent.CODEX,
                capabilities = AgentCapabilities(install = true, signIn = true),
                lifecycle = RuntimeLifecycle.Installed,
                auth = AgentAuthState.SignedOut,
            )
        val manager = manager(source)

        assertFalse(manager.snapshot(LocalAgent.CODEX).ready)

        source.authFlow.value = AgentAuthState.SignedIn("me@example.test")

        assertTrue(manager.snapshot(LocalAgent.CODEX).ready)
    }

    @Test
    fun `an agent that is still installing is busy and never ready`() {
        val manager =
            manager(
                FakeSource(
                    LocalAgent.CLAUDE_CODE,
                    capabilities = AgentCapabilities(install = true, signIn = true),
                    lifecycle = RuntimeLifecycle.Installing("Unpacking"),
                    auth = AgentAuthState.SignedIn("me@example.test"),
                ),
            )

        val snapshot = manager.snapshot(LocalAgent.CLAUDE_CODE)

        assertTrue(snapshot.busy)
        assertFalse(snapshot.ready)
        assertFalse(snapshot.usable)
    }

    @Test
    fun `only a running server counts as usable`() {
        val running =
            manager(
                FakeSource(
                    LocalAgent.OPEN_CODE,
                    capabilities = AgentCapabilities(serverLifecycle = true),
                    lifecycle = RuntimeLifecycle.Running("1.18.5"),
                ),
            )
        val stopped =
            manager(
                FakeSource(
                    LocalAgent.OPEN_CODE,
                    capabilities = AgentCapabilities(serverLifecycle = true),
                    lifecycle = RuntimeLifecycle.Stopped,
                ),
            )

        assertTrue(running.snapshot(LocalAgent.OPEN_CODE).usable)
        assertTrue(stopped.snapshot(LocalAgent.OPEN_CODE).ready)
        assertFalse(stopped.snapshot(LocalAgent.OPEN_CODE).usable)
    }

    @Test
    fun `a failed agent reports its reason without pretending to be installed`() {
        val manager =
            manager(
                FakeSource(
                    LocalAgent.ANTIGRAVITY,
                    lifecycle = RuntimeLifecycle.Failed("download failed"),
                    error = "download failed",
                ),
            )

        val snapshot = manager.snapshot(LocalAgent.ANTIGRAVITY)

        assertEquals("download failed", snapshot.error)
        assertFalse(snapshot.ready)
        assertFalse(snapshot.busy)
    }

    // ---- capabilities --------------------------------------------------------------------------

    @Test
    fun `capabilities come from the source, so a screen only offers what the agent implements`() {
        val manager =
            manager(
                FakeSource(
                    LocalAgent.CODEX,
                    capabilities = AgentCapabilities(install = true, signIn = true, update = false, mcp = true),
                ),
            )

        val capabilities = manager.capabilities(LocalAgent.CODEX)

        assertTrue(capabilities.install)
        assertTrue(capabilities.signIn)
        assertTrue(capabilities.mcp)
        assertFalse(capabilities.update)
        assertFalse(capabilities.hasPermissionModes)
    }

    @Test
    fun `an agent without a source has no capabilities rather than another agent's`() {
        val manager = manager(FakeSource(LocalAgent.CLAUDE_CODE, capabilities = AgentCapabilities(install = true)))

        assertEquals(AgentCapabilities(), manager.capabilities(LocalAgent.CODEX))
    }

    // ---- aggregation and refresh ----------------------------------------------------------------

    @Test
    fun `the combined flow keeps every agent's own facts apart`() = runTest {
        val claude =
            FakeSource(
                LocalAgent.CLAUDE_CODE,
                lifecycle = RuntimeLifecycle.Installed,
                auth = AgentAuthState.SignedIn("claude@example.test"),
                version = "1.2.3",
            )
        val codex = FakeSource(LocalAgent.CODEX, lifecycle = RuntimeLifecycle.Installing("fetching"), auth = AgentAuthState.SignedOut)
        val manager = manager(claude, codex)

        val snapshots = manager.snapshots.first()

        assertEquals(listOf(LocalAgent.CLAUDE_CODE, LocalAgent.CODEX), snapshots.map { it.agent })
        assertEquals("1.2.3", snapshots[0].version)
        assertEquals(AgentAuthState.SignedIn("claude@example.test"), snapshots[0].auth)
        assertTrue(snapshots[0].ready)
        assertEquals(RuntimeLifecycle.Installing("fetching"), snapshots[1].lifecycle)
        assertFalse(snapshots[1].ready)
    }

    @Test
    fun `refreshAll asks every source, once each`() = runTest {
        val claude = FakeSource(LocalAgent.CLAUDE_CODE)
        val codex = FakeSource(LocalAgent.CODEX)
        val manager = manager(claude, codex)

        manager.refreshAll()

        assertEquals(1, claude.refreshes)
        assertEquals(1, codex.refreshes)
    }

    // ---- the translation from each agent's own models ------------------------------------------

    @Test
    fun `claude's auth states map onto the shared vocabulary without inventing steps`() {
        assertEquals(AgentAuthState.SignedOut, AgentAuthMapper.fromClaude(ClaudeAuthCoordinator.State.Idle))
        assertEquals(AgentAuthState.Starting, AgentAuthMapper.fromClaude(ClaudeAuthCoordinator.State.Starting))
        assertEquals(
            AgentAuthState.AwaitingBrowser(url = "https://example.test/auth", transcript = "visit"),
            AgentAuthMapper.fromClaude(
                ClaudeAuthCoordinator.State.AwaitingBrowser("https://example.test/auth", "visit"),
            ),
        )
        assertEquals(AgentAuthState.Verifying, AgentAuthMapper.fromClaude(ClaudeAuthCoordinator.State.Verifying))
        assertEquals(
            AgentAuthState.SignedIn("me@example.test"),
            AgentAuthMapper.fromClaude(ClaudeAuthCoordinator.State.SignedIn("me@example.test")),
        )
        assertEquals(
            AgentAuthState.Failed("denied", "trace"),
            AgentAuthMapper.fromClaude(ClaudeAuthCoordinator.State.Failed("denied", "trace")),
        )
    }

    @Test
    fun `antigravity's SignedIn detail becomes the account, and its failure keeps the transcript`() {
        assertEquals(
            AgentAuthState.SignedIn("Google"),
            AgentAuthMapper.fromAntigravity(AntigravityAuthCoordinator.State.SignedIn("Google")),
        )
        assertEquals(
            AgentAuthState.Failed("nope", "trace"),
            AgentAuthMapper.fromAntigravity(AntigravityAuthCoordinator.State.Failed("nope", "trace")),
        )
        assertEquals(
            AgentAuthState.AwaitingBrowser(url = "u", transcript = "t"),
            AgentAuthMapper.fromAntigravity(AntigravityAuthCoordinator.State.AwaitingBrowser("u", "t")),
        )
    }

    @Test
    fun `codex's boolean sign-in maps to signed in or signed out only`() {
        assertEquals(AgentAuthState.SignedIn(), AgentAuthMapper.fromCodex(true))
        assertEquals(AgentAuthState.SignedOut, AgentAuthMapper.fromCodex(false))
    }

    @Test
    fun `sign-in states are busy exactly while the flow owns the screen`() {
        assertTrue(AgentAuthState.Starting.busy)
        assertTrue(AgentAuthState.Verifying.busy)
        assertTrue(AgentAuthState.AwaitingBrowser("u").busy)
        assertFalse(AgentAuthState.SignedIn().busy)
        assertFalse(AgentAuthState.SignedOut.busy)
        assertTrue(AgentAuthState.SignedIn().signedIn)
        assertFalse(AgentAuthState.Failed("x").signedIn)
        assertTrue(AgentAuthState.AwaitingBrowser("u").needsUserAction)
        assertTrue(AgentAuthState.Failed("x").needsUserAction)
        assertFalse(AgentAuthState.Starting.needsUserAction)
    }

    // ---- the agent-specific ui states feed the shared model -------------------------------------

    @Test
    fun `claude's ui state maps to a lifecycle the manager can aggregate`() {
        assertEquals(
            RuntimeLifecycle.Installing(detail = null),
            com.mushrea.code.runtime.lifecycle.RuntimeLifecycleMapper.fromClaudeCode(
                ClaudeCodeUiState(install = ClaudeInstallStatus.Installing(step = 1)),
            ),
        )
        assertEquals(
            RuntimeLifecycle.Failed("boom"),
            com.mushrea.code.runtime.lifecycle.RuntimeLifecycleMapper.fromClaudeCode(
                ClaudeCodeUiState(install = ClaudeInstallStatus.Failed("boom")),
            ),
        )
    }

    @Test
    fun `antigravity and codex ui states map without inventing running states`() {
        assertEquals(
            RuntimeLifecycle.Installed,
            com.mushrea.code.runtime.lifecycle.RuntimeLifecycleMapper.fromAntigravity(
                AntigravityControllerState(install = AntigravityInstallStatus.Ready("1.0")),
            ),
        )
        assertEquals(
            RuntimeLifecycle.Installed,
            com.mushrea.code.runtime.lifecycle.RuntimeLifecycleMapper.fromCodex(
                CodexUiState(installed = true, signedIn = false),
            ),
        )
        assertEquals(
            RuntimeLifecycle.Running("0.9"),
            com.mushrea.code.runtime.lifecycle.RuntimeLifecycleMapper.fromCodex(
                CodexUiState(installed = true, signedIn = true, version = "0.9"),
            ),
        )
        assertEquals(
            RuntimeLifecycle.Failed("install failed"),
            com.mushrea.code.runtime.lifecycle.RuntimeLifecycleMapper.fromCodex(
                CodexUiState(install = CodexInstallStatus.Failed("install failed")),
            ),
        )
    }
}
