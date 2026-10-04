package com.mushrea.code.core.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner answers a fact question - "can this target take that step, and by which route?" - and
 * its honesty is the point: a missing capability becomes a named blocker, never a silent failure.
 */
class ExecutionPlannerTest {
    private val transport = ExecutionTransport.PEER_ADB

    /** A provider that behaves like the ADB one: exec-out is optional, shell always works. */
    private class StubProvider : ExecutionProvider {
        override val id = "stub"

        override val transport = ExecutionTransport.PEER_ADB

        override fun supports(operation: ExecutionOperation) = operation != ExecutionOperation.INSTALL

        override fun requirements(operation: ExecutionOperation): Set<String> =
            if (operation == ExecutionOperation.EXEC) setOf(CapabilityNames.EXEC_OUT) else setOf(CapabilityNames.SHELL)

        override fun alternative(
            operation: ExecutionOperation,
            missing: String,
        ): ExecutionOperation? = if (missing == CapabilityNames.EXEC_OUT) ExecutionOperation.SHELL else null

        override suspend fun execute(request: ExecutionRequest) = ExecutionResult(ExecutionStage.SUCCEEDED)
    }

    private val planner = ExecutionPlanner(listOf(StubProvider()))

    private fun capabilities(vararg pairs: Pair<String, CapabilityStatus>): CapabilityReport =
        CapabilityReport.of(pairs.map { (name, status) -> Capability(name, status) })

    @Test
    fun `a step the target can take is planned as it was asked`() {
        val plan =
            planner.plan(
                listOf(PlanIntent(ExecutionOperation.SHELL, transport, "list the installed packages")),
                capabilities(CapabilityNames.SHELL to CapabilityStatus.AVAILABLE),
            )

        assertTrue(plan.feasible)
        assertEquals(0, plan.blockers.size)
        assertEquals(ExecutionOperation.SHELL, plan.steps.single().operation)
        assertEquals("stub", plan.steps.single().providerId)
        assertNull(plan.steps.single().rewrittenFrom)
    }

    @Test
    fun `a missing executable is rewritten to the route that exists`() {
        val plan =
            planner.plan(
                listOf(PlanIntent(ExecutionOperation.EXEC, transport, "run the app")),
                capabilities(
                    CapabilityNames.SHELL to CapabilityStatus.AVAILABLE,
                    CapabilityNames.EXEC_OUT to CapabilityStatus.MISSING,
                ),
            )

        assertTrue(plan.feasible)
        val step = plan.steps.single()
        assertEquals(ExecutionOperation.SHELL, step.operation)
        assertEquals(ExecutionOperation.EXEC, step.rewrittenFrom)
        assertEquals(setOf(CapabilityNames.SHELL), step.requirements)
    }

    @Test
    fun `a capability the phone does not have becomes a named blocker`() {
        val plan =
            planner.plan(
                listOf(PlanIntent(ExecutionOperation.SHELL, transport, "list packages")),
                capabilities(CapabilityNames.SHELL to CapabilityStatus.MISSING),
            )

        assertFalse(plan.feasible)
        assertEquals(CapabilityNames.SHELL, plan.blockers.single().capability)
        assertTrue(plan.blockedReason().contains(CapabilityNames.SHELL))
        assertTrue(plan.blockedReason().contains("SHELL"))
    }

    @Test
    fun `an unproven capability does not block - it is not evidence`() {
        val plan =
            planner.plan(
                listOf(PlanIntent(ExecutionOperation.SHELL, transport, "list packages")),
                CapabilityReport.unknown(),
            )

        // The phone was never probed: the step is attempted and the provider reports the real error,
        // rather than the platform claiming an impossibility it has not measured.
        assertTrue(plan.feasible)
    }

    @Test
    fun `an operation no provider serves is blocked with the transport named`() {
        val plan =
            planner.plan(
                listOf(PlanIntent(ExecutionOperation.INSTALL, transport, "install an apk")),
                capabilities(CapabilityNames.SHELL to CapabilityStatus.AVAILABLE),
            )

        assertFalse(plan.feasible)
        assertNull(plan.blockers.single().capability)
        assertTrue(plan.blockers.single().reason.contains("PEER_ADB"))
        assertTrue(plan.blockers.single().reason.contains("INSTALL"))
    }

    @Test
    fun `one blocked step does not hide the ones that resolved, and the plan stays infeasible`() {
        val plan =
            planner.plan(
                listOf(
                    PlanIntent(ExecutionOperation.SHELL, transport, "list packages"),
                    PlanIntent(ExecutionOperation.INSTALL, transport, "install an apk"),
                ),
                capabilities(CapabilityNames.SHELL to CapabilityStatus.AVAILABLE),
            )

        assertEquals(1, plan.steps.size)
        assertEquals(1, plan.blockers.size)
        assertFalse(plan.feasible)
    }

    @Test
    fun `a provider named by the caller is used even when another one also matches`() {
        val other =
            object : ExecutionProvider {
                override val id = "other"

                override val transport = ExecutionTransport.PEER_ADB

                override fun supports(operation: ExecutionOperation) = true

                override suspend fun execute(request: ExecutionRequest) = ExecutionResult(ExecutionStage.SUCCEEDED)
            }
        val planner = ExecutionPlanner(listOf(StubProvider(), other))

        val step =
            planner.step(
                PlanIntent(ExecutionOperation.SHELL, transport, providerId = "other"),
                capabilities(CapabilityNames.SHELL to CapabilityStatus.AVAILABLE),
            ).getOrThrow()

        assertEquals("other", step.providerId)
    }
}
