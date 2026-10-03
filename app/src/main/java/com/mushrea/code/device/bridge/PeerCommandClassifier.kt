package com.mushrea.code.device.bridge

import com.mushrea.code.core.permission.PermissionRisk

/**
 * What a command line would do to the other phone, as far as can be told by reading it.
 *
 * Four classes, not a list of allowed commands. The point is that the platform must never be the
 * thing that decides "this ADB capability is not in my table" - the device and the agent decide what
 * to run. What the platform *does* decide is how much friction a command needs, and that has to come
 * from the command itself:
 *
 *  * [READ_ONLY] - observes (`getprop`, `pm list packages`, `logcat -d`, `dumpsys`). Nothing changes
 *    on the other phone, so this may run without asking.
 *  * [STATE_CHANGING] - writes on the device (`input tap`, `settings put`, `mkdir`, `screencap -p` to
 *    a file, an unclassified program). Reversible in principle, so it needs a confirmation.
 *  * [DESTRUCTIVE] - removes or overwrites user data (`pm clear`, `rm -rf`, `uninstall`, `format`),
 *    or reboots. Needs the strong confirmation.
 *  * [PRIVILEGED] - tries to become root or weaken the device's own protection (`su`, `setenforce`,
 *    `mount -o remount`). Needs the strong confirmation and is recorded as such.
 *
 * Anything unrecognised is [STATE_CHANGING], never [READ_ONLY]: an unknown command cannot become
 * automatic, which is the same fail-closed rule the Permission Center applies to an unknown
 * operation.
 */
enum class PeerCommandClass {
    READ_ONLY,
    STATE_CHANGING,
    DESTRUCTIVE,
    PRIVILEGED,
    ;

    val risk: PermissionRisk
        get() =
            when (this) {
                READ_ONLY -> PermissionRisk.LOW
                STATE_CHANGING -> PermissionRisk.MEDIUM
                DESTRUCTIVE, PRIVILEGED -> PermissionRisk.HIGH
            }

    val mutatesTarget: Boolean get() = this != READ_ONLY

    /** Friction the class demands; the policy may raise it, never lower it. */
    val requiresStrongConfirmation: Boolean get() = this == DESTRUCTIVE || this == PRIVILEGED
}

/** The verdict plus the rule that produced it, so a refusal can explain itself. */
data class PeerCommandVerdict(
    val commandClass: PeerCommandClass,
    val program: String,
    val rule: String,
) {
    val risk: PermissionRisk get() = commandClass.risk

    val mutatesTarget: Boolean get() = commandClass.mutatesTarget

    val destructive: Boolean get() = commandClass.requiresStrongConfirmation
}

/**
 * Classifies a shell line or an argv by looking at every segment it would run.
 *
 * The line is split on the shell's own separators (`;`, `&&`, `||`, `|`, newline) and each segment's
 * first word is inspected, because `pm list packages; pm uninstall x` is two operations and only the
 * *most dangerous* one may decide the friction. A `su -c …` wrapper is privileged whatever it wraps.
 */
object PeerCommandClassifier {
    private val SEPARATORS = Regex("(?:&&|\\|\\||;|\\||\\n)")

    /** Programs that only read; anything not here is treated as writing unless it matches below. */
    private val READ_ONLY_PROGRAMS =
        setOf(
            "getprop",
            "ls",
            "cat",
            "df",
            "du",
            "id",
            "ps",
            "top",
            "uname",
            "date",
            "uptime",
            "whoami",
            "env",
            "printenv",
            "wc",
            "head",
            "tail",
            "grep",
            "find",
            "stat",
            "md5sum",
            "sha256sum",
            "dumpsys",
            "logcat",
            "lsof",
            "netstat",
            "ip",
            "ifconfig",
            "mount",
            "free",
            "vmstat",
            "which",
            "type",
            "test",
            "[",
            "echo",
            "printf",
            "true",
            "false",
            "sleep",
            "hostname",
        )

    /**
     * Read-only *sub-commands* of programs that can also write.
     *
     * `pm` is not read-only (`pm uninstall` is a wipe) and neither is `am`, so they cannot be in the
     * program set above - but refusing to list installed packages in Read-Only mode would be wrong
     * too. These prefixes are the well-known readers of Android's own tools, and a program that still
     * matches [DESTRUCTIVE_MARKERS] or [STATE_CHANGING_PREFIXES] is caught before this list is
     * consulted.
     */
    private val READ_ONLY_PREFIXES =
        listOf(
            "pm list",
            "pm path",
            "pm dump",
            "pm resolve-activity",
            "cmd package list",
            "cmd package path",
            "settings get",
            "am get-",
        )

    /** First-word prefixes that mean "this writes", even though the program itself is harmless. */
    private val STATE_CHANGING_PREFIXES =
        listOf(
            "pm install",
            "pm enable",
            "pm disable",
            "pm grant",
            "pm revoke",
            "pm set-",
            "pm compile",
            "am start",
            "am force-stop",
            "am broadcast",
            "am kill",
            "am stopservice",
            "am startservice",
            "cmd ",
            "settings put",
            "settings delete",
            "svc ",
            "input ",
            "wm ",
            "screencap",
            "screenrecord",
            "mkdir",
            "touch",
            "cp",
            "mv",
            "chmod",
            "chown",
            "ln",
            "dd ",
            "curl ",
            "wget ",
            "run-as",
        )

    /** Substrings that mean data loss or a device restart wherever they appear. */
    private val DESTRUCTIVE_MARKERS =
        listOf(
            "pm uninstall",
            "pm clear",
            "uninstall ",
            "rm -rf",
            "rm -r ",
            "rm -f",
            " mkfs",
            "format ",
            "wipe",
            "reboot",
            "shutdown",
            "poweroff",
            "halt",
            "am force-stop",
            "stop ",
            "fastboot",
            "pm disable-user",
            "cmd package uninstall",
            "recovery --wipe_data",
        )

    /** Markers of an attempt to gain root or weaken a protection. */
    private val PRIVILEGED_MARKERS =
        listOf(
            "su ",
            "su -c",
            "/su",
            "magisk",
            "setenforce",
            "mount -o remount",
            "mount -o rw",
            "chmod 777 /",
            "insmod",
            "rmmod",
            "adb root",
            "adb remount",
        )

    fun classify(line: String): PeerCommandVerdict {
        val normalized = line.trim()
        if (normalized.isBlank()) {
            return PeerCommandVerdict(PeerCommandClass.STATE_CHANGING, program = "", rule = "empty command")
        }
        val lowered = " $normalized "
        val privileged = PRIVILEGED_MARKERS.firstOrNull(lowered::contains)
        val destructive = DESTRUCTIVE_MARKERS.firstOrNull(lowered::contains)
        val segments = normalized.split(SEPARATORS).map(String::trim).filter(String::isNotEmpty)
        val program = segments.firstOrNull()?.let(::firstWord).orEmpty()
        val writing =
            segments.firstOrNull { segment ->
                val body = segment.lowercase()
                STATE_CHANGING_PREFIXES.any(body::startsWith)
            }
        return when {
            privileged != null ->
                PeerCommandVerdict(PeerCommandClass.PRIVILEGED, program, "matches privileged '$privileged'")
            destructive != null ->
                PeerCommandVerdict(PeerCommandClass.DESTRUCTIVE, program, "matches destructive '$destructive'")
            segments.isEmpty() ->
                PeerCommandVerdict(PeerCommandClass.STATE_CHANGING, program, "no executable segment")
            writing != null ->
                PeerCommandVerdict(PeerCommandClass.STATE_CHANGING, program, "starts with a writing command")
            segments.all(::isReadOnlySegment) ->
                PeerCommandVerdict(PeerCommandClass.READ_ONLY, program, "every segment only reads")
            else ->
                PeerCommandVerdict(PeerCommandClass.STATE_CHANGING, program, "not a known read-only program")
        }
    }

    /** Classifies a program plus argv, the shape an [com.mushrea.code.core.execution.ExecutionOperation.EXEC]
     * request uses. */
    fun classify(
        program: String,
        arguments: List<String>,
    ): PeerCommandVerdict = classify((listOf(program) + arguments).joinToString(" "))

    private fun isReadOnlySegment(segment: String): Boolean {
        val body = segment.trim().lowercase()
        if (firstWord(body) in READ_ONLY_PROGRAMS) return true
        return READ_ONLY_PREFIXES.any(body::startsWith)
    }

    private fun firstWord(segment: String): String = segment.trim().substringBefore(' ').substringAfterLast('/')
}
