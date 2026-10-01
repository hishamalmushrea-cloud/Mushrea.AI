package com.mushrea.code.runtime.agent

import com.mushrea.code.core.agent.AgentAuthState
import com.mushrea.code.runtime.local.AntigravityAuthCoordinator
import com.mushrea.code.runtime.local.ClaudeAuthCoordinator

/**
 * Translates each agent's own sign-in model into the single [AgentAuthState] vocabulary.
 *
 * Pure functions, so the mapping is unit-testable without a device or a running agent - the same
 * rule the lifecycle mapper follows.
 *
 * Antigravity's `SignedIn` carries a detail string and Claude's carries the account; both become
 * `SignedIn(account)` because that is all any screen shows. Claude's `Failed` carries a transcript
 * the log keeps, so it is preserved rather than dropped.
 */
object AgentAuthMapper {
    fun fromClaude(state: ClaudeAuthCoordinator.State): AgentAuthState =
        when (state) {
            ClaudeAuthCoordinator.State.Idle -> AgentAuthState.SignedOut
            ClaudeAuthCoordinator.State.Starting -> AgentAuthState.Starting
            is ClaudeAuthCoordinator.State.AwaitingBrowser ->
                AgentAuthState.AwaitingBrowser(url = state.url, transcript = state.transcript)
            ClaudeAuthCoordinator.State.Verifying -> AgentAuthState.Verifying
            is ClaudeAuthCoordinator.State.SignedIn -> AgentAuthState.SignedIn(account = state.account)
            is ClaudeAuthCoordinator.State.Failed ->
                AgentAuthState.Failed(message = state.message, transcript = state.transcript)
        }

    fun fromAntigravity(state: AntigravityAuthCoordinator.State): AgentAuthState =
        when (state) {
            AntigravityAuthCoordinator.State.Idle -> AgentAuthState.SignedOut
            AntigravityAuthCoordinator.State.Starting -> AgentAuthState.Starting
            is AntigravityAuthCoordinator.State.AwaitingBrowser ->
                AgentAuthState.AwaitingBrowser(url = state.url, transcript = state.transcript)
            AntigravityAuthCoordinator.State.Verifying -> AgentAuthState.Verifying
            is AntigravityAuthCoordinator.State.SignedIn -> AgentAuthState.SignedIn(account = state.detail)
            is AntigravityAuthCoordinator.State.Failed ->
                AgentAuthState.Failed(message = state.message, transcript = state.transcript)
        }

    /**
     * Codex reports sign-in as a boolean, so the flow's intermediate steps do not exist on its side;
     * they are never invented here.
     */
    fun fromCodex(signedIn: Boolean): AgentAuthState = if (signedIn) AgentAuthState.SignedIn() else AgentAuthState.SignedOut
}
