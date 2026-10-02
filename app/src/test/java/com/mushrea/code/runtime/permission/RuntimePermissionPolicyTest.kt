package com.mushrea.code.runtime.permission

import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionCenter
import com.mushrea.code.core.permission.PermissionDomain
import com.mushrea.code.core.permission.PermissionResult
import com.mushrea.code.core.permission.PermissionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime policy: the two decisions that used to be taken somewhere else.
 *
 * The first is the agent's own permission prompt, which the chat, the voice session and the schedule
 * runner each answered with their own `if (autoAcceptPermissions)`. The second is the on-device
 * runtime's lifecycle, which no policy watched at all.
 */
class RuntimePermissionPolicyTest {
    private val policy = RuntimePermissionPolicy()
    private val center = PermissionCenter(listOf(policy))

    @Test
    fun `with the standing authorization the app answers the agent's prompt`() {
        val decided =
            center.decide(
                RuntimePermissionPolicy.agentPromptRequest(
                    source = PermissionSource.AGENT,
                    preAuthorized = true,
                ),
            )

        assertTrue(decided.isAllowed)
        assertEquals(RuntimePermissionPolicy.ID, decided.decidedBy)
        assertTrue(decided.reason.contains("auto-accept"))
    }

    @Test
    fun `without it the app refuses to answer for the user`() {
        val decided =
            center.decide(
                RuntimePermissionPolicy.agentPromptRequest(
                    source = PermissionSource.AGENT,
                    preAuthorized = false,
                ),
            )

        assertTrue(decided.isDenied)
        assertTrue(decided.reason.contains("auto-accept is off"))
    }

    @Test
    fun `the schedule's own auto-accept is the same input, not a second policy`() {
        val allowed =
            center.decide(
                RuntimePermissionPolicy.agentPromptRequest(
                    source = PermissionSource.SCHEDULE,
                    preAuthorized = true,
                    target = "session-7",
                ),
            )
        val denied =
            center.decide(
                RuntimePermissionPolicy.agentPromptRequest(
                    source = PermissionSource.SCHEDULE,
                    preAuthorized = false,
                ),
            )

        assertTrue(allowed.isAllowed)
        assertTrue(denied.isDenied)
    }

    @Test
    fun `a user tap starts, stops and deletes the runtime without extra friction`() {
        for (operation in RuntimePermissionPolicy.LIFECYCLE_OPERATIONS) {
            val decided =
                center.decide(
                    RuntimePermissionPolicy.lifecycleRequest(operation, PermissionSource.USER),
                )

            assertEquals(ConfirmationLevel.AUTO, decided.level)
            assertEquals(RuntimePermissionPolicy.ID, decided.decidedBy)
        }
    }

    @Test
    fun `the app's own start-up and a scheduled run may bring the runtime up`() {
        val startup =
            center.decide(
                RuntimePermissionPolicy.lifecycleRequest(
                    RuntimePermissionPolicy.OP_LIFECYCLE_START,
                    PermissionSource.SYSTEM,
                ),
            )
        val scheduled =
            center.decide(
                RuntimePermissionPolicy.lifecycleRequest(
                    RuntimePermissionPolicy.OP_LIFECYCLE_START,
                    PermissionSource.SCHEDULE,
                    target = "opencode",
                ),
            )

        assertTrue(startup.isAllowed)
        assertTrue(scheduled.isAllowed)
        assertTrue(scheduled.reason.contains("scheduled"))
    }

    @Test
    fun `an agent asking for a lifecycle change is confirmed, not waved through`() {
        val decided =
            center.decide(
                RuntimePermissionPolicy.lifecycleRequest(
                    RuntimePermissionPolicy.OP_LIFECYCLE_DELETE,
                    PermissionSource.AGENT,
                    target = "opencode",
                ),
            )

        assertEquals(ConfirmationLevel.CONFIRM, decided.level)
        assertTrue(decided.needsConfirmation)
        assertTrue(decided.reason.contains("agent"))
    }

    @Test
    fun `an operation this policy does not know is refused by the center`() {
        val decided =
            center.decide(
                RuntimePermissionPolicy.lifecycleRequest("runtime.lifecycle.teleport", PermissionSource.USER),
            )

        assertTrue(decided.isDenied)
        assertEquals(PermissionResult.DECIDED_BY_CENTER, decided.decidedBy)
        assertNull(policy.evaluate(RuntimePermissionPolicy.lifecycleRequest("runtime.lifecycle.teleport", PermissionSource.USER)))
    }

    @Test
    fun `the emergency stop outranks a lifecycle start`() {
        val decided =
            center.decide(
                RuntimePermissionPolicy.lifecycleRequest(
                    RuntimePermissionPolicy.OP_LIFECYCLE_START,
                    PermissionSource.SCHEDULE,
                    emergencyStop = true,
                ),
            )

        assertTrue(decided.isDenied)
        assertEquals(PermissionResult.DECIDED_BY_CENTER, decided.decidedBy)
    }

    @Test
    fun `the runtime gate does not pass the device read-only switch, and if it ever did the answer is a refusal`() {
        // The controller builds its request without `readOnly`, because Read-Only is the Device
        // Agent's own switch. This asserts the safe direction if a future caller passes it anyway.
        val withReadOnly =
            center.decide(
                RuntimePermissionPolicy.lifecycleRequest(
                    RuntimePermissionPolicy.OP_LIFECYCLE_START,
                    PermissionSource.USER,
                    readOnly = true,
                ),
            )
        val asBuilt =
            center.decide(
                RuntimePermissionPolicy.lifecycleRequest(
                    RuntimePermissionPolicy.OP_LIFECYCLE_START,
                    PermissionSource.USER,
                ),
            )

        assertTrue(withReadOnly.isDenied)
        assertFalse(withReadOnly.decidedBy == RuntimePermissionPolicy.ID)
        assertTrue(asBuilt.isAllowed)
    }

    @Test
    fun `the policy claims exactly the two runtime domains`() {
        assertEquals(
            setOf(PermissionDomain.AGENT_RUNTIME, PermissionDomain.RUNTIME_LIFECYCLE),
            policy.domains,
        )
        assertEquals(RuntimePermissionPolicy.ID, policy.id)
    }

    @Test
    fun `the lifecycle operations are the four the controller sends`() {
        assertEquals(
            setOf(
                "runtime.lifecycle.start",
                "runtime.lifecycle.install",
                "runtime.lifecycle.stop",
                "runtime.lifecycle.delete",
            ),
            RuntimePermissionPolicy.LIFECYCLE_OPERATIONS,
        )
    }
}
