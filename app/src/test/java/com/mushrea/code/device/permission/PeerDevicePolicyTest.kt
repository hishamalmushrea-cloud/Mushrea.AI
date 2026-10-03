package com.mushrea.code.device.permission

import com.mushrea.code.core.execution.ExecutionEffect
import com.mushrea.code.core.execution.ExecutionInvocation
import com.mushrea.code.core.execution.ExecutionOperation
import com.mushrea.code.core.execution.ExecutionPolicy
import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.execution.ExecutionTarget
import com.mushrea.code.core.execution.ExecutionTransport
import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionCenter
import com.mushrea.code.core.permission.PermissionDomain
import com.mushrea.code.core.permission.PermissionRequest
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peer policy is the answer to "may this run on the other phone?" without a command table.
 *
 * What it must never do: let an unconfirmed operation run because the caller said it was fine, or
 * let a *high-risk* one become quiet just because the user answered an earlier prompt.
 */
class PeerDevicePolicyTest {
    private val policy = PeerDevicePolicy()

    private fun exec(
        risk: PermissionRisk,
        mutates: Boolean,
        preAuthorized: Boolean = false,
    ): ExecutionRequest =
        ExecutionRequest(
            operation = ExecutionOperation.SHELL,
            target = ExecutionTarget(id = "serial-x", transport = ExecutionTransport.PEER_ADB, label = "Pixel 6a"),
            invocation = ExecutionInvocation("id"),
            effect = ExecutionEffect(mutatesTarget = mutates, risk = risk),
            policy =
                ExecutionPolicy(
                    requestedBy = PermissionSource.AGENT,
                    preAuthorized = preAuthorized,
                    reason = "test",
                ),
        )

    private fun decide(request: PermissionRequest) = PermissionCenter(listOf(policy, AlwaysAllow())).decide(request)

    @Test
    fun `the policy owns exactly the peer domain`() {
        assertEquals(setOf(PermissionDomain.PEER_DEVICE), policy.domains)
        assertNull(policy.evaluate(PermissionRequest(domain = PermissionDomain.DEVICE, operation = "peer_execute", source = PermissionSource.AGENT)))
    }

    @Test
    fun `reading the other phone needs no confirmation`() {
        val decision =
            decide(
                PeerDevicePolicy.execRequest(
                    execution = exec(risk = PermissionRisk.LOW, mutates = false),
                    readOnly = true,
                    emergencyStop = false,
                    preAuthorized = false,
                ),
            )

        assertEquals(ConfirmationLevel.AUTO, decision.level)
        assertTrue(decision.isAllowed)
    }

    @Test
    fun `a writing command is confirmed once when the agent asks for it`() {
        val decision =
            decide(
                PeerDevicePolicy.execRequest(
                    execution = exec(risk = PermissionRisk.MEDIUM, mutates = true),
                    readOnly = false,
                    emergencyStop = false,
                    preAuthorized = false,
                ),
            )

        assertEquals(ConfirmationLevel.CONFIRM, decision.level)
    }

    @Test
    fun `a confirmed tool call is not confirmed twice, but a destructive one still is`() {
        val write =
            decide(
                PeerDevicePolicy.execRequest(
                    execution = exec(risk = PermissionRisk.MEDIUM, mutates = true, preAuthorized = true),
                    readOnly = false,
                    emergencyStop = false,
                    preAuthorized = true,
                ),
            )
        val destructive =
            decide(
                PeerDevicePolicy.execRequest(
                    execution = exec(risk = PermissionRisk.HIGH, mutates = true, preAuthorized = true),
                    readOnly = false,
                    emergencyStop = false,
                    preAuthorized = true,
                ),
            )

        assertEquals(ConfirmationLevel.AUTO, write.level)
        assertEquals("a strong confirmation is never inherited", ConfirmationLevel.STRONG_CONFIRM, destructive.level)
    }

    @Test
    fun `Read-Only mode blocks a command that writes`() {
        val decision =
            decide(
                PeerDevicePolicy.execRequest(
                    execution = exec(risk = PermissionRisk.MEDIUM, mutates = true, preAuthorized = true),
                    readOnly = true,
                    emergencyStop = false,
                    preAuthorized = true,
                ),
            )

        assertEquals(ConfirmationLevel.DENY, decision.level)
        assertTrue(decision.reason.contains("Read-Only"))
    }

    @Test
    fun `the emergency stop outranks everything`() {
        val decision =
            decide(
                PeerDevicePolicy.execRequest(
                    execution = exec(risk = PermissionRisk.LOW, mutates = false),
                    readOnly = false,
                    emergencyStop = true,
                    preAuthorized = true,
                ),
            )

        assertEquals(ConfirmationLevel.DENY, decision.level)
        assertTrue(decision.reason.contains("emergency stop"))
    }

    @Test
    fun `pairing by the user is quiet, pairing on a schedule is not`() {
        val byUser =
            decide(
                PeerDevicePolicy.sessionRequest(
                    operation = PeerOperations.PAIR,
                    source = PermissionSource.USER,
                    target = "192.168.1.20:37123",
                ),
            )
        val bySchedule =
            decide(
                PeerDevicePolicy.sessionRequest(
                    operation = PeerOperations.PAIR,
                    source = PermissionSource.SCHEDULE,
                    target = "192.168.1.20:37123",
                ),
            )
        val preAuthorized =
            decide(
                PeerDevicePolicy.sessionRequest(
                    operation = PeerOperations.CONNECT,
                    source = PermissionSource.AGENT,
                    target = "serial-x",
                    preAuthorized = true,
                ),
            )

        assertEquals(ConfirmationLevel.AUTO, byUser.level)
        assertEquals(ConfirmationLevel.CONFIRM, bySchedule.level)
        assertEquals(ConfirmationLevel.AUTO, preAuthorized.level)
    }

    @Test
    fun `only pairing claims to change state, so Read-Only blocks enrolling but not connecting`() {
        val pair = PeerDevicePolicy.sessionRequest(PeerOperations.PAIR, PermissionSource.AGENT)
        val connect = PeerDevicePolicy.sessionRequest(PeerOperations.CONNECT, PermissionSource.AGENT)
        val info = PeerDevicePolicy.sessionRequest(PeerOperations.INFO, PermissionSource.AGENT)

        assertTrue(pair.mutatesState)
        assertTrue(!connect.mutatesState)
        assertTrue(!info.mutatesState)

        val readOnlyPair =
            decide(
                PeerDevicePolicy.sessionRequest(
                    operation = PeerOperations.PAIR,
                    source = PermissionSource.USER,
                    readOnly = true,
                ),
            )
        assertEquals(ConfirmationLevel.DENY, readOnlyPair.level)
    }

    @Test
    fun `an operation the policy does not know is refused by the center`() {
        val decision = decide(PermissionRequest(domain = PermissionDomain.PEER_DEVICE, operation = "peer.reboot_bootloader", source = PermissionSource.AGENT))

        assertEquals(ConfirmationLevel.DENY, decision.level)
    }

    /** A stand-in so the center sees the peer policy only where it applies. */
    private class AlwaysAllow : com.mushrea.code.core.permission.PermissionPolicy {
        override val id = "test.other"

        override val domains = setOf(PermissionDomain.DEVICE)

        override fun evaluate(request: PermissionRequest) =
            com.mushrea.code.core.permission.PermissionResult(level = ConfirmationLevel.AUTO, reason = "test", decidedBy = id)
    }
}
