package com.mushrea.code.core.permission

/**
 * Which subsystem an authorization request belongs to.
 *
 * The center routes by this value, so a domain is only worth adding when a policy is registered for
 * it. Today that is exactly the surface the platform actually gates:
 *
 *  * the six domains the device bridge dispatches through one catalog — [DEVICE], [SCREEN],
 *    [FILES], [NETWORK], [USB], [SSH], [REMOTE] (the catalog family decides which one a tool maps
 *    to, so "Device Agent" is not one undifferentiated bucket);
 *  * [AGENT_RUNTIME] for the agent's own tool-permission prompts, which the chat, voice and
 *    schedule paths must answer through policy instead of their own `if`;
 *  * [RUNTIME_LIFECYCLE] for installing, starting and stopping the on-device runtime.
 */
enum class PermissionDomain {
    /** Screen control, calls, Bluetooth, Termux, MTP, payload and the device's own status tools. */
    DEVICE,

    /** The accessibility screen surface (tap, type, scroll, read screen). */
    SCREEN,

    /** Local file operations the agent asks for (delete, move, copy, read, list). */
    FILES,

    /** Outbound network probes and requests issued as device tools. */
    NETWORK,

    /** USB/ADB, USB serial and USB hubs. */
    USB,

    /** SSH/SFTP execution against a server the user configured. */
    SSH,

    /** File transfer to a remote (FTP/SMB/SSH) target. */
    REMOTE,

    /** The coding agent's own tool-permission prompts (allow/deny from the runtime protocol). */
    AGENT_RUNTIME,

    /** Installing, starting, stopping or deleting the on-device Linux runtime. */
    RUNTIME_LIFECYCLE,

    /**
     * Another Android phone reached over wireless debugging (Peer ADB).
     *
     * One domain for the whole transport, because the interesting question is not *which command*
     * but *what it does*: the peer policy weighs the operation's declared effect (read-only,
     * state-changing, high risk) rather than matching a command against a list - which is what keeps
     * the agent's reach equal to what adb and the other phone can actually do.
     */
    PEER_DEVICE,
}

/**
 * Who asked for the operation.
 *
 * It is deliberately separate from [PermissionActor]: the actor is the audit's word for *a human or
 * the agent*, while the source is what the policy reasons about — a schedule is not the same risk as
 * a user tapping a button, even though both are "the app acting on behalf of someone".
 *
 * Like [PermissionActor], a source is a *claimed* identity on the device channel (the agent writes a
 * command file, so the app cannot verify who wrote it) and the audit log records it as such.
 */
enum class PermissionSource {
    /** A person using the app's own controls. */
    USER,

    /** The coding agent, through a tool call. */
    AGENT,

    /** A scheduled run, with no one watching the screen. */
    SCHEDULE,

    /** The app itself: a readiness probe, an auto-start, a retry, a boot receiver. */
    SYSTEM,
}

/**
 * One authorization question, in the one shape every subsystem asks it.
 *
 * The fields are not a wish list: each one has a consumer in the current code, and the comment next
 * to it names that consumer. Anything a policy does not need is not here — a request that carries
 * unused context is a request whose context will drift.
 *
 * @param domain which subsystem is asking (routing).
 * @param operation the catalog action id for device tools (`usb_adb_shell`), or a dotted name for
 *   the other domains (`runtime.lifecycle.start`, `agent.permission.auto_accept`).
 * @param source who asked; the policy distinguishes a user tap from a schedule run.
 * @param target what the operation acts on (a path, host, app id, runtime id) — shown in the
 *   confirmation prompt and written to the audit log.
 * @param risk how damaging the operation is if it runs when it should not; comes from the tool
 *   catalog for device tools and from the policy for the rest.
 * @param mutatesState whether the operation changes state; the center's read-only rule needs it,
 *   and it comes from the catalog's `readOnly` flag rather than from a second hand-written list.
 * @param readOnly the current Read-Only switch, read from the store at request time.
 * @param emergencyStop whether the user has pressed the emergency stop; the center refuses
 *   everything except [safetyOperation] while it is set.
 * @param safetyOperation true only for the stop action itself, which must stay reachable while
 *   everything else is blocked.
 * @param preAuthorized a standing authorization the user granted beforehand (the app's
 *   *auto-accept permissions* setting). It is an input, never a bypass: a policy still has to
 *   accept it, and the decision is recorded.
 * @param tapLabel the visible text of the element a tap targets, for the sensitive-control
 *   escalation. Null for every operation that is not a tap.
 */
data class PermissionRequest(
    val domain: PermissionDomain,
    val operation: String,
    val source: PermissionSource,
    val target: String? = null,
    val risk: PermissionRisk? = null,
    val mutatesState: Boolean = true,
    val readOnly: Boolean = false,
    val emergencyStop: Boolean = false,
    val safetyOperation: Boolean = false,
    val preAuthorized: Boolean = false,
    val tapLabel: String? = null,
)
