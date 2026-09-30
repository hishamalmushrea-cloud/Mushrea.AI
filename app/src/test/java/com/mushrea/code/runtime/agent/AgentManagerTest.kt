package com.mushrea.code.runtime.agent

import com.mushrea.code.core.agent.AgentAuthState
import com.mushrea.code.core.runtime.RuntimeHealth
import com.mushrea.code.core.runtime.RuntimeLifecycle
import com.mushrea.code.runtime.LocalAgent
import com.mushrea.code.runtime.lifecycle.RuntimeLifecycleMapper
import com.mushrea.code.runtime.local.AntigravityAuthCoordinator
import com.mushrea.code.runtime.local.AntigravityControllerState
import com.mushrea.code.runtime.local.AntigravityInstallStatus
import com.mushrea.code.runtime.local.ClaudeAuthCoordinator
import com.mushrea.code.runtime.local.ClaudeCodeUiState
import com.mushrea.code.runtime.local.ClaudeInstallStatus
import com.mushrea.code.runtime.local.CodexInstallStatus
import com.mushrea.code.runtime.local.CodexUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
 *
 * One agent's snapshot is read through [AgentManager.snapshotFlow], which the test collects itself,
 * so the assertion cannot race the manager's own scope. The aggregation test drives that scope
 * explicitly, the same way the repository's other flow-backed tests do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

        override val lifecycle: Flow<RuntimeLifecycle> = lifecycleFlow
        override val auth: Flow<AgentAuthState> = authFlow
        override val health: Flow<RuntimeHealth> = MutableStateFlow(RuntimeHealth.UNKNOWN)
        override val version: Flow<String?> = versionFlow
        override val error: Flow<String?> = errorFlow
    }

    /** A scope sharing the test scheduler, so `advanceUntilIdle` runs the manager's own coroutines. */
    private fun TestScope.scoped() = TestScope(StandardTestDispatcher(testScheduler))

    private suspend fun AgentManager.current(agent: LocalAgent): AgentSnapshot = snapshotFlow(agent).first()

    // ---- readiness -----------------------------------------------------------------------------

    @Test
    fun `an agent with no source is unknown rather than absent or ready`() = runTest {
        val manager = AgentManager(listOf(FakeSource(LocalAgent.CLAUDE_CODE)), scoped())

        val snapshot = manager.current(LocalAgent.ANTIGRAVITY)

        assertEquals(LocalAgent.ANTIGRAVITY, snapshot.agent)
        assertEquals(RuntimeLifecycle.Unknown, snapshot.lifecycle)
        assertFalse(snapshot.ready)
        assertFalse(snapshot.usable)
    }

    @Test
    fun `an installed agent that needs no sign-in is ready`() = runTest {
        val manager =
            AgentManager(
                listOf(
                    FakeSource(
                        LocalAgent.OPEN_CODE,
                        capabilities = AgentCapabilities(install = true, serverLifecycle = true, signIn = false),
                        lifecycle = RuntimeLifecycle.Installed,
                    ),
                ),
                scoped(),
            )

        assertTrue(manager.current(LocalAgent.OPEN_CODE).ready)
    }

    @Test
    fun `an installed agent that needs a sign-in is not ready until it is signed in`() = runTest {
        val source =
            FakeSource(
                LocalAgent.CODEX,
                capabilities = AgentCapabilities(install = true, signIn = true),
                lifecycle = RuntimeLifecycle.Installed,
                auth = AgentAuthState.SignedOut,
            )
        val manager = AgentManager(listOf(source), scoped())

        assertFalse(manager.current(LocalAgent.CODEX).ready)

        source.authFlow.value = AgentAuthState.SignedIn("me@example.test")

        assertTrue(manager.current(LocalAgent.CODEX).ready)
    }

    @Test
    fun `an agent that is still installing is busy and never ready`() = runTest {
        val manager =
            AgentManager(
                listOf(
                    FakeSource(
                        LocalAgent.CLAUDE_CODE,
                        capabilities = AgentCapabilities(install = true, signIn = true),
                        lifecycle = RuntimeLifecycle.Installing("Unpacking"),
                        auth = AgentAuthState.SignedIn("me@example.test"),
                    ),
                ),
                scoped(),
            )

        val snapshot = manager.current(LocalAgent.CLAUDE_CODE)

        assertTrue(snapshot.busy)
        assertFalse(snapshot.ready)
        assertFalse(snapshot.usable)
    }

    @Test
    fun `only a running server counts as usable`() = runTest {
        val running =
            AgentManager(
                listOf(
                    FakeSource(
                        LocalAgent.OPEN_CODE,
                        capabilities = AgentCapabilities(serverLifecycle = true),
                        lifecycle = RuntimeLifecycle.Running("1.18.5"),
                    ),
                ),
                scoped(),
            )
        val stopped =
            AgentManager(
                listOf(
                    FakeSource(
                        LocalAgent.OPEN_CODE,
                        capabilities = AgentCapabilities(serverLifecycle = true),
                        lifecycle = RuntimeLifecycle.Stopped,
                    ),
                ),
                scoped(),
            )

        assertTrue(running.current(LocalAgent.OPEN_CODE).usable)
        assertTrue(stopped.current(LocalAgent.OPEN_CODE).ready)
        assertFalse(stopped.current(LocalAgent.OPEN_CODE).usable)
    }

    @Test
    fun `a failed agent reports its reason without pretending to be installed`() = runTest {
        val manager =
            AgentManager(
                listOf(
                    FakeSource(
                        LocalAgent.ANTIGRAVITY,
                        lifecycle = RuntimeLifecycle.Failed("download failed"),
                        error = "download failed",
                    ),
                ),
                scoped(),
            )

        val snapshot = manager.current(LocalAgent.ANTIGRAVITY)

        assertEquals("download failed", snapshot.error)
        assertFalse(snapshot.ready)
        assertFalse(snapshot.busy)
    }

    // ---- capabilities --------------------------------------------------------------------------

    @Test
    fun `capabilities come from the source, so a screen only offers what the agent implements`() = runTest {
        val manager =
            AgentManager(
                listOf(
                    FakeSource(
                        LocalAgent.CODEX,
                        capabilities = AgentCapabilities(install = true, signIn = true, update = false, mcp = true),
                    ),
                ),
                scoped(),
            )

        val capabilities = manager.current(LocalAgent.CODEX).capabilities

        assertTrue(capabilities.install)
        assertTrue(capabilities.signIn)
        assertTrue(capabilities.mcp)
        assertFalse(capabilities.update)
        assertFalse(capabilities.hasPermissionModes)
    }

    @Test
    fun `an agent without a source has no capabilities rather than another agent's`() = runTest {
        val manager =
            AgentManager(
                listOf(FakeSource(LocalAgent.CLAUDE_CODE, capabilities = AgentCapabilities(install = true))),
                scoped(),
            )

        assertEquals(AgentCapabilities(), manager.current(LocalAgent.CODEX).capabilities)
    }

    // ---- aggregation ---------------------------------------------------------------------------

    @Test
    fun `the aggregated list keeps every agent's own facts apart`() = runTest {
        val claude =
            FakeSource(
                LocalAgent.CLAUDE_CODE,
                lifecycle = RuntimeLifecycle.Installed,
                auth = AgentAuthState.SignedIn("claude@example.test"),
                version = "1.2.3",
            )
        val codex =
            FakeSource(
                LocalAgent.CODEX,
                lifecycle = RuntimeLifecycle.Installing("fetching"),
                auth = AgentAuthState.SignedOut,
            )
        val manager = AgentManager(listOf(claude, codex), scoped())

        advanceUntilIdle()
        val snapshots = manager.snapshots.value

        assertEquals(listOf(LocalAgent.CLAUDE_CODE, LocalAgent.CODEX), snapshots.map { it.agent })
        assertEquals("1.2.3", snapshots[0].version)
        assertEquals(AgentAuthState.SignedIn("claude@example.test"), snapshots[0].auth)
        assertTrue(snapshots[0].ready)
        assertEquals(RuntimeLifecycle.Installing("fetching"), snapshots[1].lifecycle)
        assertFalse(snapshots[1].ready)
    }

    @Test
    fun `the single-agent read follows the live aggregation and starts unknown`() = runTest {
        val source = FakeSource(LocalAgent.CLAUDE_CODE)
        val manager = AgentManager(listOf(source), scoped())

        // Before the manager's scope has run, the honest answer is the all-unknown snapshot.
        assertEquals(RuntimeLifecycle.Unknown, manager.snapshot(LocalAgent.CLAUDE_CODE).lifecycle)

        advanceUntilIdle()
        source.lifecycleFlow.value = RuntimeLifecycle.Running("2.0.0")
        advanceUntilIdle()

        assertEquals(RuntimeLifecycle.Running("2.0.0"), manager.snapshot(LocalAgent.CLAUDE_CODE).lifecycle)
        assertEquals(LocalAgent.CLAUDE_CODE, manager.snapshot(LocalAgent.CLAUDE_CODE).agent)
        assertEquals(RuntimeLifecycle.Unknown, manager.snapshot(LocalAgent.ANTIGRAVITY).lifecycle)
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
            RuntimeLifecycleMapper.fromClaudeCode(
                ClaudeCodeUiState(install = ClaudeInstallStatus.Installing(step = 1)),
            ),
        )
        assertEquals(
            RuntimeLifecycle.Failed("boom"),
            RuntimeLifecycleMapper.fromClaudeCode(ClaudeCodeUiState(install = ClaudeInstallStatus.Failed("boom"))),
        )
    }

    @Test
    fun `antigravity and codex ui states map without inventing running states`() {
        assertEquals(
            RuntimeLifecycle.Installed,
            RuntimeLifecycleMapper.fromAntigravity(
                AntigravityControllerState(install = AntigravityInstallStatus.Ready("1.0")),
            ),
        )
        assertEquals(
            RuntimeLifecycle.Installed,
            RuntimeLifecycleMapper.fromCodex(CodexUiState(installed = true, signedIn = false)),
        )
        assertEquals(
            RuntimeLifecycle.Running("0.9"),
            RuntimeLifecycleMapper.fromCodex(CodexUiState(installed = true, signedIn = true, version = "0.9")),
        )
        assertEquals(
            RuntimeLifecycle.Failed("install failed"),
            RuntimeLifecycleMapper.fromCodex(CodexUiState(install = CodexInstallStatus.Failed("install failed"))),
        )
    }
}
