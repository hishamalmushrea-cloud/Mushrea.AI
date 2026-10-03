package com.mushrea.code.core.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The general path: a **goal** - a recipe id, or the same thing in words - resolved against what the
 * target actually reported, with every route that was considered recorded and every blocker named.
 *
 * These tests are the acceptance criteria of the round in executable form: no pre-existing tool is
 * needed, a missing capability is never the first answer, and a route the phone cannot take is skipped
 * for the next one instead of failing the request.
 */
class ExecutionRecipePlanTest {
    private val transport = ExecutionTransport.PEER_ADB

    /** A provider that behaves like the ADB one: everything but the operations it is told to drop. */
    private class StubProvider(
        override val id: String = "stub",
        override val transport: ExecutionTransport = ExecutionTransport.PEER_ADB,
        private val served: Set<ExecutionOperation> = ExecutionOperation.entries.toSet(),
    ) : ExecutionProvider {
        override fun supports(operation: ExecutionOperation): Boolean = operation in served

        override suspend fun execute(request: ExecutionRequest): ExecutionResult = ExecutionResult(ExecutionStage.SUCCEEDED)
    }

    private fun capabilitiesOf(vararg pairs: Pair<String, CapabilityStatus>): CapabilityReport =
        CapabilityReport.of(pairs.map { (name, status) -> Capability(name, status) })

    private fun available(name: String): Pair<String, CapabilityStatus> = name to CapabilityStatus.AVAILABLE

    private fun missing(name: String): Pair<String, CapabilityStatus> = name to CapabilityStatus.MISSING

    private fun goal(
        recipeId: String? = null,
        description: String = "",
        parameters: Map<String, String> = emptyMap(),
    ): ExecutionGoal = ExecutionGoal(description = description, transport = transport, recipeId = recipeId, parameters = parameters)

    @Test
    fun `a goal resolves to the route the phone can take, with its capability named`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))

        val plan =
            planner.plan(
                goal(recipeId = "packages.list"),
                capabilitiesOf(available(CapabilityNames.binary("pm")), available(CapabilityNames.SHELL)),
            )

        assertTrue(plan.feasible)
        assertEquals("packages.list", plan.recipeId)
        assertEquals("stub", plan.providerId)
        val step = plan.steps.single()
        assertEquals(ExecutionOperation.SHELL, step.operation)
        assertEquals("bin:pm", step.capability)
        assertEquals("pm list packages", step.invocation.command)
    }

    @Test
    fun `a capability the phone reported missing moves the plan to the next route`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))

        val plan =
            planner.plan(
                goal(recipeId = "packages.list"),
                capabilitiesOf(
                    missing(CapabilityNames.binary("pm")),
                    available(CapabilityNames.binary("cmd")),
                    available(CapabilityNames.SHELL),
                ),
            )

        assertTrue(plan.feasible)
        assertEquals("cmd package list packages", plan.steps.single().invocation.command)
        assertEquals("bin:cmd", plan.steps.single().capability)
        assertTrue("the skipped route is explained", plan.steps.single().fallbackReason.contains("bin:pm"))
        assertTrue(plan.candidates.any { it.startsWith("pm-list: skipped") })
    }

    @Test
    fun `a capability nothing measured is attempted, not treated as absent`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))

        val plan = planner.plan(goal(recipeId = "packages.list"), CapabilityReport.unknown())

        assertTrue(plan.feasible)
        assertEquals("bin:pm", plan.steps.single().capability)
        assertEquals(listOf("bin:pm"), plan.steps.single().unproven)
    }

    @Test
    fun `a phone that cannot take any route gets a blocker naming the capability`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))

        val plan =
            planner.plan(
                goal(recipeId = "packages.list"),
                capabilitiesOf(missing(CapabilityNames.binary("pm")), missing(CapabilityNames.binary("cmd"))),
            )

        assertFalse(plan.feasible)
        assertEquals("bin:pm", plan.blockers.single().capability)
        assertTrue(plan.blockedReason().contains("bin:pm"))
        assertTrue(plan.candidates.all { it.contains("skipped") })
    }

    @Test
    fun `plans returns every feasible route in preference order, so a caller can fall back`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))
        val capabilities =
            capabilitiesOf(
                available(CapabilityNames.binary("pm")),
                available(CapabilityNames.binary("cmd")),
                available(CapabilityNames.SHELL),
            )

        val routes = planner.plans(ExecutionRecipes.registry.byId("packages.list")!!, goal(recipeId = "packages.list"), capabilities)

        assertEquals(listOf("pm-list", "cmd-package-list"), routes.map { it.steps.single().candidateId })
    }

    @Test
    fun `a goal in words finds the recipe without anybody naming it`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))

        val arabic = planner.plan(goal(description = "اعرض التطبيقات المثبتة"), capabilitiesOf(available(CapabilityNames.binary("pm"))))
        val english = planner.plan(goal(description = "take a screenshot please"), CapabilityReport.unknown())

        assertEquals("packages.list", arabic.recipeId)
        assertTrue(arabic.feasible)
        assertEquals("screen.capture", english.recipeId)
        assertTrue("a capture needs a local path, so it is blocked with one", english.blockers.single().reason.contains("local"))
    }

    @Test
    fun `a script is planned through the interpreter the phone reported`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))

        val plan =
            planner.plan(
                goal(recipeId = "script.run", parameters = mapOf("script" to "print(1)", "interpreter" to "python3")),
                capabilitiesOf(available(CapabilityNames.interpreter("python3")), available(CapabilityNames.SHELL)),
            )

        assertTrue(plan.feasible)
        val step = plan.steps.single()
        assertEquals(ExecutionOperation.SCRIPT, step.operation)
        assertEquals("python3", step.invocation.interpreter)
        assertEquals("interp:python3", step.capability)
    }

    @Test
    fun `a script whose interpreter is absent is blocked with the capability, unless the caller allows sh`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))
        val capabilities = capabilitiesOf(missing(CapabilityNames.interpreter("python3")), available(CapabilityNames.binary("sh")))

        val blocked = planner.plan(
            goal(recipeId = "script.run", parameters = mapOf("script" to "echo hi", "interpreter" to "python3")),
            capabilities,
        )
        val allowed =
            planner.plan(
                goal(
                    recipeId = "script.run",
                    parameters = mapOf("script" to "echo hi", "interpreter" to "python3", "shell_fallback" to "yes"),
                ),
                capabilities,
            )

        assertFalse(blocked.feasible)
        assertEquals("interp:python3", blocked.blockers.single().capability)
        assertTrue(allowed.feasible)
        assertEquals("sh", allowed.steps.single().invocation.interpreter)
    }

    @Test
    fun `a provider that cannot install is not offered the install route`() {
        val planner = ExecutionPlanner(listOf(StubProvider(served = ExecutionOperation.entries.toSet() - ExecutionOperation.INSTALL)))

        val plan =
            planner.plan(
                goal(recipeId = "app.install", parameters = mapOf("apk" to "/sdcard/app.apk")),
                capabilitiesOf(available(CapabilityNames.binary("pm")), available(CapabilityNames.SYNC)),
            )

        assertTrue(plan.feasible)
        assertEquals(listOf(ExecutionOperation.PUSH, ExecutionOperation.SHELL), plan.steps.map { it.operation })
        assertTrue(plan.candidates.any { it.startsWith("adb-install: skipped") })
    }

    @Test
    fun `a route the transport cannot serve is skipped, and the blocker names the transport`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))
        val ssh = ExecutionGoal(description = "", transport = ExecutionTransport.SSH, recipeId = "packages.list")

        val plan = planner.plan(ssh, capabilitiesOf(available(CapabilityNames.binary("pm"))))

        assertFalse(plan.feasible)
        assertTrue(plan.blockers.single().reason.contains("SSH"))
        assertTrue(plan.candidates.all { it.contains("SSH") })
    }

    @Test
    fun `an objective nobody wrote a recipe for says how to reach it anyway`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))

        val plan = planner.plan(goal(description = "sing a song to the other phone"), CapabilityReport.unknown())

        assertFalse(plan.feasible)
        assertTrue(plan.blockers.single().hint.contains("shell.run"))
        assertTrue(plan.blockers.single().reason.contains("no recipe matches"))
    }

    @Test
    fun `an unknown recipe id is not silently ignored`() {
        val planner = ExecutionPlanner(listOf(StubProvider()))

        val plan = planner.plan(goal(recipeId = "no.such.recipe"), CapabilityReport.unknown())

        assertFalse(plan.feasible)
        assertTrue(plan.blockers.single().hint.contains("shell.run"))
    }

    @Test
    fun `a caller that pins a provider gets that one or a refusal, never a substitution`() {
        val other =
            object : ExecutionProvider {
                override val id = "other"

                override val transport = ExecutionTransport.PEER_ADB

                override fun supports(operation: ExecutionOperation) = true

                override suspend fun execute(request: ExecutionRequest) = ExecutionResult(ExecutionStage.SUCCEEDED)
            }
        val planner = ExecutionPlanner(listOf(StubProvider(), other))
        val capabilities = capabilitiesOf(available(CapabilityNames.binary("pm")))

        val pinned = planner.plan(goal(recipeId = "packages.list").copy(providerId = "other"), capabilities)
        val unknown = planner.plan(goal(recipeId = "packages.list").copy(providerId = "no-such-provider"), capabilities)

        assertEquals("other", pinned.providerId)
        assertFalse("a pinned provider that does not exist is a refusal, not a substitution", unknown.feasible)
        assertTrue(unknown.candidates.all { !it.contains("chosen") })
        assertTrue(unknown.candidates.any { it.contains("no provider 'no-such-provider' is registered") })
    }

    @Test
    fun `a recipe the planner has never seen is still planned when a provider serves it`() {
        // The extension point, in a test: a new objective from a future provider is data, not a rebuild.
        val vendor =
            ExecutionRecipe(
                id = "vendor.health",
                title = "Vendor health",
                description = "Ask the vendor service how it feels",
                parameters = listOf(RecipeParameter("unit", "Which unit")),
                keywords = listOf("vendor health"),
            ) { values ->
                listOf(
                    RecipeCandidate(
                        id = "vendor-direct",
                        actions =
                            listOf(
                                RecipeAction(
                                    operation = ExecutionOperation.SHELL,
                                    invocation = ExecutionInvocation("vendorctl health ${values["unit"].orEmpty()}"),
                                    effect = ExecutionEffect(mutatesTarget = false),
                                    capability = "bin:vendorctl",
                                ),
                            ),
                    ),
                )
            }
        val planner = ExecutionPlanner(listOf(StubProvider()), ExecutionRecipes.registry.with(vendor))

        val plan =
            planner.plan(
                goal(recipeId = "vendor.health", parameters = mapOf("unit" to "core")),
                capabilitiesOf(available("bin:vendorctl")),
            )

        assertTrue(plan.feasible)
        assertEquals("vendorctl health core", plan.steps.single().invocation.command)
        assertNotNull(planner.catalogue().firstOrNull { it.id == "vendor.health" })
    }
}
