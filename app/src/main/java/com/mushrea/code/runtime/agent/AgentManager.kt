package com.mushrea.code.runtime.agent

import com.mushrea.code.core.runtime.RuntimeLifecycle
import com.mushrea.code.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The one place that answers "what are the agents doing?".
 *
 * Four agents exist, each owned by its own controller with its own state type. This manager owns no
 * state of its own: it aggregates a set of [AgentStatusSource]s into one list of [AgentSnapshot]s,
 * so a screen can render any agent from the same object and the app can answer questions like "is
 * Codex ready?" without knowing which controller holds the answer.
 *
 * ### What it deliberately does not do
 *
 * It does not start, stop or restart agents. Only OpenCode runs as a long-lived local server; the
 * other three run a process per turn, so a uniform `start()` would be a fiction for them - the same
 * reason `RuntimeLifecycle.Stopping` is only reported where a shutdown really exists. Instead
 * [AgentCapabilities] states which agent has a server lifecycle, and the operations that do exist
 * (install, update, sign in/out) stay with the controller that implements them.
 *
 * It also does not invent state: a source that does not report health or a version contributes
 * `UNKNOWN`/null rather than a plausible-looking default.
 */
class AgentManager(
    sources: List<AgentStatusSource>,
    scope: CoroutineScope,
) {
    private val sourcesByAgent: Map<LocalAgent, AgentStatusSource> = sources.associateBy { it.agent }

    /**
     * Snapshot of one agent, kept fresh by its source.
     *
     * An agent without a source yields an all-unknown snapshot rather than null: a screen asking
     * about an agent the app does not manage should render "unknown", not crash or show nothing.
     */
    fun snapshotFlow(agent: LocalAgent): Flow<AgentSnapshot> =
        sourcesByAgent[agent]?.let(::snapshotOf) ?: flowOf(AgentSnapshot(agent))

    /** Every agent this manager knows about, in the order the sources were supplied. */
    val snapshots: StateFlow<List<AgentSnapshot>> =
        combine(sources.map(::snapshotOf)) { agentSnapshots -> agentSnapshots.toList() }
            .stateIn(scope, SharingStarted.Eagerly, sources.map(::initialSnapshot))

    /** The agent ids this manager can answer for - i.e. what "discovery" means here. */
    val agents: Set<LocalAgent> get() = sourcesByAgent.keys

    /**
     * The current snapshot of one agent, read from the live aggregation.
     *
     * It reads the aggregated flow rather than a private copy, so a caller that cannot collect
     * flows (a navigation graph building an argument, a `when` in a view model) still sees the
     * agent's real state. Before the sources have emitted, this is the honest all-unknown value.
     */
    fun snapshot(agent: LocalAgent): AgentSnapshot =
        snapshots.value.firstOrNull { it.agent == agent } ?: AgentSnapshot(agent)

    /** True when this manager has a live source for [agent]. */
    fun knows(agent: LocalAgent): Boolean = sourcesByAgent.containsKey(agent)

    fun capabilities(agent: LocalAgent): AgentCapabilities = sourcesByAgent[agent]?.capabilities ?: AgentCapabilities()

    /** Re-reads every agent; the settings screens call this on entry. */
    suspend fun refreshAll() {
        sourcesByAgent.values.forEach { it.refresh() }
    }

    private fun snapshotOf(source: AgentStatusSource): Flow<AgentSnapshot> =
        combine(source.lifecycle, source.auth, source.version, source.health, source.error) { lifecycle, auth, version, health, error ->
            AgentSnapshot(
                agent = source.agent,
                lifecycle = lifecycle,
                health = health,
                auth = auth,
                version = version,
                error = error,
                capabilities = source.capabilities,
            )
        }

    /** The value used before the first emission, so a screen never shows nothing while flows warm up. */
    private fun initialSnapshot(source: AgentStatusSource) =
        AgentSnapshot(
            agent = source.agent,
            capabilities = source.capabilities,
        )

    /** Convenience for callers that only want the readiness rule, without collecting flows. */
    fun isReady(agent: LocalAgent): Boolean = snapshot(agent).ready

    /** Convenience for callers that only want the busy rule. */
    fun isBusy(agent: LocalAgent): Boolean = snapshot(agent).busy

    /** The lifecycle of [agent] as a plain flow, for screens that only need that part. */
    fun lifecycleOf(agent: LocalAgent): Flow<RuntimeLifecycle> =
        sourcesByAgent[agent]?.lifecycle ?: flowOf(RuntimeLifecycle.Unknown)
}
