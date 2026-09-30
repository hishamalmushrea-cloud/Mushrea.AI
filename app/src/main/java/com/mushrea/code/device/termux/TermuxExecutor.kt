package com.mushrea.code.device.termux

import android.content.Context
import com.mushrea.code.device.usb.AdbException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent-facing half of the Termux bridge: the `termux_*` actions other than the status probe
 * delegate here. Everything it runs goes through [TermuxCommandPolicy] first, and a refusal is
 * returned as a structured result with the reason — the agent gets an explanation, not a stack
 * trace, and the user sees the same sentence.
 *
 * `termux-fastboot` is the fastboot the bridge uses. Stock Termux `fastboot` from `android-tools`
 * cannot see USB devices without root, so pointing the tool at it would produce empty results and
 * a lie; the pinned binary from nohajc/termux-adb takes the USB file descriptor from `termux-usb`
 * instead. Its absence is reported as a prerequisite to install, never worked around.
 */
class TermuxExecutor(private val context: Context) {
    private val bridge = TermuxBridge(context)
    private val miunlock = TermuxMiunlock(bridge)

    fun executeStatus(): JSONObject.() -> Unit {
        val status = bridge.status()
        return {
            put("termux_installed", status.termuxInstalled)
            status.termuxVersionName?.let { put("termux_version", it) }
            put("termux_api_installed", status.termuxApiAppInstalled)
            put("permission_granted", status.permissionGranted)
            put("missing", JSONArray(status.missing))
            put("ready", status.ready)
            put(
                "summary",
                if (status.ready) {
                    "Termux ${status.termuxVersionName ?: ""} is installed and the RUN_COMMAND permission is granted" +
                        if (status.termuxApiAppInstalled) "" else "; Termux:API is missing — USB access needs it"
                } else {
                    "the Termux bridge is not usable yet: " + status.missing.joinToString("; ")
                },
            )
        }
    }

    suspend fun executeRun(params: JSONObject): JSONObject.() -> Unit {
        val command = params.optString("command").trim().ifBlank { throw AdbException("command is required") }
        val arguments = params.stringList("args")
        val joined = (listOf(command) + arguments).joinToString(" ")
        if (!isSafe(joined)) return refusal("refused: the command contains characters outside the allowlisted set")
        val decision =
            if (command == TERMUX_FASTBOOT || command.endsWith("/$TERMUX_FASTBOOT")) {
                TermuxCommandPolicy.checkFastboot(arguments, params.optString("device_product").ifBlank { null })
            } else {
                TermuxCommandPolicy.checkShell(listOf(command) + arguments)
            }
        if (!decision.allowed) return refusal(decision.reason)
        val result = bridge.run("$PREFIX/bin/$command", arguments)
        return runResult(joined, result)
    }

    suspend fun executeFastbootRun(params: JSONObject): JSONObject.() -> Unit {
        val arguments = params.stringList("args")
        if (arguments.isEmpty()) throw AdbException("args are required (for example [\"getvar\", \"product\"])")
        if (!isSafe(arguments.joinToString(" "))) return refusal("refused: an argument contains characters outside the allowlisted set")
        val decision = TermuxCommandPolicy.checkFastboot(arguments, params.optString("device_product").ifBlank { null })
        if (!decision.allowed) return refusal(decision.reason)
        val result = bridge.run("$PREFIX/bin/$TERMUX_FASTBOOT", arguments)
        return runResult("$TERMUX_FASTBOOT ${arguments.joinToString(" ")}", result)
    }

    suspend fun executeMitool(params: JSONObject): JSONObject.() -> Unit {
        val report =
            when (params.optString("step", "status").lowercase()) {
                "install" -> miunlock.install()
                "help" -> miunlock.help()
                else -> miunlock.status()
            }
        return {
            put("title", report.title)
            put("ok", report.ok)
            put(
                "steps",
                JSONArray().apply {
                    report.steps.forEach { step ->
                        put(JSONObject().put("name", step.name).put("ok", step.ok).put("detail", step.detail))
                    }
                },
            )
            put("output", report.output)
            put(
                "summary",
                if (report.ok) {
                    "${report.title}: all ${report.steps.size} step(s) passed"
                } else {
                    "${report.title}: " + report.steps.filterNot { it.ok }.joinToString("; ") { "${it.name}: ${it.detail}" }
                },
            )
        }
    }

    private fun runResult(
        label: String,
        result: TermuxBridge.RunResult,
    ): JSONObject.() -> Unit =
        {
            put("command", result.command)
            put("exit_code", result.exitCode ?: JSONObject.NULL)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            result.error?.let { put("error", it) }
            if (result.timedOut) put("timed_out", true)
            put(
                "summary",
                when {
                    result.timedOut -> "$label timed out in Termux — check Termux (and its USB prompt) on the phone"
                    result.error != null -> "$label was refused by Termux: ${result.error}"
                    label.contains(TOKEN_HINT, ignoreCase = true) ->
                        "$label ran (exit ${result.exitCode}) — the token itself is in this result only; it is never logged or stored"
                    else -> "$label ran in Termux (exit ${result.exitCode})"
                },
            )
        }

    private fun refusal(reason: String): JSONObject.() -> Unit =
        {
            put("allowed", false)
            put("reason", reason)
            put("summary", reason)
        }

    /** Conservative argument shape: no shell metacharacters can survive a re-join this way. */
    private fun isSafe(text: String): Boolean = text.length <= MAX_COMMAND_CHARS && SAFE_ARGUMENTS.matches(text)

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
    }

    private companion object {
        const val TERMUX_FASTBOOT = "termux-fastboot"
        const val PREFIX = "\$PREFIX"
        const val TOKEN_HINT = "token"
        const val MAX_COMMAND_CHARS = 600

        val SAFE_ARGUMENTS = Regex("^[A-Za-z0-9_./=:@+ -]+$")
    }
}
