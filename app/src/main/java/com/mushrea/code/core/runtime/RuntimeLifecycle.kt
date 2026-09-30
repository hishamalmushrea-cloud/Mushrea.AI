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

/**
 * Everything the app knows about one runtime at one instant.
 *
 * This is the read model a central manager hands out: discovery ([id], [name], [agent]), lifecycle,
 * health, [version], [port], [environment] and the [error] that explains a failure, in one object
 * instead of five flows.
 */
data class RuntimeSnapshot(
    val id: String,
    val name: String,
    val agent: String? = null,
    val lifecycle: RuntimeLifecycle = RuntimeLifecycle.Unknown,
    val health: RuntimeHealth = RuntimeHealth.UNKNOWN,
    val version: String? = null,
    val port: Int? = null,
    val environment: String? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = lifecycle.busy

    val usable: Boolean get() = lifecycle.usable && health != RuntimeHealth.UNREACHABLE
}

/** Whether [ids] describe the same runtime, used when a stale selection must be dropped. */
fun RuntimeSnapshot.matches(id: String?): Boolean = id != null && this.id == id
