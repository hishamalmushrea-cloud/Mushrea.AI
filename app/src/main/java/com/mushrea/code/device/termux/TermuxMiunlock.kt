package com.mushrea.code.device.termux

/**
 * The "mitool" wrapper the owner asked for, built on the tool that actually exists and matches
 * the documented flow: [termux-miunlock](https://github.com/RohitVerma882/termux-miunlock) run
 * inside the user's Termux.
 *
 * What it does and does not do:
 * - **does** check the prerequisites (`git`, `python3`, `termux-usb`, the checkout itself),
 *   install/update that one checkout, and print its `--help`/`--version` so the user can see the
 *   tool they are about to run;
 * - **does not** fetch an unlock token on its own, does not touch the Mi account, and does not run
 *   `stage`/`oem unlock` — those are refused by [TermuxCommandPolicy] until the confirmation phase.
 *
 * `termux-fastboot` (nohajc/termux-adb) is the piece that gives non-root USB access. It is a
 * third-party binary the user installs themselves; when it is missing this wrapper says so and
 * points at the project instead of silently installing a random package.
 *
 * Every composed script line passes [TermuxCommandPolicy.checkScript] before anything is sent.
 */
class TermuxMiunlock(private val bridge: TermuxBridge) {
    data class Step(
        val name: String,
        val ok: Boolean,
        val detail: String,
    )

    data class Report(
        val title: String,
        val steps: List<Step>,
        val output: String,
    ) {
        val ok: Boolean
            get() = steps.all { it.ok }
    }

    /** Checks every prerequisite without installing or changing anything. */
    suspend fun status(): Report {
        val steps = mutableListOf<Step>()
        val status = bridge.status()
        steps += Step("Termux", status.termuxInstalled, status.termuxVersionName ?: "package $TERMUX_PACKAGE not found")
        status.missing.forEach { steps += Step("prerequisite", false, it) }
        if (!status.ready) {
            steps += Step("bridge", false, "Termux is not ready yet — fix the prerequisite(s) above, then retry")
            return Report("Termux bridge status", steps, "")
        }
        val probe = bridge.probe()
        steps += Step("bridge round-trip", probe.ok, probe.summary())
        if (!probe.ok) {
            steps +=
                Step(
                    "allow-external-apps",
                    false,
                    "Termux did not answer. Set allow-external-apps=true in ~/.termux/termux.properties " +
                        "(then run termux-reload-settings) and retry.",
                )
        }
        val output = StringBuilder(probe.stdout.trim())
        for (tool in TOOL_PROBES) {
            val result = runScript(listOf("command -v $tool"))
            steps += Step(tool, result.exitCode == 0, result.stdout.trim().ifBlank { "not installed" })
            output.appendLine(result.stdout.trim())
        }
        val checkout = runScript(listOf("test -f $CHECKOUT_TEST"))
        steps +=
            Step(
                "termux-miunlock checkout",
                checkout.exitCode == 0,
                if (checkout.exitCode == 0) CHECKOUT_DIR else "not installed yet — run the install step",
            )
        return Report("Termux bridge status", steps, output.toString())
    }

    /** Installs or updates the termux-miunlock checkout and the packages it needs. */
    suspend fun install(): Report {
        val steps = mutableListOf<Step>()
        val output = StringBuilder()
        val status = bridge.status()
        if (!status.ready) {
            return Report(
                "termux-miunlock install",
                listOf(Step("bridge", false, "Termux is not ready: " + status.missing.joinToString("; "))),
                "",
            )
        }
        val packages = runScript(listOf("pkg install -y git python3 termux-api", "mkdir -p $MUSHREA_DIR"))
        output.appendLine(packages.stdout.trim()).appendLine(packages.stderr.trim())
        steps += Step("packages", packages.ok, packages.summary())
        if (!packages.ok) return Report("termux-miunlock install", steps, output.toString())

        val pull = runScript(listOf("git -C $CHECKOUT_DIR pull --ff-only"))
        output.appendLine(pull.stdout.trim()).appendLine(pull.stderr.trim())
        if (pull.ok) {
            steps += Step("checkout", true, "updated $CHECKOUT_DIR")
        } else {
            val clone = runScript(listOf("git clone --depth 1 $REPO_URL $CHECKOUT_DIR"))
            output.appendLine(clone.stdout.trim()).appendLine(clone.stderr.trim())
            steps += Step("checkout", clone.ok, clone.summary())
        }
        val verify = runScript(listOf("test -f $CHECKOUT_TEST"))
        steps +=
            Step(
                "get_token.sh present",
                verify.exitCode == 0,
                if (verify.exitCode == 0) "ok" else "the checkout does not contain get_token.sh",
            )
        val fastboot = runScript(listOf("command -v termux-fastboot"))
        val fastbootDetail =
            if (fastboot.exitCode == 0) {
                Step("termux-fastboot", true, fastboot.stdout.trim())
            } else {
                Step(
                    "termux-fastboot",
                    false,
                    "not installed — USB access from Termux comes from nohajc/termux-adb " +
                        "(https://github.com/nohajc/termux-adb); install it in Termux, then retry",
                )
            }
        steps += fastbootDetail
        return Report("termux-miunlock install", steps, output.toString())
    }

    /** Prints the tool's own help and version, without touching the device. */
    suspend fun help(): Report {
        val present = runScript(listOf("test -f $CHECKOUT_TEST"))
        if (present.exitCode != 0) {
            return Report(
                "termux-miunlock help",
                listOf(Step("checkout", false, "not installed yet — run the install step first")),
                "",
            )
        }
        val steps = mutableListOf<Step>()
        val output = StringBuilder()
        for (flag in listOf("--version", "--help")) {
            // The script is started from its own directory so the argument never needs shell
            // expansion: `bash ./get_token.sh <flag>` with the workdir set to the checkout.
            val result =
                bridge.run(
                    commandPath = "\$PREFIX/bin/bash",
                    arguments = listOf("./${CHECKOUT_SCRIPT_NAME}", flag),
                    workdir = CHECKOUT_WORKDIR,
                    label = "termux-miunlock $flag",
                )
            output.appendLine("\$ ./$CHECKOUT_SCRIPT_NAME $flag").appendLine(result.stdout.trim()).appendLine(result.stderr.trim())
            steps += Step(flag, result.ok, result.summary())
        }
        return Report("termux-miunlock help", steps, output.toString())
    }

    /** Policy-checks a composed script, then runs it in Termux's bash. */
    private suspend fun runScript(lines: List<String>): TermuxBridge.RunResult {
        val decision = TermuxCommandPolicy.checkScript(lines)
        if (!decision.allowed) {
            return TermuxBridge.RunResult(lines.joinToString("; "), null, "", "", decision.reason, timedOut = false)
        }
        return bridge.run("\$PREFIX/bin/bash", listOf("-c", lines.joinToString("\n")))
    }

    companion object {
        const val TERMUX_PACKAGE = TermuxBridge.TERMUX_PACKAGE
        const val REPO_URL = "https://github.com/RohitVerma882/termux-miunlock"
        const val MUSHREA_DIR = "\$HOME/.mushrea"
        const val CHECKOUT_DIR = "\$HOME/.mushrea/termux-miunlock"
        const val CHECKOUT_TEST = "$CHECKOUT_DIR/get_token.sh"
        const val CHECKOUT_WORKDIR = "~/.mushrea/termux-miunlock"
        const val CHECKOUT_SCRIPT_NAME = "get_token.sh"

        /** Tools the flow needs; `termux-fastboot` is installed by the user from its own project. */
        private val TOOL_PROBES = listOf("git", "python3", "termux-usb", "termux-fastboot")
    }
}
