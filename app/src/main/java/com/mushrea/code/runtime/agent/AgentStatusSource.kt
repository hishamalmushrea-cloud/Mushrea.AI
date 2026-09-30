package com.mushrea.code.runtime.agent

import com.mushrea.code.core.agent.AgentAuthState
import com.mushrea.code.core.runtime.RuntimeHealth
import com.mushrea.code.core.runtime.RuntimeLifecycle
import com.mushrea.code.runtime.LocalAgent
import kotlinx.coroutines.flow.Flow

/**
 * One agent's live facts, as the manager reads them.
 *
 * Each agent is owned by a different controller with a different state type; this is the small
 * adapter surface that lets the manager treat them alike without touching those controllers. The
 * app implements it for the four local agents (see `runtime/local/AgentStatusSources.kt`) and tests
 * implement it with plain fakes.
 *
 * Everything here is a live [Flow] rather than a getter so a screen observes the same value the
 * manager aggregates, with no polling in between.
 */
interface AgentStatusSource {
    /** Which agent these facts belong to. */
    val agent: LocalAgent

    /** What this agent can do, read from the wiring rather than guessed from its name. */
    val capabilities: AgentCapabilities

    val lifecycle: Flow<RuntimeLifecycle>

    /** Sign-in state; `Unknown` for an agent with no sign-in flow. */
    val auth: Flow<AgentAuthState>
        get() = kotlinx.coroutines.flow.flowOf(AgentAuthState.Unknown)

    /** Health, when the agent can report something better than the connection state implies. */
    val health: Flow<RuntimeHealth>
        get() = kotlinx.coroutines.flow.flowOf(RuntimeHealth.UNKNOWN)

    /** Installed version, when one is known. */
    val version: Flow<String?>
        get() = kotlinx.coroutines.flow.flowOf(null)

    /** The last user-facing failure, when the agent is in one. */
    val error: Flow<String?>
        get() = kotlinx.coroutines.flow.flowOf(null)

    /** Re-reads the agent's state; a no-op for sources that are already live. */
    suspend fun refresh() = Unit
}
