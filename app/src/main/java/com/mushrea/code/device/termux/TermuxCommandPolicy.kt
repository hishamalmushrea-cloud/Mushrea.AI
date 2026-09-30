package com.mushrea.code.device.termux

/**
 * The gate every command has to pass before it reaches the host's Termux.
 *
 * Deliberately **default-deny**: only the read-only fastboot/verification commands the on-device
 * flashing workflow needs are allowed, and everything else is refused *with a stated reason* so
 * the agent (and the user reading the log) learns why instead of seeing a silent no-op.
 *
 * This build implements the read + verify half only. The destructive half the owner asked for —
 * `stage`, `oem unlock`, `flash`, `erase`, `lock` — is intentionally refused here rather than
 * merely hidden in the UI: those commands are wired in a later phase behind the typed
 * confirmation, the mandatory dry-run and the exportable audit log, and until that exists no code
 * path may call them. See docs/ON_DEVICE_AGENT.md ("phases").
 *
 * MTK tokens: `oem get_token` is the MediaTek token path. The owner's device (`sky`) is a
 * Qualcomm Snapdragon 4 Gen 2, and mixing MTK tooling into a Qualcomm unlock is exactly the kind
 * of mismatch that bricks a phone, so every `oem ...` command is refused outright for now.
 */
object TermuxCommandPolicy {
    /** Read-only fastboot subcommands this build may run. */
    private val READ_ONLY_SUBCOMMANDS = setOf("devices", "getvar", "help")

    /** Safe, non-modifying fastboot subcommands. */
    private val BENIGN_SUBCOMMANDS = setOf("--version", "-v", "--help", "-h", "reboot", "reboot-bootloader")

    /**
     * The destructive set the next phase implements. Listed explicitly (never as a pattern) so an
     * unexpected new subcommand falls through to "not allowlisted" instead of matching a rule.
     */
    val DESTRUCTIVE_SUBCOMMANDS =
        setOf("flash", "flashall", "erase", "format", "stage", "lock", "unlock", "update", "set_active")

    data class Decision(
        val allowed: Boolean,
        val reason: String,
    )

    /**
     * Classifies `termux-fastboot <arguments>`.
     *
     * @param arguments the fastboot arguments exactly as passed (no binary name).
     * @param deviceProduct the codename `fastboot getvar product` reported, when known; used only
     *   to explain the refusal text for vendor commands.
     */
    fun checkFastboot(
        arguments: List<String>,
        deviceProduct: String? = null,
    ): Decision {
        val cleaned = arguments.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return Decision(false, "refused: no fastboot arguments given")
        val subcommand = cleaned.first().lowercase()
        if (subcommand in BENIGN_SUBCOMMANDS) {
            return Decision(true, "allowed: \"$subcommand\" does not modify the device")
        }
        return when {
            subcommand == "oem" -> refuseVendorGroup(cleaned.drop(1), deviceProduct)
            subcommand in DESTRUCTIVE_SUBCOMMANDS ->
                Decision(
                    false,
                    "refused: \"$subcommand\" is a destructive command. This build is read/verify only; it " +
                        "runs after the typed confirmation plus dry-run phase is implemented and enabled.",
                )
            subcommand !in READ_ONLY_SUBCOMMANDS ->
                Decision(false, "refused: \"$subcommand\" is not in the read-only allowlist for this build")
            subcommand == "getvar" -> checkGetvar(cleaned.drop(1))
            else -> Decision(true, "allowed: read-only fastboot $subcommand")
        }
    }

    /**
     * Classifies a plain shell command run through the bridge. Only read/verify commands are
     * allowlisted, so `termux_run` can never install packages or run arbitrary code — the install
     * path lives in [TermuxMiunlock] behind its own confirmation.
     */
    fun checkShell(arguments: List<String>): Decision {
        val cleaned = arguments.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return Decision(false, "refused: no command given")
        val head = cleaned.first().substringAfterLast('/')
        val rest = cleaned.drop(1)
        return when (head) {
            "termux-fastboot" -> checkFastboot(rest)
            "termux-usb" -> if (rest == listOf("-l")) {
                Decision(true, "allowed: listing USB devices from Termux's side")
            } else {
                Decision(false, "refused: only \"termux-usb -l\" is allowlisted")
            }
            "getprop", "uname" -> Decision(true, "allowed: \"$head\" only reads system properties")
            "command" -> if (rest.firstOrNull() == "-v" && rest.size == 2) {
                Decision(true, "allowed: checking whether \"${rest[1]}\" is installed")
            } else {
                Decision(false, "refused: only \"command -v <name>\" is allowlisted")
            }
            "python3" -> if (rest == listOf("--version")) {
                Decision(true, "allowed: version probe")
            } else {
                Decision(false, "refused: only \"python3 --version\" is allowlisted")
            }
            else ->
                Decision(
                    false,
                    "refused: \"$head\" is not in the read-only allowlist for this build. Arbitrary shell " +
                        "execution and package installation stay disabled.",
                )
        }
    }

    /**
     * Verifies a composed helper script line by line before it is sent to Termux. Used by the
     * miunlock installer, which is the one place that legitimately needs `pkg` and `git`.
     */
    fun checkScript(lines: List<String>): Decision {
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (SCRIPT_METACHARACTERS.any { trimmed.contains(it) }) {
                return Decision(
                    false,
                    "refused: script line \"$trimmed\" uses shell chaining/redirection, which the installer never needs",
                )
            }
            val head = trimmed.substringBefore(' ')
            if (head !in SCRIPT_ALLOWLIST) {
                return Decision(false, "refused: script line \"$trimmed\" is not allowlisted")
            }
        }
        return Decision(true, "allowed: installer steps use only allowlisted commands")
    }

    /** True when [subcommand] is one of the commands the next phase must gate explicitly. */
    fun isDestructive(subcommand: String): Boolean {
        val key = subcommand.trim().lowercase()
        return key in DESTRUCTIVE_SUBCOMMANDS || key == "oem"
    }

    private fun refuseVendorGroup(
        rest: List<String>,
        deviceProduct: String?,
    ): Decision {
        val action = rest.firstOrNull()?.lowercase()
        val suffix =
            when {
                action == null -> ""
                action.startsWith("get_token") ->
                    " (MediaTek token path: rejected here — a Qualcomm device such as \"sky\" must use " +
                        "\"getvar token\" instead, and mixing the two is a known way to brick a phone)"
                else -> ""
            }
        val productHint =
            if (deviceProduct.isNullOrBlank()) {
                ""
            } else {
                " Device product: \"$deviceProduct\"."
            }
        return Decision(
            false,
            "refused: \"fastboot oem ...\" covers the vendor groups (unlock tokens included) and is " +
                "disabled in this build.$suffix$productHint",
        )
    }

    private fun checkGetvar(arguments: List<String>): Decision {
        val variable = arguments.firstOrNull()?.lowercase()
        if (variable.isNullOrBlank()) {
            return Decision(false, "refused: getvar without a variable name reads nothing")
        }
        if (variable == "token") {
            return Decision(
                true,
                "allowed: the unlock token is read-only device state. It is shown but never written to " +
                    "the activity log, the audit log, or anywhere off this phone",
            )
        }
        return Decision(true, "allowed: getvar $variable")
    }

    /** Command heads the installer script may use (one plain command per line). */
    private val SCRIPT_ALLOWLIST =
        setOf(
            "pkg",
            "git",
            "chmod",
            "command",
            "mkdir",
            "test",
            "python3",
            "termux-fastboot",
            "termux-usb",
            "termux-setup-storage",
        )

    /** Chaining/redirection is refused outright: the installer only ever runs plain commands. */
    private val SCRIPT_METACHARACTERS =
        listOf(";", "&&", "||", "|", "`", "\$(", ">", "<", "&")
}
