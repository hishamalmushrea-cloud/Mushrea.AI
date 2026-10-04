package com.mushrea.code.device.bridge

import com.mushrea.code.runtime.local.AdbShellRunner
import com.mushrea.code.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one adb invocation on the IO dispatcher.
 *
 * [AdbShellRunner.runShell] is a *blocking* call: it starts proot, waits for the process and reads its
 * output file - seconds of wall time for a single command. The bridge is called from the agent's
 * coroutines (and, for the QR screen, from a lifecycle scope whose context may be the main
 * dispatcher), so every path that reaches a shell goes through here rather than trusting the caller's
 * context. It is an extension on purpose: the call sites then read the same as the blocking version,
 * and a future provider cannot forget the hop.
 */
internal suspend fun AdbShellRunner.runShellOnIo(
    command: String,
    timeoutSeconds: Long,
): LocalRuntimeCommandResult = withContext(Dispatchers.IO) { runShell(command, timeoutSeconds) }
