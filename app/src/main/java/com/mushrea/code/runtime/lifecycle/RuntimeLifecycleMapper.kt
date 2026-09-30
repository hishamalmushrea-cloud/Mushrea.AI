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

/**
 * Translates the four existing runtime state models into the single [RuntimeLifecycle] vocabulary.
 *
 * Every function here is pure: given the same input state it returns the same lifecycle, which is
 * what makes the translation testable without a device or a running runtime. Nothing in this file
 * invents state the source model does not carry - a missing detail becomes null rather than a
 * guess, so an unknown step is never rendered as a confident one.
 *
 * The mappings are deliberately conservative in two places:
 *  * a runtime that is installed but not running maps to [RuntimeLifecycle.Stopped] (not
 *    `Available`), because the app knows it exists on disk;
 *  * a connection that is merely being established (`Connecting`) maps to
 *    [RuntimeLifecycle.Starting], never to `Running`, so a caller cannot treat "trying" as "ready".
 */
object RuntimeLifecycleMapper {
    /** Maps the shared local environment's status. [healthy] is a live probe result when known. */
    fun fromLocalRuntimeStatus(
        status: LocalRuntimeStatus,
        healthy: Boolean? = null,
    ): RuntimeLifecycle =
        when (status) {
            LocalRuntimeStatus.NotInstalled -> RuntimeLifecycle.Available
            is LocalRuntimeStatus.UnsupportedAbi -> RuntimeLifecycle.Failed("Unsupported ABI: ${status.abi}")
            is LocalRuntimeStatus.Installing -> RuntimeLifecycle.Installing(status.step)
            is LocalRuntimeStatus.Starting -> RuntimeLifecycle.Starting
            is LocalRuntimeStatus.Updating -> RuntimeLifecycle.Installing(status.step)
            is LocalRuntimeStatus.Stopped -> RuntimeLifecycle.Stopped
            is LocalRuntimeStatus.Broken -> RuntimeLifecycle.Failed(status.reason)
            is LocalRuntimeStatus.Ready ->
                if (healthy == false) {
                    RuntimeLifecycle.Failed("The runtime is installed but is not answering on port ${status.port}")
                } else {
                    RuntimeLifecycle.Running(status.version)
                }
        }

    /** Maps a selectable target's connection state. */
    fun fromRuntimeState(state: RuntimeState): RuntimeLifecycle =
        when (state) {
            RuntimeState.Disconnected -> RuntimeLifecycle.Stopped
            RuntimeState.Connecting -> RuntimeLifecycle.Starting
            is RuntimeState.Connected -> RuntimeLifecycle.Running(state.version)
            is RuntimeState.Unavailable -> RuntimeLifecycle.Available
            is RuntimeState.Failed -> RuntimeLifecycle.Failed(state.message)
        }

    /** Health of a target, as far as its connection state alone can tell. */
    fun healthOf(state: RuntimeState): RuntimeHealth =
        when (state) {
            is RuntimeState.Connected -> RuntimeHealth.HEALTHY
            is RuntimeState.Failed -> RuntimeHealth.UNREACHABLE
            is RuntimeState.Unavailable, RuntimeState.Disconnected -> RuntimeHealth.UNKNOWN
            RuntimeState.Connecting -> RuntimeHealth.UNKNOWN
        }

    /** Maps the Claude Code agent's install status seen through its UI state. */
    fun fromClaudeCode(state: ClaudeCodeUiState): RuntimeLifecycle =
        fromClaudeInstallStatus(state.install, installed = state.installed)

    /**
     * [ClaudeInstallStatus.Installing] carries a string resource id, not a human step name, so the
     * detail is left null here: this mapper is pure and cannot resolve resources. The settings
     * screen already renders that resource id itself.
     */
    fun fromClaudeInstallStatus(
        status: ClaudeInstallStatus,
        installed: Boolean = false,
    ): RuntimeLifecycle =
        when (status) {
            ClaudeInstallStatus.Idle ->
                if (installed) RuntimeLifecycle.Installed else RuntimeLifecycle.Available
            is ClaudeInstallStatus.Installing -> RuntimeLifecycle.Installing(detail = null)
            is ClaudeInstallStatus.Ready -> RuntimeLifecycle.Installed
            is ClaudeInstallStatus.Failed -> RuntimeLifecycle.Failed(status.message)
        }

    /** Maps the Antigravity agent's state. */
    fun fromAntigravity(state: AntigravityControllerState): RuntimeLifecycle =
        fromAntigravityInstallStatus(state.install, installed = state.installed)

    fun fromAntigravityInstallStatus(
        status: AntigravityInstallStatus,
        installed: Boolean = false,
    ): RuntimeLifecycle =
        when (status) {
            AntigravityInstallStatus.Idle ->
                if (installed) RuntimeLifecycle.Installed else RuntimeLifecycle.Available
            is AntigravityInstallStatus.Installing -> RuntimeLifecycle.Installing(status.step)
            is AntigravityInstallStatus.Ready -> RuntimeLifecycle.Installed
            is AntigravityInstallStatus.Failed -> RuntimeLifecycle.Failed(status.message)
        }

    /**
     * Maps the Codex agent's state.
     *
     * Codex is the one agent whose readiness needs two facts: the CLI installed *and* signed in.
     * It reports neither a starting nor a stopping state of its own, so those are never invented
     * here; an installed-but-signed-out Codex is [RuntimeLifecycle.Installed], which is exactly what
     * the UI needs to prompt for sign-in.
     */
    fun fromCodex(state: CodexUiState): RuntimeLifecycle =
        when (val status = state.install) {
            CodexInstallStatus.Idle ->
                when {
                    !state.installed -> RuntimeLifecycle.Available
                    state.signedIn -> RuntimeLifecycle.Running(state.version)
                    else -> RuntimeLifecycle.Installed
                }
            is CodexInstallStatus.Installing -> RuntimeLifecycle.Installing(status.step)
            is CodexInstallStatus.Failed -> RuntimeLifecycle.Failed(status.message ?: "Codex installation failed")
        }
}
