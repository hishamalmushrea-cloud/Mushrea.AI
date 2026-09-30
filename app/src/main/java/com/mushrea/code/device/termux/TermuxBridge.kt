package com.mushrea.code.device.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.mushrea.code.device.TermuxResultReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The host-side half of the Termux bridge.
 *
 * The app (or the agent, through it) sends a `com.termux.RUN_COMMAND` intent to the user's Termux;
 * Termux runs the command and — when the caller supplied a pending intent — sends stdout, stderr
 * and the exit code back. That is the only non-root way to reach real USB from Linux userspace on
 * Android: Termux's `termux-fastboot` (from nohajc/termux-adb) asks the user once per device for
 * the USB file descriptor and hands it to a patched fastboot.
 *
 * Everything here is optional by design: if Termux, Termux:API, the RUN_COMMAND permission or the
 * `allow-external-apps` property are missing, [status] says exactly which one, and callers degrade
 * to a plain explanation instead of pretending the tool exists. Nothing is installed or enabled
 * behind the user's back — see [TermuxMiunlock] for the one installer, and it asks first.
 *
 * This class never decides *what* may run: [TermuxCommandPolicy] does, and callers must apply it
 * before calling [run].
 */
class TermuxBridge(private val context: Context) {
    data class Status(
        val termuxInstalled: Boolean,
        val termuxVersionName: String?,
        val termuxApiAppInstalled: Boolean,
        val permissionGranted: Boolean,
        val missing: List<String>,
    ) {
        val ready: Boolean
            get() = termuxInstalled && permissionGranted
    }

    data class RunResult(
        val command: String,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val error: String?,
        val timedOut: Boolean,
    ) {
        val ok: Boolean
            get() = !timedOut && error == null && (exitCode == null || exitCode == 0)

        fun summary(): String =
            when {
                timedOut -> "Termux did not answer in time: $command"
                error != null -> "Termux refused: $error"
                exitCode == 0 -> "ran \"$command\" in Termux (exit 0)"
                else -> "ran \"$command\" in Termux (exit $exitCode)"
            }
    }

    fun status(): Status {
        val packages = context.packageManager
        val termuxInstalled = isInstalled(packages, TERMUX_PACKAGE)
        val apiInstalled = isInstalled(packages, TERMUX_API_PACKAGE)
        val permission =
            ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
        val missing = mutableListOf<String>()
        if (!termuxInstalled) missing += "Termux ($TERMUX_PACKAGE) is not installed"
        if (!permission) missing += "the \"$PERMISSION_RUN_COMMAND\" permission is not granted (Termux bridge: Settings → Apps → Mushrea Code → Additional permissions)"
        if (!apiInstalled) missing += "Termux:API ($TERMUX_API_PACKAGE) is not installed — USB access needs it"
        return Status(
            termuxInstalled = termuxInstalled,
            termuxVersionName = versionName(packages, TERMUX_PACKAGE),
            termuxApiAppInstalled = apiInstalled,
            permissionGranted = permission,
            missing = missing,
        )
    }

    /**
     * Runs one command in Termux and waits for its result.
     *
     * `background = true` is intentional: only background commands return stdout, stderr and the
     * exit code *separately*; foreground commands return a merged terminal transcript instead.
     */
    suspend fun run(
        commandPath: String,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        workdir: String = TERMUX_HOME,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        label: String = "Mushrea Code command",
    ): RunResult {
        val executionId = EXECUTION_IDS.incrementAndGet()
        val deferred = CompletableDeferred<Bundle>()
        waiters[executionId] = deferred
        val printable = (listOf(commandPath) + arguments).joinToString(" ")
        try {
            val intent =
                Intent()
                    .setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                    .setAction(ACTION_RUN_COMMAND)
                    .putExtra(EXTRA_COMMAND_PATH, commandPath)
                    .putExtra(EXTRA_ARGUMENTS, arguments.toTypedArray())
                    .putExtra(EXTRA_WORKDIR, workdir)
                    .putExtra(EXTRA_BACKGROUND, true)
                    .putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)
                    .putExtra(EXTRA_SESSION_ACTION, "0")
                    .putExtra(EXTRA_COMMAND_LABEL, label)
                    .putExtra(EXTRA_PENDING_INTENT, resultIntent(executionId))
            if (!stdin.isNullOrEmpty()) intent.putExtra(EXTRA_STDIN, stdin)
            startTermuxService(intent)
            val bundle = withTimeout(timeoutMillis) { deferred.await() }
            return parseResult(printable, bundle)
        } catch (timeout: TimeoutCancellationException) {
            return RunResult(printable, null, "", "", null, timedOut = true)
        } catch (error: Exception) {
            return RunResult(printable, null, "", "", error.message ?: "Termux refused the command", timedOut = false)
        } finally {
            waiters.remove(executionId)
        }
    }

    /**
     * The cheapest possible round-trip that proves the whole chain works: Termux installed, the
     * permission granted, and `allow-external-apps=true` so Termux actually accepts external
     * commands. A refusal there produces no result at all, which is why this is a probe with its
     * own short timeout rather than something inferred from the package list.
     */
    suspend fun probe(): RunResult =
        run(
            commandPath = "\$PREFIX/bin/echo",
            arguments = listOf(PROBE_TOKEN),
            timeoutMillis = PROBE_TIMEOUT_MILLIS,
            label = "Mushrea Code bridge probe",
        )

    private fun resultIntent(executionId: Int): PendingIntent {
        val intent = Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_EXECUTION_ID, executionId)
        val flags = PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, executionId, intent, flags)
    }

    /**
     * Android 8+ restricts starting another app's service from the background; Termux accepts the
     * intent either way once it is running. We try the plain start first (the documented path),
     * then the foreground-service start, and let the caller report a failure honestly.
     */
    private fun startTermuxService(intent: Intent) {
        val started = runCatching { context.startService(intent) }.isSuccess
        if (started) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            throw IllegalStateException("could not hand the command to Termux — open Termux once, then retry")
        }
    }

    private fun parseResult(
        command: String,
        bundle: Bundle,
    ): RunResult {
        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(RESULT_STDERR).orEmpty()
        val exitCode = if (bundle.containsKey(RESULT_EXIT_CODE)) bundle.getInt(RESULT_EXIT_CODE) else null
        val err = if (bundle.containsKey(RESULT_ERR)) bundle.getInt(RESULT_ERR) else 0
        val errmsg = bundle.getString(RESULT_ERRMSG)
        val error = if (err != 0) "Termux internal error $err${errmsg?.let { ": $it" } ?: ""}" else null
        return RunResult(
            command = command,
            exitCode = exitCode,
            stdout = stdout.take(MAX_OUTPUT_CHARS),
            stderr = stderr.take(MAX_OUTPUT_CHARS),
            error = error,
            timedOut = false,
        )
    }

    private fun isInstalled(
        packages: PackageManager,
        packageName: String,
    ): Boolean =
        runCatching { packages.getPackageInfo(packageName, 0) }.isSuccess

    private fun versionName(
        packages: PackageManager,
        packageName: String,
    ): String? =
        runCatching {
            val info = packages.getPackageInfo(packageName, 0)
            info.versionName
        }.getOrNull()

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_API_PACKAGE = "com.termux.api"
        /** `~/` is expanded by Termux itself (the wiki-documented prefix) — `$HOME` is not. */
        const val TERMUX_HOME = "~/"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val RUNNER_APP_SHELL = "app_shell"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        // Extra keys are the literal values of TermuxConstants (termux-shared) and must not be
        // "tidied": Termux matches them exactly.
        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        /** Result bundle + keys inside it (`TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_*`). */
        const val RESULT_BUNDLE = "result"
        const val RESULT_STDOUT = "stdout"
        const val RESULT_STDERR = "stderr"
        const val RESULT_EXIT_CODE = "exitCode"
        const val RESULT_ERR = "err"
        const val RESULT_ERRMSG = "errmsg"

        const val EXTRA_EXECUTION_ID = "com.mushrea.code.termux.execution_id"
        const val PROBE_TOKEN = "mushrea-bridge-ok"

        private const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        private const val PROBE_TIMEOUT_MILLIS = 20_000L
        private const val MAX_OUTPUT_CHARS = 24_000
        private val EXECUTION_IDS = AtomicInteger(1000)

        /** execution id → the coroutine waiting for that command's result. */
        private val waiters = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

        /** Called by [TermuxResultReceiver] when Termux sends a result back. */
        fun deliver(
            executionId: Int,
            bundle: Bundle,
        ) {
            waiters[executionId]?.complete(bundle)
        }
    }
}
