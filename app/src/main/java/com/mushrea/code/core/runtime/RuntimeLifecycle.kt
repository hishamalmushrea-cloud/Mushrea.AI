package com.mushrea.code.core.runtime

/**
 * One lifecycle vocabulary shared by every runtime, so a screen (or an agent) never has to know
 * which controller produced the state it is looking at.
 *
 * The app grew four parallel state models for the same idea - ``LocalRuntimeStatus`` (runtime
 * layer) for the shared Linux environment, ``RuntimeState`` for a selectable target, and one
 * install-status model per agent (Claude Code, Antigravity, Codex). They agree on the rough shape but disagree on names, and a
 * missing state in one of them (there was no "Stopping" anywhere, and "Available" had no
 * equivalent) meant the gap was papered over at each call site instead.
 *
 * This type is the single vocabulary. The existing models keep their own semantics where they
 * carry real meaning; [RuntimeLifecycleMapper] translates them, so nothing had to be rewritten to
 * gain a common language.
 *
 * The states form one progression, with [Failed] reachable from any of them:
 *
 * ```
 * Unknown -> Available -> Installing -> Installed -> Starting -> Running
 *                 ^                                                  |
 *                 |                                                  v
 *                 +--- Stopped <-------------------------------- Stopping
 *
 *   (any state) -> Failed
 * ```
 */
sealed interface RuntimeLifecycle {
    /** Nothing is known yet: this runtime has not been probed or read since the app started. */
    data object Unknown : RuntimeLifecycle

    /** Known to be usable *if* installed - the runtime exists on this device or was discovered. */
    data object Available : RuntimeLifecycle

    /** An install or update is writing files. [detail] is the current step, when one is known. */
    data class Installing(val detail: String? = null) : RuntimeLifecycle

    /** Files are in place but no process or server is running yet. */
    data object Installed : RuntimeLifecycle

    /** A process/server is being brought up (or a connection is being established). */
    data object Starting : RuntimeLifecycle

    /** Running and reachable; [version] is the reported version when the runtime reports one. */
    data class Running(val version: String? = null) : RuntimeLifecycle

    /** A shutdown is in flight. */
    data object Stopping : RuntimeLifecycle

    /** Installed, intentionally not running. */
    data object Stopped : RuntimeLifecycle

    /** The last operation or probe failed; [reason] is user-facing. */
    data class Failed(val reason: String) : RuntimeLifecycle

    /** True while an operation owns the runtime, so no second action may be dispatched. */
    val busy: Boolean
        get() = this is Installing || this is Starting || this is Stopping

    /** True once the runtime answers: the only state in which a session may be opened. */
    val usable: Boolean
        get() = this is Running
}

/**
 * Health as a value the UI and the agents can compare, kept separate from the lifecycle because a
 * runtime can be [RuntimeLifecycle.Running] yet have stopped answering (a wedged local server is
 * the concrete case this app already handles in chat).
 */
enum class RuntimeHealth {
    /** Answered a probe just now. */
    HEALTHY,

    /** Reachable but degraded: slow, or answering with errors the runtime itself reports. */
    DEGRADED,

    /** Known to be installed but the last probe did not answer. */
    UNREACHABLE,

    /** No probe has run, or the runtime cannot be probed in its current state. */
    UNKNOWN,
}

/*
 * `RuntimeSnapshot` used to live here: a read model nothing produced and nothing read (Phase 1
 * audit). It was removed in Phase 2 rather than kept as a contract with no implementation: the
 * per-runtime state the app actually hands out is `RuntimeTarget.state`/`lifecycle` (runtime layer)
 * and `AgentSnapshot` (agent layer), which carry the same vocabulary and do have producers and
 * consumers. Bringing a snapshot back means giving it a producer first.
 */
