package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.ExecutionEffect
import com.mushrea.code.core.execution.ExecutionFile
import com.mushrea.code.core.execution.ExecutionInvocation
import com.mushrea.code.core.execution.ExecutionOperation
import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.execution.ExecutionStage
import com.mushrea.code.core.execution.ExecutionTarget
import com.mushrea.code.core.execution.ExecutionTransport
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.runtime.local.AdbShellRunner
import com.mushrea.code.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provider is the only place a peer command becomes an `adb` line and a runner result. */
class PeerAdbProviderTest {
    private val serial = "adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp"

    private fun request(
        operation: ExecutionOperation,
        invocation: ExecutionInvocation,
        effect: ExecutionEffect = ExecutionEffect(mutatesTarget = true, risk = PermissionRisk.MEDIUM),
    ): ExecutionRequest =
        ExecutionRequest(
            operation = operation,
            target = ExecutionTarget(id = serial, transport = ExecutionTransport.PEER_ADB, label = "Pixel 6a"),
            invocation = invocation,
            effect = effect,
        )

    private fun provider(result: LocalRuntimeCommandResult): PeerAdbProvider =
        PeerAdbProvider(AdbShellRunner { _, _ -> result })

    @Test
    fun `a shell line keeps its device and its quoting`() {
        val line =
            PeerAdbProvider(AdbShellRunner { _, _ -> LocalRuntimeCommandResult(0, "") })
                .commandFor(serial, request(ExecutionOperation.SHELL, ExecutionInvocation("pm list packages -3")))

        assertEquals("adb -s '$serial' 'shell' 'pm list packages -3'", line)
    }

    @Test
    fun `an exec argv is quoted argument by argument`() {
        val line =
            PeerAdbProvider(AdbShellRunner { _, _ -> LocalRuntimeCommandResult(0, "") })
                .commandFor(
                    serial,
                    request(
                        ExecutionOperation.EXEC,
                        ExecutionInvocation("id", listOf("-u")),
                        effect = ExecutionEffect(mutatesTarget = false, risk = PermissionRisk.LOW),
                    ),
                )

        // The argv is quoted once for adb's local shell and travels as one argument, so the device's
        // shell receives `id -u` exactly: no word splitting, no expansion, no injection.
        assertTrue(line.startsWith("adb -s '$serial' 'exec-out' "))
        assertTrue(line.contains("'id'"))
        assertTrue(line.contains("'-u'"))
    }

    @Test
    fun `a script travels as a heredoc with a quoted delimiter`() {
        val line =
            PeerAdbProvider(AdbShellRunner { _, _ -> LocalRuntimeCommandResult(0, "") })
                .commandFor(
                    serial,
                    request(
                        ExecutionOperation.SCRIPT,
                        ExecutionInvocation("echo \$HOME", interpreter = "sh"),
                    ),
                )

        assertTrue(line.startsWith("adb -s '$serial' 'shell' 'sh -s <<'MUSHREA_EOF_"))
        assertTrue(line.contains("echo \$HOME\nMUSHREA_EOF_"))
    }

    @Test
    fun `file operations address the device and both paths`() {
        val provider = PeerAdbProvider(AdbShellRunner { _, _ -> LocalRuntimeCommandResult(0, "") })
        val files = listOf(ExecutionFile(localPath = "/tmp/app.apk", remotePath = "/sdcard/app.apk"))

        assertEquals(
            "adb -s '$serial' 'push' '/tmp/app.apk' '/sdcard/app.apk'",
            provider.commandFor(serial, request(ExecutionOperation.PUSH, ExecutionInvocation("", files = files))),
        )
        assertEquals(
            "adb -s '$serial' 'pull' '/sdcard/app.apk' '/tmp/app.apk'",
            provider.commandFor(serial, request(ExecutionOperation.PULL, ExecutionInvocation("", files = files))),
        )
        assertEquals(
            "adb -s '$serial' 'install' '-r' '/tmp/app.apk'",
            provider.commandFor(serial, request(ExecutionOperation.INSTALL, ExecutionInvocation("", files = files))),
        )
    }

    @Test
    fun `a file operation without a file is refused instead of running half a command`() {
        val provider = PeerAdbProvider(AdbShellRunner { _, _ -> LocalRuntimeCommandResult(0, "") })

        val thrown =
            runCatching {
                provider.commandFor(serial, request(ExecutionOperation.PUSH, ExecutionInvocation("")))
            }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `capabilities decide which operations are advertised`() {
        val provider = PeerAdbProvider(AdbShellRunner { _, _ -> LocalRuntimeCommandResult(0, "") })

        assertTrue(provider.supports(ExecutionOperation.SHELL))
        assertTrue(provider.supports(ExecutionOperation.SCRIPT))
        assertTrue(provider.supports(ExecutionOperation.INSTALL))
        assertTrue(provider.supports(ExecutionOperation.PROBE))
        assertEquals(setOf(CapabilityNames.EXEC_OUT), provider.requirements(ExecutionOperation.EXEC))
        assertEquals(setOf(CapabilityNames.SYNC), provider.requirements(ExecutionOperation.PULL))
        assertEquals(ExecutionOperation.SHELL, provider.alternative(ExecutionOperation.EXEC, CapabilityNames.EXEC_OUT))
        assertNull(provider.alternative(ExecutionOperation.INSTALL, CapabilityNames.SYNC))
    }

    @Test
    fun `a command that reports success is succeeded, never verified`() {
        val result =
            runBlocking {
                provider(LocalRuntimeCommandResult(0, "com.example.app")).execute(
                    request(
                        ExecutionOperation.SHELL,
                        ExecutionInvocation("pm list packages"),
                        effect = ExecutionEffect(mutatesTarget = false, risk = PermissionRisk.LOW),
                    ),
                )
            }

        assertEquals(ExecutionStage.SUCCEEDED, result.stage)
        assertTrue(result.ok)
        assertFalse("only a follow-up check may claim verification", result.verified)
        assertEquals(0, result.exitCode)
        assertEquals("com.example.app", result.stdout)
    }

    @Test
    fun `a failing command is a command failure with adb's own words`() {
        val result =
            runBlocking {
                provider(LocalRuntimeCommandResult(1, "adb: device offline")).execute(
                    request(
                        ExecutionOperation.SHELL,
                        ExecutionInvocation("id"),
                        effect = ExecutionEffect(mutatesTarget = false, risk = PermissionRisk.LOW),
                    ),
                )
            }

        assertEquals(ExecutionStage.COMMAND_FAILED, result.stage)
        assertFalse(result.ok)
        assertTrue(result.message.startsWith("${PeerAdbErrorCode.DEVICE_OFFLINE}"))
    }

    @Test
    fun `a request that under-declares itself is refused before it runs`() {
        var ran = false
        val provider = PeerAdbProvider(AdbShellRunner { _, _ -> ran = true; LocalRuntimeCommandResult(0, "") })

        val result =
            runBlocking {
                provider.execute(
                    request(
                        ExecutionOperation.SHELL,
                        ExecutionInvocation("rm -rf /sdcard/Download"),
                        effect = ExecutionEffect(mutatesTarget = false, risk = PermissionRisk.LOW),
                    ),
                )
            }

        assertEquals(ExecutionStage.REJECTED, result.stage)
        assertFalse("nothing may run when the declaration is a lie", ran)
        assertTrue(result.message.contains("changes the target"))
    }

    @Test
    fun `the same command declared honestly is allowed through to the runner`() {
        var ran = false
        val provider = PeerAdbProvider(AdbShellRunner { _, _ -> ran = true; LocalRuntimeCommandResult(0, "ok") })

        val result =
            runBlocking {
                provider.execute(
                    request(
                        ExecutionOperation.SHELL,
                        ExecutionInvocation("rm -rf /sdcard/Download"),
                        effect = ExecutionEffect(mutatesTarget = true, destructive = true, risk = PermissionRisk.HIGH),
                    ),
                )
            }

        assertTrue(ran)
        assertEquals(ExecutionStage.SUCCEEDED, result.stage)
    }

    @Test
    fun `an unavailable runtime is a transport failure, not an empty success`() {
        val provider = PeerAdbProvider(AdbShellRunner { _, _ -> throw IllegalStateException("the runtime is not installed") })

        val result =
            runBlocking {
                provider.execute(
                    request(
                        ExecutionOperation.SHELL,
                        ExecutionInvocation("id"),
                        effect = ExecutionEffect(mutatesTarget = false, risk = PermissionRisk.LOW),
                    ),
                )
            }

        assertEquals(ExecutionStage.TRANSPORT_FAILED, result.stage)
        assertEquals(PeerAdbErrorCode.RUNTIME_UNAVAILABLE.name, result.errorCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `a plan-less capability report does not make the provider lie`() {
        val report: CapabilityReport = CapabilityReport.unknown()

        assertEquals(setOf<String>(), report.names)
        assertFalse(report.has(CapabilityNames.SHELL))
    }
}
