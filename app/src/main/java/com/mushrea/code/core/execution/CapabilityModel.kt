package com.mushrea.code.core.execution

/**
 * The platform's capability vocabulary.
 *
 * A "capability" is one thing a target was *measured* to be able to do: a program that exists, a
 * shell that answers, a service that is reachable, a filesystem that accepts writes. Capabilities are
 * discovered per target and never assumed - Android devices differ by vendor, by build and by what
 * the user installed, and an agent that assumes `cmd` or `python3` exists works on the developer's
 * phone and fails on the user's.
 *
 * Nothing here is a fixed list of *allowed* things: [CapabilityKind] classifies a capability so the
 * planner and the UI can reason about it, and the naming convention below lets a new capability be
 * registered by adding a probe - not by editing this file.
 *
 * Names are namespaced `kind:name` (`bin:pm`, `svc:input`, `fs:sdcard_write`), so a capability
 * declared tomorrow by a provider nobody has written yet still lands in the right kind:
 *
 *  * `bin:` - a program on the target's `PATH`;
 *  * `applet:` - a sub-command of a multiplexer (`toybox`, `busybox`);
 *  * `interp:` - an interpreter that can run a script;
 *  * `svc:` - an Android system service reachable through `service`/`cmd`;
 *  * `prop:` - a system property the build reports;
 *  * `fs:` - a filesystem path the target can read or write;
 *  * `priv:` - a privilege level or escalation tool;
 *  * `debug:` - a debugging capability (`ro.debuggable`, wireless-debugging port…);
 *  * `build:` - build/platform facts beyond the identity fields;
 *  * `pkg:` - a package-manager capability;
 *  * `net:` - a network arrangement that is *in effect* (Wi-Fi on, wireless debugging on,
 *    airplane mode off). A switch the phone reports as off is [CapabilityStatus.MISSING] with the
 *    reading in its detail, exactly like `debug:debuggable`, while a setting the build does not
 *    report at all stays absent (UNKNOWN) - "off" and "not supported" are different answers.
 */
enum class CapabilityKind {
    SHELL,
    BINARY,
    APPLET,
    INTERPRETER,
    SERVICE,
    PROPERTY,
    FILESYSTEM,
    PRIVILEGE,
    DEBUGGING,
    PACKAGE_MANAGER,
    PLATFORM,
    TRANSPORT,

    /** Reachability arrangements: whether the phone is on a network at all, and how it is debugged. */
    NETWORK,
    OTHER,
}

/**
 * The capability names the platform standardises on.
 *
 * These are the names recipes, providers and the planner refer to by constant. A device may report
 * many more than these (the probe adds whatever it finds); a name that is not here is still a
 * capability, it simply has no constant, and [CapabilityKinds.of] still classifies it.
 */
object CapabilityNames {
    const val SHELL = "shell"
    const val EXEC_OUT = "exec-out"
    const val SH = "sh"
    const val BASH = "bash"
    const val PYTHON = "python3"
    const val TOYBOX = "toybox"
    const val TOOLBOX = "toolbox"
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

    // The discovery conventions: a probe names what it finds `kind:name`, and everything downstream
    // reads the kind from the name. A provider that discovers a new class of capability adds a
    // prefix here (or its own) without touching the planner.
    const val BINARY_PREFIX = "bin:"
    const val APPLET_PREFIX = "applet:"
    const val INTERPRETER_PREFIX = "interp:"
    const val SERVICE_PREFIX = "svc:"
    const val CMD_SERVICE_PREFIX = "cmd:"
    const val PROPERTY_PREFIX = "prop:"
    const val FILESYSTEM_PREFIX = "fs:"
    const val PRIVILEGE_PREFIX = "priv:"
    const val DEBUGGING_PREFIX = "debug:"
    const val BUILD_PREFIX = "build:"
    const val PACKAGE_PREFIX = "pkg:"

    /** The network arrangements the probe reads off the device's own settings. */
    const val NETWORK_PREFIX = "net:"
    const val WIFI = "net:wifi"
    const val WIRELESS_DEBUGGING = "net:adb_wifi"
    const val AIRPLANE_MODE = "net:airplane"

    /** Path lookup for a program (`wm` → `bin:wm`). */
    fun binary(program: String): String = BINARY_PREFIX + program

    /** An interpreter entry (`python3` → `interp:python3`). */
    fun interpreter(program: String): String = INTERPRETER_PREFIX + program

    /** A `toybox` sub-command (`ps` → `applet:ps`). */
    fun applet(applet: String): String = APPLET_PREFIX + applet

    /** An Android system service (`input` → `svc:input`). */
    fun service(service: String): String = SERVICE_PREFIX + service

    /** A `cmd` service (`package` → `cmd:package`). */
    fun cmdService(service: String): String = CMD_SERVICE_PREFIX + service

    /** A filesystem probe (`sdcard_write` → `fs:sdcard_write`). */
    fun filesystem(probe: String): String = FILESYSTEM_PREFIX + probe

    /** A debugging fact (`debuggable` → `debug:debuggable`). */
    fun debugging(probe: String): String = DEBUGGING_PREFIX + probe

    /** A network arrangement (`wifi` → `net:wifi`). */
    fun network(fact: String): String = NETWORK_PREFIX + fact

    /** A build fact (`fingerprint` → `build:fingerprint`). */
    fun build(probe: String): String = BUILD_PREFIX + probe

    /** The two readers and one writer that always exist when a shell answers at all. */
    val derived: Set<String> = setOf(SHELL, PACKAGE_MANAGER, SYNC)
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
    val kind: CapabilityKind = CapabilityKinds.of(name),
) {
    val available: Boolean get() = status == CapabilityStatus.AVAILABLE

    val missing: Boolean get() = status == CapabilityStatus.MISSING

    /** True when nothing measured it: attempt it, but never claim it works. */
    val unproven: Boolean get() = status == CapabilityStatus.UNKNOWN
}

/** Classifies a capability name. The prefix is the convention; anything unrecognised is [CapabilityKind.OTHER]. */
object CapabilityKinds {
    private val PREFIXED: List<Pair<String, CapabilityKind>> =
        listOf(
            CapabilityNames.BINARY_PREFIX to CapabilityKind.BINARY,
            CapabilityNames.APPLET_PREFIX to CapabilityKind.APPLET,
            CapabilityNames.INTERPRETER_PREFIX to CapabilityKind.INTERPRETER,
            CapabilityNames.SERVICE_PREFIX to CapabilityKind.SERVICE,
            CapabilityNames.CMD_SERVICE_PREFIX to CapabilityKind.SERVICE,
            CapabilityNames.PROPERTY_PREFIX to CapabilityKind.PROPERTY,
            CapabilityNames.FILESYSTEM_PREFIX to CapabilityKind.FILESYSTEM,
            CapabilityNames.PRIVILEGE_PREFIX to CapabilityKind.PRIVILEGE,
            CapabilityNames.DEBUGGING_PREFIX to CapabilityKind.DEBUGGING,
            CapabilityNames.BUILD_PREFIX to CapabilityKind.PLATFORM,
            CapabilityNames.PACKAGE_PREFIX to CapabilityKind.PACKAGE_MANAGER,
            CapabilityNames.NETWORK_PREFIX to CapabilityKind.NETWORK,
        )

    private val KNOWN: Map<String, CapabilityKind> =
        mapOf(
            CapabilityNames.SHELL to CapabilityKind.SHELL,
            CapabilityNames.EXEC_OUT to CapabilityKind.TRANSPORT,
            CapabilityNames.SYNC to CapabilityKind.TRANSPORT,
            CapabilityNames.INSTALL to CapabilityKind.TRANSPORT,
            CapabilityNames.PACKAGE_MANAGER to CapabilityKind.PACKAGE_MANAGER,
        )

    fun of(name: String): CapabilityKind {
        // An alias names the same fact as its canonical name (`pm` is `bin:pm`), so it classifies the
        // same way - a caller that still speaks the older name does not get a different answer.
        val canonical = CapabilityAliases.canonical(name)
        return KNOWN[canonical]
            ?: PREFIXED.firstOrNull { (prefix, _) -> canonical.startsWith(prefix) }?.second
            ?: CapabilityKind.OTHER
    }
}

/**
 * A capability the caller requires before a step is worth attempting.
 *
 * [alternatives] are names that would do as well (`applet:ps` for `bin:ps`), which is how a recipe
 * says "any of these" without the planner knowing what a `toybox` is.
 */
data class CapabilityRequirement(
    val name: String,
    val optional: Boolean = false,
    val alternatives: List<String> = emptyList(),
    val reason: String = "",
) {
    /** Every name that satisfies this requirement, the preferred one first. */
    val names: List<String> get() = listOf(name) + alternatives

    companion object {
        fun of(name: String): CapabilityRequirement = CapabilityRequirement(name)

        /** A requirement met by any of [names]. */
        fun any(
            vararg names: String,
            reason: String = "",
        ): CapabilityRequirement {
            require(names.isNotEmpty()) { "a requirement needs at least one name" }
            return CapabilityRequirement(name = names.first(), alternatives = names.drop(1), reason = reason)
        }

        /** A capability that makes a step better but never blocks it. */
        fun optional(name: String): CapabilityRequirement = CapabilityRequirement(name, optional = true)
    }
}

/** What a [CapabilityReport] says about a set of requirements. */
data class CapabilityCheck(
    /** Every requirement is available (or optional). */
    val satisfied: Boolean,
    /** Requirements the target *reported* as absent - the only honest blocker. */
    val missing: List<CapabilityRequirement> = emptyList(),
    /** Requirements nothing measured: attempt them, but the plan says so. */
    val unproven: List<CapabilityRequirement> = emptyList(),
) {
    val blocked: Boolean get() = missing.isNotEmpty()

    fun reason(): String =
        when {
            missing.isNotEmpty() -> "missing: " + missing.joinToString { it.name }
            unproven.isNotEmpty() -> "unmeasured: " + unproven.joinToString { it.name }
            else -> "every required capability is available"
        }
}

/** What changed between two reports of the same target (a re-probe after the user changed something). */
data class CapabilityDelta(
    val added: List<Capability>,
    val removed: List<Capability>,
    val changed: List<Capability>,
) {
    val empty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/**
 * What a target can actually do - the answer to "does this phone have `pm`, `python3`, `su`?".
 *
 * Two rules make this type honest, and both are load-bearing:
 *  * a capability the probe never saw is [CapabilityStatus.UNKNOWN], never MISSING - "we did not
 *    measure it" and "the device does not have it" are different facts, and only the second blocks;
 *  * the report is additive: [merge] keeps what a previous probe learned, so an announcement that
 *    only carries an address cannot erase a measured capability.
 */
class CapabilityReport(private val byName: Map<String, Capability>) {
    /** Every capability the target reported, in a stable order. */
    val all: List<Capability> get() = byName.values.sortedBy { it.name }

    val names: Set<String> get() = byName.keys

    val kinds: Set<CapabilityKind> get() = byName.values.map { it.kind }.toSet()

    fun capabilitiesOfKind(kind: CapabilityKind): List<Capability> = all.filter { it.kind == kind }

    /** The status of [name]; a legacy alias (`pm`) resolves to the canonical capability (`bin:pm`). */
    fun status(name: String): CapabilityStatus =
        byName[CapabilityAliases.canonical(name)]?.status ?: CapabilityStatus.UNKNOWN

    fun detail(name: String): String = byName[CapabilityAliases.canonical(name)]?.detail.orEmpty()

    fun has(name: String): Boolean = status(name) == CapabilityStatus.AVAILABLE

    fun missing(name: String): Boolean = status(name) == CapabilityStatus.MISSING

    fun unproven(name: String): Boolean = status(name) == CapabilityStatus.UNKNOWN

    fun with(capability: Capability): CapabilityReport =
        CapabilityReport(byName + (CapabilityAliases.canonical(capability.name) to capability))

    fun without(name: String): CapabilityReport = CapabilityReport(byName - name)

    /** This report, with [other]'s measurements on top of it (the newer probe wins per name). */
    fun merge(other: CapabilityReport): CapabilityReport = CapabilityReport(byName + other.byName)

    /** What [other] says that this report did not: what a re-probe learned. */
    fun diff(other: CapabilityReport): CapabilityDelta {
        val added = other.all.filter { it.name !in byName }
        val removed = all.filter { it.name !in other.byName }
        val changed =
            all.filter { mine ->
                val theirs = other.byName[mine.name] ?: return@filter false
                theirs.status != mine.status || theirs.detail != mine.detail
            }.map { mine -> other.byName.getValue(mine.name) }
        return CapabilityDelta(added = added, removed = removed, changed = changed)
    }

    /** Best-effort check across a set of requirements. Never treats UNKNOWN as available *or* as absent. */
    fun check(requirements: Iterable<CapabilityRequirement>): CapabilityCheck {
        fun measured(name: String): CapabilityStatus = byName[CapabilityAliases.canonical(name)]?.status ?: CapabilityStatus.UNKNOWN
        val missing =
            requirements.filter { requirement ->
                !requirement.optional &&
                    requirement.names.none { measured(it) == CapabilityStatus.AVAILABLE } &&
                    requirement.names.any { measured(it) == CapabilityStatus.MISSING }
            }
        val unproven =
            requirements.filter { requirement ->
                !requirement.optional &&
                    requirement.names.none { measured(it) == CapabilityStatus.AVAILABLE } &&
                    requirement.names.none { measured(it) == CapabilityStatus.MISSING }
            }
        return CapabilityCheck(satisfied = missing.isEmpty(), missing = missing, unproven = unproven)
    }

    /**
     * The names a step would use, preferring one the target measured as available.
     *
     * This is what turns "any of `bin:ps` or `applet:ps`" into the command that actually runs, and why
     * the planner can be capability-aware without knowing a single command.
     */
    fun preferredName(requirement: CapabilityRequirement): String? =
        requirement.names.firstOrNull { has(it) } ?: requirement.names.firstOrNull { unproven(it) }

    /** Everything available, as `name = detail`, for the agent and the audit trail. */
    fun toMap(): Map<String, String> = all.associate { it.name to (it.detail.takeIf { detail -> detail.isNotBlank() } ?: it.status.name) }

    /** A one-line summary a screen or a tool result can print. */
    fun summary(): String {
        val available = all.count { it.available }
        val missing = all.count { it.missing }
        val unknown = all.count { it.unproven }
        return "$available available, $missing missing, $unknown unmeasured"
    }

    companion object {
        /** No probe ran (or the target refused): everything is unknown, nothing is promised. */
        fun unknown(): CapabilityReport = CapabilityReport(emptyMap())

        fun of(capabilities: Iterable<Capability>): CapabilityReport =
            CapabilityReport(capabilities.associateBy { it.name })

        /**
         * Reads a stored map back.
         *
         * The map is what `PeerDevice.capabilities` holds and what a settings row persists, so it must
         * round-trip: a name whose value is blank was measured as absent, anything else was measured as
         * present (the value is the detail: a path, a version, `yes`).
         */
        fun fromMap(values: Map<String, String>): CapabilityReport =
            CapabilityReport(
                values.map { (name, value) ->
                    val canonicalName = CapabilityAliases.canonical(name)
                    if (value.isBlank()) {
                        missing(canonicalName)
                    } else {
                        available(canonicalName, value)
                    }
                }.associateBy { it.name },
            )

        fun available(
            name: String,
            detail: String = "",
        ): Capability = Capability(name, CapabilityStatus.AVAILABLE, detail)

        fun missing(
            name: String,
            detail: String = "",
        ): Capability = Capability(name, CapabilityStatus.MISSING, detail)

        /** A capability nobody measured: it neither blocks a step nor promises one. */
        fun unmeasured(
            name: String,
            detail: String = "",
        ): Capability = Capability(name, CapabilityStatus.UNKNOWN, detail)
    }
}
