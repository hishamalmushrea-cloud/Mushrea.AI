package com.mushrea.code.core.execution

/**
 * A way of running things.
 *
 * This is the extension point the architecture was missing: today every device tool is a branch in
 * `DeviceAgentBridge`, so a new capability means editing the bridge, the catalog, the firewall and
 * the agent's tool table together. A provider instead declares what it can do and answers requests;
 * adding a transport (a second phone over ADB, the local runtime, a remote shell, an HTTP endpoint,
 * a future protocol) is adding an implementation, not re-cutting the bridge.
 *
 * The contract is deliberately small, because the interesting decisions are not here:
 *  * **policy** is decided before a provider is called (the Permission Center, from the request's
 *    [ExecutionEffect] and the caller's context);
 *  * **capability** is decided by the target (a [CapabilityReport] the provider fills in), not
 *    assumed from the provider's name;
 *  * **planning** is the [ExecutionPlanner]'s job, using the two above.
 *
 * A provider therefore only has to be honest: report what it supports, report what it needs, and
 * never turn a failure into an empty success.
 */
interface ExecutionProvider {
    /** Stable id, used in plans and in the execution log. */
    val id: String

    /** The one channel this provider serves. */
    val transport: ExecutionTransport

    /** True when this provider can run [operation] at all (before any target is considered). */
    fun supports(operation: ExecutionOperation): Boolean

    /**
     * Capabilities the target must report before this operation is worth attempting.
     *
     * Empty by default - a provider that cannot be sure says nothing rather than guessing. The names
     * are [CapabilityNames] constants, so a planner can compare two providers without knowing either.
     */
    fun requirements(operation: ExecutionOperation): Set<String> = emptySet()

    /**
     * An operation that reaches the same result when [missing] capability is absent.
     *
     * This is what turns "the device has no `exec-out`" from a dead end into a supported path: the
     * provider knows its own fallbacks, the planner only has to ask.
     */
    fun alternative(
        operation: ExecutionOperation,
        missing: String,
    ): ExecutionOperation? = null

    /** Runs the request, or reports why it could not. Must never throw for an expected failure. */
    suspend fun execute(request: ExecutionRequest): ExecutionResult

    /**
     * Reads the target's capabilities. Providers that cannot probe anything return
     * [CapabilityReport.unknown] rather than an empty report, which would read as "nothing works".
     */
    suspend fun capabilities(target: ExecutionTarget): CapabilityReport = CapabilityReport.unknown()
}

/** The capability names the platform standardises on. Providers may add their own. */
object CapabilityNames {
    const val SHELL = "shell"
    const val EXEC_OUT = "exec-out"
    const val SH = "sh"
    const val BASH = "bash"
    const val PYTHON = "python3"
    const val TOYBOX = "toybox"
    const val PM = "pm"
    const val AM = "am"
    const val CMD = "cmd"
    const val DUMPSYS = "dumpsys"
    const val SETTINGS = "settings"
    const val LOGCAT = "logcat"
    const val SCREENCAP = "screencap"
    const val INPUT = "input"
    const val UI_AUTOMATOR = "uiautomator"
    const val SU = "su"
    const val SYNC = "sync"
    const val INSTALL = "install"
    const val TELEPHONY = "telephony"
    const val PACKAGE_MANAGER = "package-manager"
}

/** Whether the target can do something, and why we think so. */
enum class CapabilityStatus {
    AVAILABLE,
    MISSING,

    /** The probe could not answer (no shell, timeout, refused) - never treated as available. */
    UNKNOWN,
}

/** One capability of one target. */
data class Capability(
    val name: String,
    val status: CapabilityStatus,
    val detail: String = "",
)

/**
 * What a target can actually do - the answer to "does this phone have `pm`, `python3`, `su`?".
 *
 * Android devices are not interchangeable: a shell is not guaranteed to have `cmd`, an interpreter
 * is only there if something shipped it, and an OEM can remove a `toybox` applet. A platform that
 * assumes otherwise works on the developer's phone and fails on the user's.
 */
class CapabilityReport(private val byName: Map<String, Capability>) {
    /** Every capability the target reported, in a stable order. */
    val all: List<Capability> get() = byName.values.sortedBy { it.name }

    val names: Set<String> get() = byName.keys

    fun status(name: String): CapabilityStatus = byName[name]?.status ?: CapabilityStatus.UNKNOWN

    fun detail(name: String): String = byName[name]?.detail.orEmpty()

    fun has(name: String): Boolean = status(name) == CapabilityStatus.AVAILABLE

    fun missing(name: String): Boolean = status(name) == CapabilityStatus.MISSING

    fun with(capability: Capability): CapabilityReport = CapabilityReport(byName + (capability.name to capability))

    companion object {
        /** No probe ran (or the target refused): everything is unknown, nothing is promised. */
        fun unknown(): CapabilityReport = CapabilityReport(emptyMap())

        fun of(capabilities: Iterable<Capability>): CapabilityReport =
            CapabilityReport(capabilities.associateBy { it.name })

        fun available(
            name: String,
            detail: String = "",
        ): Capability = Capability(name, CapabilityStatus.AVAILABLE, detail)

        fun missing(
            name: String,
            detail: String = "",
        ): Capability = Capability(name, CapabilityStatus.MISSING, detail)
    }
}
