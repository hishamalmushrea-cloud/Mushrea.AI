package com.mushrea.code.runtime.lifecycle

import com.mushrea.code.core.runtime.RuntimeHealth
import com.mushrea.code.core.runtime.RuntimeLifecycle
import com.mushrea.code.runtime.LocalRuntimeStatus
import com.mushrea.code.runtime.RuntimeState
import com.mushrea.code.runtime.local.AntigravityControllerState
import com.mushrea.code.runtime.local.AntigravityInstallStatus
import com.mushrea.code.runtime.local.ClaudeCodeUiState
import com.mushrea.code.runtime.local.ClaudeInstallStatus
import com.mushrea.code.runtime.local.CodexInstallStatus
import com.mushrea.code.runtime.local.CodexUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the one lifecycle vocabulary.
 *
 * They are deliberately about semantics, not about calling the mapper: each test names a fact the
 * UI depends on ("a connection that is still being established is never reported as running") so a
 * later refactor of the state models cannot silently flip it.
 */
class RuntimeLifecycleMapperTest {
    // ---- local runtime environment -------------------------------------------------------------

    @Test
    fun `a runtime that isn't installed is available, not failed`() {
        // Available is what makes the setup guide offer an install; Failed would send the user to a
        // repair path for something that simply has not happened yet.
        assertEquals(
            RuntimeLifecycle.Available,
            RuntimeLifecycleMapper.fromLocalRuntimeStatus(LocalRuntimeStatus.NotInstalled),
        )
    }

    @Test
    fun `an unsupported ABI is a failure that names the ABI`() {
        val lifecycle = RuntimeLifecycleMapper.fromLocalRuntimeStatus(LocalRuntimeStatus.UnsupportedAbi("armeabi-v7a"))

        assertEquals(RuntimeLifecycle.Failed("Unsupported ABI: armeabi-v7a"), lifecycle)
    }

    @Test
    fun `install and update both read as installing, carrying their step`() {
        val installing = RuntimeLifecycleMapper.fromLocalRuntimeStatus(LocalRuntimeStatus.Installing(0.5f, "Downloading runtime"))
        val updating =
            RuntimeLifecycleMapper.fromLocalRuntimeStatus(
                LocalRuntimeStatus.Updating("1.18.5", "1.19.0", 0.2f, "Applying update"),
            )

        assertEquals(RuntimeLifecycle.Installing("Downloading runtime"), installing)
        assertEquals(RuntimeLifecycle.Installing("Applying update"), updating)
        assertTrue(installing.busy)
        assertTrue(updating.busy)
    }

    @Test
    fun `ready is running and usable`() {
        val lifecycle = RuntimeLifecycleMapper.fromLocalRuntimeStatus(LocalRuntimeStatus.Ready("1.18.5", 4097))

        assertEquals(RuntimeLifecycle.Running("1.18.5"), lifecycle)
        assertTrue(lifecycle.usable)
        assertFalse(lifecycle.busy)
    }

    @Test
    fun `ready but not answering is a failure, because the app cannot reach it`() {
        // The concrete case this guards: the process is up as far as the metadata says, but the
        // port probe fails. Reporting that as Running is what makes chat hang instead of explaining.
        val lifecycle =
            RuntimeLifecycleMapper.fromLocalRuntimeStatus(
                LocalRuntimeStatus.Ready("1.18.5", 4097),
                healthy = false,
            )

        assertTrue(lifecycle is RuntimeLifecycle.Failed)
        assertTrue((lifecycle as RuntimeLifecycle.Failed).reason.contains("4097"))
    }

    @Test
    fun `stopped and broken keep their distinct meanings`() {
        assertEquals(
            RuntimeLifecycle.Stopped,
            RuntimeLifecycleMapper.fromLocalRuntimeStatus(LocalRuntimeStatus.Stopped("1.18.5", 4097)),
        )
        assertEquals(
            RuntimeLifecycle.Failed("rootfs is corrupt"),
            RuntimeLifecycleMapper.fromLocalRuntimeStatus(LocalRuntimeStatus.Broken("rootfs is corrupt")),
        )
    }

    // ---- selectable targets --------------------------------------------------------------------

    @Test
    fun `a target being connected to is starting, never running`() {
        assertEquals(RuntimeLifecycle.Starting, RuntimeLifecycleMapper.fromRuntimeState(RuntimeState.Connecting))
        assertFalse(RuntimeLifecycleMapper.fromRuntimeState(RuntimeState.Connecting).usable)
    }

    @Test
    fun `target states keep version, reason and failure text`() {
        assertEquals(
            RuntimeLifecycle.Running("1.19.0"),
            RuntimeLifecycleMapper.fromRuntimeState(RuntimeState.Connected("1.19.0")),
        )
        assertEquals(
            RuntimeLifecycle.Available,
            RuntimeLifecycleMapper.fromRuntimeState(RuntimeState.Unavailable("no endpoint")),
        )
        assertEquals(
            RuntimeLifecycle.Failed("connection refused"),
            RuntimeLifecycleMapper.fromRuntimeState(RuntimeState.Failed("connection refused")),
        )
        assertEquals(RuntimeLifecycle.Stopped, RuntimeLifecycleMapper.fromRuntimeState(RuntimeState.Disconnected))
    }

    @Test
    fun `health is read from the same state, and an unknown state is unknown health`() {
        assertEquals(RuntimeHealth.HEALTHY, RuntimeLifecycleMapper.healthOf(RuntimeState.Connected("1.18.5")))
        assertEquals(RuntimeHealth.UNREACHABLE, RuntimeLifecycleMapper.healthOf(RuntimeState.Failed("timeout")))
        assertEquals(RuntimeHealth.UNKNOWN, RuntimeLifecycleMapper.healthOf(RuntimeState.Connecting))
        assertEquals(RuntimeHealth.UNKNOWN, RuntimeLifecycleMapper.healthOf(RuntimeState.Disconnected))
        assertEquals(RuntimeHealth.UNKNOWN, RuntimeLifecycleMapper.healthOf(RuntimeState.Unavailable("no endpoint")))
    }

    // ---- agents --------------------------------------------------------------------------------

    @Test
    fun `claude reports available, installing, installed and failed apart from each other`() {
        assertEquals(
            RuntimeLifecycle.Available,
            RuntimeLifecycleMapper.fromClaudeCode(ClaudeCodeUiState()),
        )
        assertEquals(
            RuntimeLifecycle.Installing(detail = null),
            RuntimeLifecycleMapper.fromClaudeCode(
                ClaudeCodeUiState(install = ClaudeInstallStatus.Installing(step = 2)),
            ),
        )
        assertEquals(
            RuntimeLifecycle.Installed,
            RuntimeLifecycleMapper.fromClaudeCode(ClaudeCodeUiState(installed = true, version = "1.2.3")),
        )
        assertEquals(
            RuntimeLifecycle.Failed("apk failed"),
            RuntimeLifecycleMapper.fromClaudeCode(
                ClaudeCodeUiState(install = ClaudeInstallStatus.Failed("apk failed")),
            ),
        )
    }

    @Test
    fun `a finished claude install is installed, not running, because no server is up`() {
        // Claude Code runs one process per turn; "Ready" means the files are there, so claiming
        // Running would tell the UI a session can be opened without launching anything.
        val lifecycle =
            RuntimeLifecycleMapper.fromClaudeCode(
                ClaudeCodeUiState(installed = true, install = ClaudeInstallStatus.Ready("1.2.3")),
            )

        assertEquals(RuntimeLifecycle.Installed, lifecycle)
        assertFalse(lifecycle.usable)
    }

    @Test
    fun `antigravity carries its install step through and reports failures verbatim`() {
        assertEquals(
            RuntimeLifecycle.Installing("Unpacking Debian rootfs"),
            RuntimeLifecycleMapper.fromAntigravity(
                AntigravityControllerState(install = AntigravityInstallStatus.Installing(0.4f, "Unpacking Debian rootfs")),
            ),
        )
        assertEquals(
            RuntimeLifecycle.Failed("download failed"),
            RuntimeLifecycleMapper.fromAntigravity(
                AntigravityControllerState(install = AntigravityInstallStatus.Failed("download failed")),
            ),
        )
    }

    @Test
    fun `codex needs both install and sign-in before it is running`() {
        assertEquals(
            RuntimeLifecycle.Available,
            RuntimeLifecycleMapper.fromCodex(CodexUiState()),
        )
        assertEquals(
            RuntimeLifecycle.Installed,
            RuntimeLifecycleMapper.fromCodex(CodexUiState(installed = true, version = "0.9.0")),
        )
        assertEquals(
            RuntimeLifecycle.Running("0.9.0"),
            RuntimeLifecycleMapper.fromCodex(CodexUiState(installed = true, signedIn = true, version = "0.9.0")),
        )
    }

    @Test
    fun `a codex install without a message still reports a failure reason`() {
        val lifecycle =
            RuntimeLifecycleMapper.fromCodex(
                CodexUiState(install = CodexInstallStatus.Failed(message = null)),
            )

        assertEquals(RuntimeLifecycle.Failed("Codex installation failed"), lifecycle)
    }

    // ---- the vocabulary itself -----------------------------------------------------------------

    @Test
    fun `busy and usable are disjoint, so an operation can never be reported as ready`() {
        val states =
            listOf(
                RuntimeLifecycle.Unknown,
                RuntimeLifecycle.Available,
                RuntimeLifecycle.Installing(),
                RuntimeLifecycle.Installed,
                RuntimeLifecycle.Starting,
                RuntimeLifecycle.Stopping,
                RuntimeLifecycle.Stopped,
                RuntimeLifecycle.Running("1.18.5"),
                RuntimeLifecycle.Failed("nope"),
            )

        states.forEach { lifecycle ->
            assertFalse("${lifecycle::class.simpleName} cannot be both busy and usable", lifecycle.busy && lifecycle.usable)
        }
    }

    @Test
    fun `only running is usable`() {
        assertTrue(RuntimeLifecycle.Running("1.18.5").usable)
        assertFalse(RuntimeLifecycle.Installed.usable)
        assertFalse(RuntimeLifecycle.Stopped.usable)
        assertFalse(RuntimeLifecycle.Available.usable)
        assertFalse(RuntimeLifecycle.Unknown.usable)
        assertFalse(RuntimeLifecycle.Failed("nope").usable)
    }
}
