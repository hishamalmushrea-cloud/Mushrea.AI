package com.mushrea.code.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mushrea.code.R
import com.mushrea.code.core.runtime.RuntimeLifecycle
import com.mushrea.code.runtime.agent.AgentSnapshot

/**
 * The one place an agent's unified state becomes words on screen.
 *
 * Claude Code, Antigravity and Codex each had their own `statusLabel()` doing the same four checks
 * against their own state type; they now share this function, so a change to the wording or the
 * ordering of the checks cannot drift between agents.
 *
 * OpenCode still renders its own status text on purpose: that string carries runtime detail the
 * shared label does not (the current install/update step, the port), and flattening it would lose
 * information the screen needs. It does use the shared [AgentSnapshot] for its active flag.
 */
@Composable
fun AgentSnapshot.statusLabel(): String =
    when {
        lifecycle is RuntimeLifecycle.Installing -> stringResource(R.string.runtime_status_setting_up)
        lifecycle is RuntimeLifecycle.Failed -> stringResource(R.string.agent_status_install_failed)
        !installed -> stringResource(R.string.runtime_status_not_installed)
        ready -> stringResource(R.string.agent_status_ready)
        else -> stringResource(R.string.agent_status_sign_in_required)
    }

/** Installed means the files are in place, whether or not anything is running. */
val AgentSnapshot.installed: Boolean
    get() =
        when (lifecycle) {
            is RuntimeLifecycle.Running, RuntimeLifecycle.Installed, RuntimeLifecycle.Stopped -> true
            else -> false
        }
