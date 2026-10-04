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
import com.mushrea.code.core.permission.PermissionPolicy
import com.mushrea.code.core.permission.PermissionRequest
import com.mushrea.code.core.permission.PermissionResult
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peer policy is the answer to "may this run on the other phone?" without a command table - and,
 * since round 2, without a closed list of operation *names* either.
 *
 * What it must never do: let an unconfirmed operation run because the caller said it was fine, let a
 * high-risk one become quiet because the user answered an earlier prompt, or refuse a capability the
 * platform has not learned about yet just because nobody listed its name here.
 */
class PeerDevicePolicyTest {
    private val policy = PeerDevicePolicy()

    private fun exec(
        risk: PermissionRisk,
        mutates: Boolean,
        preAuthorized: Boolean = false,
        destructive: Boolean = false,
    ): ExecutionRequest =
        ExecutionRequest(
            operation = ExecutionOperation.SHELL,
            target = ExecutionTarget(id = "serial-x", transport = ExecutionTransport.PEER_ADB, label = "Pixel 6a"),
            invocation = ExecutionInvocation("id"),
            effect = ExecutionEffect(mutatesTarget = mutates, destructive = destructive, risk = risk),
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
        assertNull(
            policy.evaluate(
                PermissionRequest(domain = PermissionDomain.DEVICE, operation = "peer_execute", source = PermissionSource.AGENT),
            ),
        )
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
    fun `an operation that can lose data is strong-confirmed even when it declares itself a read`() {
        // The `destructive` flag is what the classifier raises when it reads a wipe-shaped command; a
        // read-only claim must not be able to lower it, and a write is not needed to be destructive.
        val decision =
            decide(
                PeerDevicePolicy.execRequest(
                    execution = exec(risk = PermissionRisk.HIGH, mutates = false, destructive = true, preAuthorized = true),
                    readOnly = false,
                    emergencyStop = false,
                    preAuthorized = true,
                ),
            )

        assertEquals(ConfirmationLevel.STRONG_CONFIRM, decision.level)
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
    fun `an operation this policy has never seen is decided by its effect, not refused for its name`() {
        // A future provider (a second transport, a script host, a new session step) will produce
        // operation names this file has never heard of. Refusing those by name would make the policy
        // the ceiling of the platform: a read is quiet, a write is confirmed once, damage is strong.
        val read =
            decide(
                PermissionRequest(
                    domain = PermissionDomain.PEER_DEVICE,
                    operation = "peer.future.read_state",
                    source = PermissionSource.AGENT,
                    mutatesState = false,
                    risk = PermissionRisk.LOW,
                ),
            )
        val write =
            decide(
                PermissionRequest(
                    domain = PermissionDomain.PEER_DEVICE,
                    operation = "peer.future.push_settings",
                    source = PermissionSource.AGENT,
                    mutatesState = true,
                    risk = PermissionRisk.MEDIUM,
                ),
            )
        val dangerous =
            decide(
                PermissionRequest(
                    domain = PermissionDomain.PEER_DEVICE,
                    operation = "peer.future.wipe",
                    source = PermissionSource.AGENT,
                    mutatesState = true,
                    destructive = true,
                    risk = PermissionRisk.HIGH,
                ),
            )

        assertEquals(ConfirmationLevel.AUTO, read.level)
        assertEquals(ConfirmationLevel.CONFIRM, write.level)
        assertEquals(ConfirmationLevel.STRONG_CONFIRM, dangerous.level)
    }

    @Test
    fun `a malformed request outside the peer namespace is still refused by the center`() {
        // Fail-closed is kept where it belongs: a request that claims the peer domain but does not name
        // a peer operation is not this policy's business, and the center refuses what no policy claims.
        val decision =
            decide(
                PermissionRequest(
                    domain = PermissionDomain.PEER_DEVICE,
                    operation = "reboot_bootloader",
                    source = PermissionSource.AGENT,
                ),
            )

        assertEquals(ConfirmationLevel.DENY, decision.level)
        assertNull(
            policy.evaluate(
                PermissionRequest(domain = PermissionDomain.PEER_DEVICE, operation = "reboot_bootloader", source = PermissionSource.AGENT),
            ),
        )
    }

    @Test
    fun `the decision records the route it is about`() {
        val request =
            PeerDevicePolicy.execRequest(
                execution = exec(risk = PermissionRisk.LOW, mutates = false),
                readOnly = false,
                emergencyStop = false,
                preAuthorized = false,
                capability = "bin:pm",
                providerId = "peer-adb",
            )

        assertEquals("bin:pm", request.capability)
        assertEquals("peer-adb", request.providerId)
        assertTrue(decide(request).reason.contains("bin:pm"))
    }

    /** A stand-in so the center sees the peer policy only where it applies. */
    private class AlwaysAllow : PermissionPolicy {
        override val id = "test.other"

        override val domains = setOf(PermissionDomain.DEVICE)

        override fun evaluate(request: PermissionRequest) =
            PermissionResult(level = ConfirmationLevel.AUTO, reason = "test", decidedBy = id)
    }
}
