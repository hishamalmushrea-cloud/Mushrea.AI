package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.Capability
import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.core.peer.PeerIdentity
import com.mushrea.code.runtime.local.AdbShellRunner

/**
 * What one probe learned about a peer: what it is, what it can do, and whether it reports exit codes.
 *
 * [reachable] is separate from the identity fields on purpose. A device can answer `getprop` (so the
 * channel works) while saying nothing useful about itself, and the caller has to be able to tell that
 * apart from "no channel at all".
 */
data class PeerProbeReport(
    val identity: PeerIdentity = PeerIdentity(),
    val capabilities: CapabilityReport = CapabilityReport.unknown(),
    val exitCodeSupport: Boolean = false,
    val output: String = "",
) {
    val reachable: Boolean get() = capabilities.has(CapabilityNames.SHELL)
}

/**
 * Asks a peer device what it can actually do.
 *
 * Android devices are not interchangeable, and the differences that matter are exactly the ones an
 * agent would otherwise assume away: `cmd` is missing on older builds, `uiautomator` is absent from
 * some OEM images, `python3` only exists if something shipped it, `toybox` applets differ per vendor,
 * and `su` is a rooted phone rather than a program on PATH. So instead of a hardcoded "ADB can do X",
 * the platform runs one batched probe and records the answer per device.
 *
 * The probe is one round trip: a shell script that prints `bin:<program>=<path>` (one loop over a
 * *long* list, not a handful of names), the applet list of the multiplexer, the services `cmd -l`
 * and `service list` expose, property/build/debugging facts, filesystem write probes and package
 * manager behaviour. It is generated as *device* shell code (the local runtime shell must not expand
 * it - see [AdbCommandLine.shell]), and it is parsed by a pure function, so the parsing is tested
 * against real device output without a device.
 *
 * Nothing downstream assumes a fixed set: the parser publishes `kind:name` capabilities, and a
 * capability the probe did not look for stays [CapabilityStatus.UNKNOWN] rather than missing.
 */
class PeerCapabilityDiscovery(private val runner: AdbShellRunner) {
    /** Runs the batched probe and the exit-code check. */
    suspend fun probe(
        serial: String,
        timeoutSeconds: Long = PROBE_TIMEOUT_SECONDS,
    ): PeerProbeReport {
        val output = runCatching { runner.runShellOnIo(AdbCommandLine.shell(serial, PeerCapabilityScript.script()), timeoutSeconds) }
        val text = output.getOrNull()?.output.orEmpty()
        val exitCodes = runCatching { exitCodeSupport(serial) }.getOrDefault(false)
        // `parse` reads the capability lines and `identity` the property lines out of the same output;
        // the report is assembled here so a device that answered nothing still comes back as an
        // explicit "unreachable" instead of an empty capability map that reads like a healthy phone.
        return PeerProbeReport(
            identity = PeerCapabilityScript.identity(text),
            capabilities = PeerCapabilityScript.parse(text),
            exitCodeSupport = exitCodes,
            output = text,
        )
    }

    /**
     * Whether the device's shell reports exit codes (`adb shell 'exit 7'` → exit code 7).
     *
     * This decides whether a command's failure can be *detected* at all: on a device where the code
     * is lost, a command that fails looks exactly like one that succeeded, so the platform has to
     * verify the effect instead of trusting the channel. It is measured, never assumed.
     */
    suspend fun exitCodeSupport(
        serial: String,
        timeoutSeconds: Long = EXIT_CODE_TIMEOUT_SECONDS,
    ): Boolean {
        val result = runner.runShellOnIo(AdbCommandLine.shell(serial, EXIT_CODE_PROBE_SCRIPT), timeoutSeconds)
        return result.exitCode == EXIT_CODE_PROBE_VALUE
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 60L
        const val EXIT_CODE_TIMEOUT_SECONDS = 20L
        const val EXIT_CODE_PROBE_VALUE = 7
        const val EXIT_CODE_PROBE_SCRIPT = "exit 7"
    }
}

/** The probe text and its parser, split out so both are unit-testable. */
object PeerCapabilityScript {
    /**
     * The programs the probe looks for on `PATH`.
     *
     * The first entries are the ones earlier releases published by their plain name (`pm`, `screencap`);
     * [com.mushrea.code.core.execution.CapabilityAliases] still reads such a stored map. The rest is
     * the wider set the recipes and the agent can reach for: text tools, archives, interpreters,
     * network tools and Android-specific entry points. Looking for a program costs one builtin call on
     * the device, so the list is generous on purpose - a capability nobody probes is a capability the
     * platform has to guess about.
     */
    val BINARIES: List<String> =
        listOf(
            // Android entry points.
            "sh",
            "toybox",
            "toolbox",
            "cmd",
            "pm",
            "am",
            "monkey",
            "dumpsys",
            "settings",
            "logcat",
            "screencap",
            "screenrecord",
            "input",
            "uiautomator",
            "wm",
            "svc",
            "service",
            "getprop",
            "setprop",
            "id",
            "whoami",
            "run-as",
            // The escalation program itself: `su` is a binary like any other, and the platform has to be
            // able to say "this phone has no `su`" as a measurement rather than as an assumption.
            "su",
            "content",
            "app_process",
            "dalvikvm",
            // Shells and interpreters.
            "bash",
            "ash",
            "mksh",
            "busybox",
            "python",
            "python3",
            "perl",
            "ruby",
            "node",
            "java",
            // Core text and file tools (many are toybox applets on modern builds).
            "cat",
            "ls",
            "rm",
            "cp",
            "mv",
            "mkdir",
            "touch",
            "echo",
            "grep",
            "egrep",
            "sed",
            "awk",
            "sort",
            "head",
            "tail",
            "wc",
            "cut",
            "tr",
            "find",
            "xargs",
            "date",
            "sleep",
            "kill",
            "df",
            "du",
            "ps",
            "top",
            // Archives, encodings and network tools.
            "tar",
            "gzip",
            "gunzip",
            "zip",
            "unzip",
            "base64",
            "md5sum",
            "sha1sum",
            "sha256sum",
            "xxd",
            "od",
            "printf",
            "timeout",
            "ip",
            "ifconfig",
            "netstat",
            "ping",
            "curl",
            "wget",
            "nc",
            "sqlite3",
            "openssl",
        )

    /** Interpreters that can run a script; each one present becomes an `interp:` capability. */
    val INTERPRETERS: List<String> = listOf("sh", "bash", "ash", "mksh", "busybox", "python", "python3", "perl", "ruby", "node")

    /** The properties that identify the device (read by [identity]). */
    private val PROPS =
        listOf(
            "ro.product.model",
            "ro.product.manufacturer",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.product.cpu.abi",
        )

    /** Extra build facts, published as `build:` capabilities. */
    private val BUILD_PROPS =
        listOf(
            "build:fingerprint" to "ro.build.fingerprint",
            "build:id" to "ro.build.id",
            "build:tags" to "ro.build.tags",
            "build:type" to "ro.build.type",
            "build:abilist" to "ro.product.cpu.abilist",
            "build:hardware" to "ro.hardware",
            "build:board" to "ro.product.board",
            "build:brand" to "ro.product.brand",
        )

    /** Debugging facts, published as `debug:` capabilities. */
    private val DEBUG_PROPS =
        listOf(
            "debug:debuggable" to "ro.debuggable",
            "debug:secure" to "ro.secure",
            "debug:tls_port" to "service.adb.tls.port",
            "debug:tcp_port" to "service.adb.tcp.port",
            "debug:sdk_release" to "ro.build.version.sdk",
        )

    /** Filesystem facts: measured by asking the device whether the path is readable/writable. */
    private val FS_PROBES =
        listOf(
            "fs:sdcard_write" to "-w /sdcard",
            "fs:sdcard_read" to "-r /sdcard",
            "fs:data_local_tmp_write" to "-w /data/local/tmp",
            "fs:proc_read" to "-r /proc/self/status",
            "fs:system_read" to "-r /system",
        )

    /** `toybox` applets worth recording; the block it prints is filtered through this set. */
    private val APPLETS_OF_INTEREST =
        setOf(
            "ls",
            "cat",
            "cp",
            "mv",
            "rm",
            "mkdir",
            "rmdir",
            "ln",
            "chmod",
            "chown",
            "touch",
            "stat",
            "find",
            "xargs",
            "grep",
            "sed",
            "awk",
            "sort",
            "uniq",
            "head",
            "tail",
            "wc",
            "cut",
            "tr",
            "date",
            "sleep",
            "kill",
            "ps",
            "top",
            "df",
            "du",
            "id",
            "whoami",
            "getenforce",
            "setenforce",
            "mount",
            "umount",
            "dmesg",
            "log",
            "tar",
            "gzip",
            "gunzip",
            "zcat",
            "base64",
            "md5sum",
            "sha1sum",
            "sha256sum",
            "xxd",
            "od",
            "printf",
            "timeout",
            "sync",
            "netstat",
            "ifconfig",
            "wget",
            "ping",
            "nproc",
            "uname",
            "uptime",
            "free",
            "watch",
            "lsof",
            "readlink",
            "realpath",
            "mktemp",
            "split",
            "tac",
            "seq",
            "expr",
            "env",
            "printenv",
            "clear",
            "tty",
            "truncate",
        )

    private const val APPLET_BLOCK_START = "applet-list-start"
    private const val APPLET_BLOCK_END = "applet-list-end"
    private const val CMD_BLOCK_START = "cmd-list-start"
    private const val CMD_BLOCK_END = "cmd-list-end"
    private const val SVC_BLOCK_START = "svc-list-start"
    private const val SVC_BLOCK_END = "svc-list-end"
    private const val VALUE_MARKER = "marker=probe-done"
    private const val SERVICE_LINE = "^(\\s*\\d+\\s+)?([A-Za-z][A-Za-z0-9_.]*):"

    /** The device-side script. No single quotes anywhere, so the caller can wrap it in one. */
    fun script(): String =
        buildString {
            // The script travels inside one single-quoted argument ([AdbCommandLine.shell]), so it
            // must not contain a single quote of its own; the local shell expands nothing either, which
            // is the point - `$(...)` has to run on the other phone.
            append("for b in ")
            append(BINARIES.joinToString(" "))
            append("; do echo \"bin:\$b=\$(command -v \$b 2>/dev/null)\"; done; ")
            PROPS.forEach { prop -> append("echo \"prop:$prop=\$(getprop $prop 2>/dev/null)\"; ") }
            BUILD_PROPS.forEach { (label, prop) -> append("echo \"$label=\$(getprop $prop 2>/dev/null)\"; ") }
            DEBUG_PROPS.forEach { (label, prop) -> append("echo \"$label=\$(getprop $prop 2>/dev/null)\"; ") }
            FS_PROBES.forEach { (label, test) -> append("echo \"$label=\$( [ $test ] && echo yes )\"; ") }
            append("echo \"priv:id=\$(id 2>/dev/null)\"; ")
            append("echo \"priv:su=\$(command -v su 2>/dev/null)\"; ")
            append("echo \"pkg:pm_list=\$(pm list packages >/dev/null 2>&1 && echo yes)\"; ")
            append("echo \"pkg:cmd_package=\$(cmd package list packages >/dev/null 2>&1 && echo yes)\"; ")
            append("echo $APPLET_BLOCK_START; toybox 2>&1; echo $APPLET_BLOCK_END; ")
            append("echo $CMD_BLOCK_START; cmd -l 2>&1; echo $CMD_BLOCK_END; ")
            append("echo $SVC_BLOCK_START; service list 2>&1; echo $SVC_BLOCK_END; ")
            append("echo \"$VALUE_MARKER\"")
        }

    /**
     * Parses the probe output.
     *
     * A line the script did not print is ignored; a line it printed with an empty value is a
     * measurement of *absence* ([CapabilityStatus.MISSING]), while everything the parse never saw stays
     * unknown. That difference is what lets the planner say "this phone has no `python3`, use `sh`"
     * instead of "unknown, try anyway".
     *
     * Names are canonical (`bin:pm`, `applet:ls`, `svc:input`, `cmd:package`, `interp:python3`), with
     * the transport-level facts (`shell`, `sync`, `install`, `exec-out`, `package-manager`) left
     * unprefixed because they describe adb's channel, not a program.
     */
    fun parse(output: String): CapabilityReport {
        val text = output.replace("\r\n", "\n")
        val capabilities = mutableListOf<Capability>()
        var answered = false
        BINARIES.forEach { binary ->
            val marker = "bin:$binary="
            if (!text.contains(marker)) return@forEach
            answered = true
            val path = value(text, marker)
            capabilities.add(
                if (path.isBlank()) {
                    CapabilityReport.missing(CapabilityNames.binary(binary), "not on this device")
                } else {
                    CapabilityReport.available(CapabilityNames.binary(binary), path)
                },
            )
        }
        if (!answered) return CapabilityReport.unknown()
        // The probe ran through the device's shell, so `sh` exists whatever the lookup printed, and
        // adb's own channel capabilities hold because the round trip happened at all.
        capabilities.add(CapabilityReport.available(CapabilityNames.SHELL, "shell answered the probe"))
        capabilities.add(CapabilityReport.available(CapabilityNames.SYNC, "adbd answered"))
        capabilities.add(CapabilityReport.available(CapabilityNames.INSTALL, "adb install goes through adbd"))
        capabilities.add(CapabilityReport.available(CapabilityNames.EXEC_OUT, "adb exec-out is a host-side feature"))
        INTERPRETERS.forEach { interpreter ->
            val binaryName = CapabilityNames.binary(interpreter)
            val measured = capabilities.firstOrNull { it.name == binaryName && it.status == CapabilityStatus.AVAILABLE }
            if (measured != null) {
                capabilities.add(CapabilityReport.available(CapabilityNames.interpreter(interpreter), measured.detail))
            }
        }
        PROPS.forEach { prop ->
            val propValue = value(text, "prop:$prop=")
            if (text.contains("prop:$prop=")) {
                capabilities.add(
                    if (propValue.isBlank()) {
                        CapabilityReport.missing(CapabilityNames.build(prop.substringAfterLast('.')), "the build does not report it")
                    } else {
                        CapabilityReport.available(CapabilityNames.build(prop.substringAfterLast('.')), propValue)
                    },
                )
            }
        }
        BUILD_PROPS.forEach { (label, _) -> capabilities.addIfMeasured(text, label, detail = "") }
        DEBUG_PROPS.forEach { (label, _) -> capabilities.addIfMeasured(text, label, detail = "") }
        FS_PROBES.forEach { (label, _) -> capabilities.addIfMeasured(text, label, detail = "asked the device") }
        PRIVILEGE_PROBES.forEach { label -> capabilities.addIfMeasured(text, label, detail = "") }
        PACKAGE_PROBES.forEach { label -> capabilities.addIfMeasured(text, label, detail = "ran it on the device") }
        if (capabilities.hasAvailable(CapabilityNames.binary("toybox"))) {
            block(text, APPLET_BLOCK_START, APPLET_BLOCK_END)
                .flatMap { line -> line.split(' ', '\t') }
                .map { name -> name.trim().lowercase() }
                .filter { name -> name in APPLETS_OF_INTEREST }
                .distinct()
                .forEach { applet -> capabilities.add(CapabilityReport.available(CapabilityNames.applet(applet), "toybox applet")) }
        }
        if (capabilities.hasAvailable(CapabilityNames.binary("cmd"))) {
            block(text, CMD_BLOCK_START, CMD_BLOCK_END)
                .mapNotNull { line -> serviceName(line) }
                .distinct()
                .forEach { service ->
                    capabilities.add(CapabilityReport.available(CapabilityNames.cmdService(service), "listed by cmd -l"))
                }
        }
        if (capabilities.hasAvailable(CapabilityNames.binary("service"))) {
            block(text, SVC_BLOCK_START, SVC_BLOCK_END)
                .mapNotNull { line -> serviceName(line) }
                .distinct()
                .forEach { service ->
                    capabilities.add(CapabilityReport.available(CapabilityNames.service(service), "listed by service list"))
                }
        }
        val packageManager = capabilities.firstOrNull { it.name == CapabilityNames.binary("pm") }
        capabilities.add(
            if (packageManager?.status == CapabilityStatus.AVAILABLE) {
                CapabilityReport.available(CapabilityNames.PACKAGE_MANAGER, "pm is present")
            } else {
                CapabilityReport.missing(CapabilityNames.PACKAGE_MANAGER, "pm is not on this device")
            },
        )
        return CapabilityReport.of(capabilities)
    }

    /** The device identity from the same output. */
    fun identity(output: String): PeerIdentity {
        val text = output.replace("\r\n", "\n")
        return PeerIdentity(
            model = value(text, "prop:ro.product.model="),
            manufacturer = value(text, "prop:ro.product.manufacturer="),
            androidVersion = value(text, "prop:ro.build.version.release="),
            sdk = value(text, "prop:ro.build.version.sdk=").toIntOrNull() ?: 0,
            abi = value(text, "prop:ro.product.cpu.abi="),
        )
    }

    /** `yes` is a measurement of presence; a blank value (or `0`) is a measurement of absence. */
    private fun MutableList<Capability>.addIfMeasured(
        text: String,
        label: String,
        detail: String,
    ) {
        val marker = "$label="
        if (!text.contains(marker)) return
        val raw = value(text, marker)
        add(
            if (raw.isNotBlank() && raw != "0") {
                CapabilityReport.available(label, raw)
            } else {
                CapabilityReport.missing(label, detail.ifBlank { "the device reported it as off" })
            },
        )
    }

    /** Whether the device measured [name] as present, before the list becomes a report. */
    private fun List<Capability>.hasAvailable(name: String): Boolean =
        any { capability -> capability.name == name && capability.available }

    /** The lines strictly between two marker lines, header and footer excluded. */
    private fun block(
        text: String,
        start: String,
        end: String,
    ): List<String> {
        val lines = text.lines()
        val from = lines.indexOfFirst { it.trim() == start }
        if (from < 0) return emptyList()
        val to = lines.indexOfFirst { it.trim() == end }
        if (to <= from) return emptyList()
        return lines.subList(from + 1, to)
    }

    /**
     * The service name in a `cmd -l` line (a bare name), a `service list` line
     * (`0\tpackage: [android.content.pm.IPackageManager]`) or a `cmd -l` footer, when there is one.
     */
    private fun serviceName(line: String): String? {
        val trimmed = line.trim()
        val named = Regex(SERVICE_LINE).find(trimmed)?.groupValues?.get(2)?.lowercase()
        val bare = trimmed.lowercase().takeIf { name -> SERVICE_NAME_REGEX.matches(name) }
        return (named ?: bare)?.takeIf { name -> name.length in 2..48 && name !in SERVICE_STOP_WORDS }
    }

    private fun value(
        text: String,
        key: String,
    ): String =
        text
            .lineSequence()
            .firstOrNull { it.startsWith(key) }
            ?.removePrefix(key)
            ?.trim()
            .orEmpty()

    private const val SERVICE_NAME = "[a-z][a-z0-9_.]{1,47}"

    private val SERVICE_NAME_REGEX = Regex(SERVICE_NAME)

    /** Words a `cmd -l` failure or usage line produces; they are not services. */
    private val SERVICE_STOP_WORDS = setOf("usage", "error", "unknown", "command", "abort", "killed", "not", "found")

    private val PRIVILEGE_PROBES = listOf("priv:id", "priv:su")
    private val PACKAGE_PROBES = listOf("pkg:pm_list", "pkg:cmd_package")
}
