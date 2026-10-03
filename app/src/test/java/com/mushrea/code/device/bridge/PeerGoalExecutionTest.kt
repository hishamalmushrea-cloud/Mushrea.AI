package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.Capability
import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.ExecutionGoal
import com.mushrea.code.core.execution.ExecutionLog
import com.mushrea.code.core.execution.ExecutionOperation
import com.mushrea.code.core.execution.ExecutionPolicy
import com.mushrea.code.core.execution.ExecutionStage
import com.mushrea.code.core.execution.ExecutionTransport
import com.mushrea.code.core.peer.PeerDeviceState
import com.mushrea.code.core.peer.PeerIdentity
import com.mushrea.code.core.permission.PermissionSource
import com.mushrea.code.runtime.local.AdbShellRunner
import com.mushrea.code.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole path a **goal** takes, without a phone: plan against what the device reported, ask the gate
 * for every single request, run it through the provider, and fall back to the next route when a route
 * fails on the device.
 *
 * These tests are the safety story of the round in executable form: no request reaches a provider
 * without passing the gate (including the verification follow-up), a device that is not connected is
 * refused before anything runs, and a fallback is always explained.
 */
class PeerGoalExecutionTest {
    private val serial = "adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp"
    private val log = ExecutionLog()

    /** A runner that records every adb line and answers from a queue. */
    private class RecordingRunner(
        var answer: (String) -> LocalRuntimeCommandResult = { LocalRuntimeCommandResult(0, "") },
    ) : AdbShellRunner {
        val commands = mutableListOf<String>()

        override fun runShell(
            command: String,
            timeoutSeconds: Long,
        ): LocalRuntimeCommandResult {
            commands += command
            return answer(command)
        }
    }

    private object NoDiscovery : PeerServiceDiscovery {
        override fun browse(type: PeerAdbServiceType): Flow<PeerAdbService> = emptyFlow()
    }

    private fun registry(
        report: CapabilityReport,
        state: PeerDeviceState = PeerDeviceState.VERIFIED,
    ): PeerDeviceRegistry =
        PeerDeviceRegistry().apply {
            connected(serial, "192.168.1.20", 37123, PeerIdentity(model = "Pixel 6a"))
            capabilities(serial, report)
            state(serial, state)
        }

    private fun capabilitiesOf(vararg capabilities: Capability): CapabilityReport = CapabilityReport.of(capabilities.toList())

    private fun capableOfEverything(): CapabilityReport =
        capabilitiesOf(
            CapabilityReport.available(CapabilityNames.SHELL, "/system/bin/sh"),
            CapabilityReport.available(CapabilityNames.binary("pm"), "/system/bin/pm"),
            CapabilityReport.available(CapabilityNames.binary("cmd"), "/system/bin/cmd"),
            CapabilityReport.available(CapabilityNames.SYNC, "adbd answered"),
        )

    private fun bridge(
        runner: AdbShellRunner,
        registry: PeerDeviceRegistry,
        gate: PeerExecutionGate = PeerExecutionGate { _, _ -> null },
    ): PeerAdbBridge =
        PeerAdbBridge(
            session = PeerAdbSession(runner, NoDiscovery, registry),
            registry = registry,
            providers = listOf(PeerAdbProvider(runner)),
            log = log,
            gate = gate,
        )

    private fun goal(recipeId: String, parameters: Map<String, String> = emptyMap(), verify: Boolean = false) =
        ExecutionGoal(
            description = "",
            transport = ExecutionTransport.PEER_ADB,
            targetId = serial,
            recipeId = recipeId,
            parameters = parameters,
            verify = verify,
        )

    private val policy = ExecutionPolicy(requestedBy = PermissionSource.AGENT, reason = "test")

    @Test
    fun `a goal is planned from the phone's own report and run through the provider`() {
        val runner = RecordingRunner { LocalRuntimeCommandResult(0, "package:com.android.settings") }
        val gateCalls = mutableListOf<ExecutionOperation>()
        val peerBridge =
            bridge(runner, registry(capableOfEverything())) { request, _ ->
                gateCalls += request.operation
                null
            }

        val outcome = runBlocking { peerBridge.executeGoal(goal("packages.list"), policy) }

        assertTrue(outcome.succeeded)
        assertEquals("peer-adb", outcome.result?.providerId)
        assertEquals(serial, outcome.result?.targetId)
        assertEquals("bin:pm", outcome.result?.capability)
        assertEquals(listOf(ExecutionOperation.SHELL), gateCalls)
        assertTrue("the pm route ran", runner.commands.single().contains("pm list packages"))
        val record = log.recent().single()
        assertEquals("bin:pm", record.capability)
        assertEquals(ExecutionStage.SUCCEEDED, record.stage)
    }

    @Test
    fun `a phone without pm takes the cmd route and says why`() {
        val runner = RecordingRunner { LocalRuntimeCommandResult(0, "package:com.android.settings") }
        val capabilities =
            capabilitiesOf(
                CapabilityReport.available(CapabilityNames.SHELL, "/system/bin/sh"),
                CapabilityReport.available(CapabilityNames.binary("cmd"), "/system/bin/cmd"),
                CapabilityReport.missing(CapabilityNames.binary("pm"), "not on this device"),
            )
        val peerBridge = bridge(runner, registry(capabilities))

        val outcome = runBlocking { peerBridge.executeGoal(goal("packages.list"), policy) }

        assertTrue(outcome.succeeded)
        assertEquals("bin:cmd", outcome.result?.capability)
        assertTrue(runner.commands.single().contains("cmd package list packages"))
        assertTrue(outcome.result?.fallback.orEmpty().contains("bin:pm"))
        assertEquals("first route the planner chose", outcome.attempts.single().reason)
    }

    @Test
    fun `a route that fails on the device falls back to the next one, with the reason recorded`() {
        var call = 0
        val runner =
            RecordingRunner {
                call += 1
                if (call == 1) {
                    LocalRuntimeCommandResult(1, "adb: device offline")
                } else {
                    LocalRuntimeCommandResult(0, "package:com.android.settings")
                }
            }
        val peerBridge = bridge(runner, registry(capableOfEverything()))

        val outcome = runBlocking { peerBridge.executeGoal(goal("packages.list"), policy) }

        assertTrue(outcome.succeeded)
        assertEquals(2, runner.commands.size)
        assertEquals(2, outcome.attempts.size)
        assertEquals(ExecutionStage.COMMAND_FAILED, outcome.attempts.first().stage)
        assertEquals("pm-list", outcome.attempts.first().candidateId)
        assertTrue(outcome.result?.fallback.orEmpty().contains("pm-list"))
        assertEquals(2, log.size())
    }

    @Test
    fun `verification is a second request through the same gate`() {
        val runner = RecordingRunner { command ->
            if (command.contains("ls -d")) LocalRuntimeCommandResult(0, "/sdcard/newdir") else LocalRuntimeCommandResult(0, "")
        }
        val gateCalls = mutableListOf<ExecutionOperation>()
        val peerBridge =
            bridge(runner, registry(capableOfEverything())) { request, _ ->
                gateCalls += request.operation
                null
            }

        val outcome = runBlocking { peerBridge.executeGoal(goal("files.mkdir", mapOf("path" to "/sdcard/newdir"), verify = true), policy) }

        assertEquals(ExecutionStage.VERIFIED, outcome.result?.stage)
        assertTrue(outcome.verified)
        assertEquals(2, gateCalls.size)
        assertEquals(2, runner.commands.size)
        assertTrue(runner.commands.first().contains("mkdir -p"))
        assertTrue(runner.commands.last().contains("ls -d"))
    }

    @Test
    fun `a command that ran but was not confirmed is not reported as verified`() {
        val runner =
            RecordingRunner { command ->
                if (command.contains(
                        "ls -d",
                    )
                ) {
                    LocalRuntimeCommandResult(1, "ls: /sdcard/newdir: No such file")
                } else {
                    LocalRuntimeCommandResult(0, "")
                }
            }
        val peerBridge = bridge(runner, registry(capableOfEverything()))

        val outcome = runBlocking { peerBridge.executeGoal(goal("files.mkdir", mapOf("path" to "/sdcard/newdir"), verify = true), policy) }

        assertEquals(ExecutionStage.SUCCEEDED, outcome.result?.stage)
        assertFalse(outcome.verified)
        assertTrue(outcome.result?.failureReason.orEmpty().startsWith("unverified"))
        assertTrue("the command itself did run", outcome.succeeded)
    }

    @Test
    fun `a device that is not connected is refused before anything runs`() {
        val runner = RecordingRunner()
        val peerBridge = bridge(runner, registry(capableOfEverything(), state = PeerDeviceState.DISCONNECTED))

        val outcome = runBlocking { peerBridge.executeGoal(goal("packages.list"), policy) }

        assertFalse(outcome.succeeded)
        assertEquals(ExecutionStage.REJECTED, outcome.result?.stage)
        assertTrue(runner.commands.isEmpty())
        assertEquals(0, log.size())
    }

    @Test
    fun `a goal naming a recipe the platform does not have is refused with a way out`() {
        val runner = RecordingRunner()
        val peerBridge = bridge(runner, registry(capableOfEverything()))

        val outcome = runBlocking { peerBridge.executeGoal(goal("no.such.recipe"), policy) }

        assertFalse(outcome.succeeded)
        assertTrue(runner.commands.isEmpty())
        assertTrue(outcome.plan.blockers.single().hint.contains("shell.run"))
    }

    @Test
    fun `a gate refusal stops the route and is recorded`() {
        val runner = RecordingRunner()
        val peerBridge = bridge(runner, registry(capableOfEverything())) { _, _ -> "the user did not allow it" }

        val outcome = runBlocking { peerBridge.executeGoal(goal("packages.list"), policy) }

        assertFalse(outcome.succeeded)
        assertEquals(ExecutionStage.REJECTED, outcome.result?.stage)
        assertTrue(runner.commands.isEmpty())
        assertEquals("refused by policy", log.recent().last().verification)
    }
}
