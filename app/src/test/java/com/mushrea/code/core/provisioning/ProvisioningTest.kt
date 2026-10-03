package com.mushrea.code.core.provisioning

import com.mushrea.code.core.connectivity.ConnectivityReport
import com.mushrea.code.core.connectivity.Endpoint
import com.mushrea.code.core.connectivity.EndpointSource
import com.mushrea.code.core.connectivity.NetworkScope
import com.mushrea.code.core.connectivity.RouteCandidate
import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.peer.PeerTrust
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Connect to this device and set it up for remote work" - as rules, not as a script.
 *
 * The tests pin the four things that make the flow trustworthy: it plans from measured facts and not
 * from a device name, it asks for the one thing Android will not let an app do for the user, it never
 * sends adb to a public address unless told to, and its final status is derived from what was proven.
 */
class ProvisioningTest {
    private val planner = ProvisioningPlanner()

    private fun route(
        address: String,
        source: EndpointSource = EndpointSource.ANNOUNCED,
        transport: String = "peer-adb-tcp",
    ) = RouteCandidate(Endpoint(address, 37123, source), transport, reason = "test route")

    private fun facts(
        readiness: DeviceReadiness = DeviceReadiness.DISCOVERED,
        trust: PeerTrust = PeerTrust.UNKNOWN,
        routes: List<RouteCandidate> = emptyList(),
        capabilities: CapabilityReport = CapabilityReport.unknown(),
        extra: Map<String, String> = emptyMap(),
    ) = ProvisioningFacts(
        targetId = "serial-1",
        readiness = readiness,
        trust = trust,
        capabilities = capabilities,
        connectivity = ConnectivityReport.of(setOf(com.mushrea.code.core.connectivity.ConnectivityFacility.WIFI)),
        routes = routes,
        extra = extra,
    )

    private fun request(
        pairingCode: String? = null,
        purpose: ProvisioningPurpose = ProvisioningPurpose.REMOTE_CONTROL,
        allowPublic: Boolean = false,
        persistence: Boolean = true,
    ) = ProvisioningRequest(
        targetId = "serial-1",
        purpose = purpose,
        persistence = persistence,
        pairingCode = pairingCode,
        allowPublicRoutes = allowPublic,
    )

    private fun kinds(plan: ProvisioningPlan) = plan.steps.map { it.kind }

    @Test
    fun `a brand-new device is asked for exactly one user action`() {
        val plan = planner.plan(request(), facts(routes = listOf(route("192.168.1.20"))))

        assertTrue(kinds(plan).contains(ProvisioningStepKind.PAIR))
        assertTrue(kinds(plan).contains(ProvisioningStepKind.CONNECT))
        val pair = plan.stepsOf(ProvisioningStepKind.PAIR).single()
        assertFalse(pair.automated)
        assertTrue("the instruction names the Android setting", pair.instruction.contains("Wireless debugging"))
    }

    @Test
    fun `a code the user already read turns pairing into an automated step`() {
        val plan = planner.plan(request(pairingCode = "123456"), facts(routes = listOf(route("192.168.1.20"))))

        assertTrue(plan.stepsOf(ProvisioningStepKind.PAIR).single().automated)
        assertFalse(plan.needsUser)
    }

    @Test
    fun `a device we already trust is not paired again`() {
        val plan =
            planner.plan(
                request(),
                facts(readiness = DeviceReadiness.CONNECTED, trust = PeerTrust.TOFU, routes = listOf(route("192.168.1.20"))),
            )

        assertTrue(plan.stepsOf(ProvisioningStepKind.PAIR).isEmpty())
        assertTrue(kinds(plan).contains(ProvisioningStepKind.VERIFY))
    }

    @Test
    fun `a revoked device is not silently re-trusted`() {
        val plan = planner.plan(request(), facts(trust = PeerTrust.REVOKED, routes = listOf(route("192.168.1.20"))))

        val pair = plan.stepsOf(ProvisioningStepKind.PAIR).single()
        assertFalse(pair.automated)
        assertTrue(pair.instruction.lowercase().contains("revoked") || pair.instruction.contains("الاقتران"))
    }

    @Test
    fun `no route at all becomes one clear instruction, not unsupported`() {
        val plan = planner.plan(request(), facts())

        val ask = plan.stepsOf(ProvisioningStepKind.ENABLE_REMOTE_ACCESS).single()
        assertFalse(ask.automated)
        assertTrue(ask.instruction.contains("Wireless debugging"))
        assertTrue("the plan knows nothing is reachable", plan.note.contains("no usable route"))
    }

    @Test
    fun `a public address is not a route unless the caller opts in`() {
        val privatePlan = planner.plan(request(), facts(routes = listOf(route("8.8.8.8"))))
        val allowed = planner.plan(request(allowPublic = true), facts(routes = listOf(route("8.8.8.8"))))

        assertTrue(privatePlan.stepsOf(ProvisioningStepKind.ENABLE_REMOTE_ACCESS).isNotEmpty())
        assertTrue(privatePlan.note.contains("public route"))
        assertTrue(allowed.stepsOf(ProvisioningStepKind.ENABLE_REMOTE_ACCESS).isEmpty())
        assertTrue(allowed.note.contains("1 route(s) available"))
    }

    @Test
    fun `persistence is planned from the programs the device actually has`() {
        val withoutSettings = planner.plan(request(), facts(routes = listOf(route("192.168.1.20"))))
        val withSettings =
            planner.plan(
                request(),
                facts(
                    readiness = DeviceReadiness.CAPABILITIES_VERIFIED,
                    routes = listOf(route("192.168.1.20")),
                    capabilities =
                        CapabilityReport.of(
                            listOf(
                                CapabilityReport.available(CapabilityNames.SHELL, "/system/bin/sh"),
                                CapabilityReport.available(CapabilityNames.binary("settings"), "/system/bin/settings"),
                            ),
                        ),
                ),
            )

        assertTrue("nothing is written to a device that reported no settings", withoutSettings.stepsOf(ProvisioningStepKind.PERSIST).none { it.mutatesTarget })
        val ids = withSettings.stepsOf(ProvisioningStepKind.PERSIST).map { it.id }
        assertTrue(ids.contains("persist-wifi"))
        assertTrue(ids.contains("persist-awake"))
        assertTrue("persistence changes the target", withSettings.stepsOf(ProvisioningStepKind.PERSIST).all { it.mutatesTarget })
    }

    @Test
    fun `a test-only run does not write anything to the device`() {
        val plan =
            planner.plan(
                request(purpose = ProvisioningPurpose.TEST, persistence = false),
                facts(routes = listOf(route("192.168.1.20"))),
            )

        assertTrue(plan.stepsOf(ProvisioningStepKind.PERSIST).isEmpty())
    }

    @Test
    fun `capabilities are measured when nothing was measured, and not re-measured for nothing`() {
        val fresh = planner.plan(request(), facts(routes = listOf(route("192.168.1.20"))))
        val measured =
            planner.plan(
                request(),
                facts(
                    readiness = DeviceReadiness.CAPABILITIES_VERIFIED,
                    routes = listOf(route("192.168.1.20")),
                    capabilities = CapabilityReport.of(listOf(CapabilityReport.available(CapabilityNames.SHELL))),
                ),
            )

        assertTrue(kinds(fresh).contains(ProvisioningStepKind.CAPABILITIES))
        assertTrue(measured.stepsOf(ProvisioningStepKind.CAPABILITIES).isEmpty())
    }

    @Test
    fun `readiness is derived from what was proven, never from a socket`() {
        assertEquals(DeviceReadiness.DISCOVERED, DeviceReadiness.of(known = true))
        assertEquals(DeviceReadiness.CONNECTED, DeviceReadiness.of(known = true, connected = true))
        assertEquals(DeviceReadiness.IDENTIFIED, DeviceReadiness.of(known = true, connected = true, identified = true))
        assertEquals(
            DeviceReadiness.EXECUTION_VERIFIED,
            DeviceReadiness.of(known = true, connected = true, identified = true, measured = true, executed = true),
        )
        assertTrue(DeviceReadiness.EXECUTION_VERIFIED.atLeast(DeviceReadiness.CAPABILITIES_VERIFIED))
        assertFalse(DeviceReadiness.CONNECTED.atLeast(DeviceReadiness.IDENTIFIED))
    }

    // ---- the engine ---------------------------------------------------------------------------

    private class FakeHost(
        var facts: ProvisioningFacts,
        private val handler: (ProvisioningStep) -> StepOutcome,
    ) : ProvisioningHost {
        override val id = "fake"
        val ran = mutableListOf<String>()

        override suspend fun facts(request: ProvisioningRequest): ProvisioningFacts = facts

        override suspend fun run(
            step: ProvisioningStep,
            request: ProvisioningRequest,
            facts: ProvisioningFacts,
        ): StepOutcome {
            ran += step.id
            return handler(step)
        }
    }

    @Test
    fun `the engine stops at the step only the user can take`() = runBlocking {
        val host = FakeHost(facts(routes = listOf(route("192.168.1.20")))) { StepOutcome.Completed("done") }

        val report = ProvisioningEngine(host).provision(request())

        assertEquals(ProvisioningStatus.NEEDS_USER, report.status)
        assertTrue(report.needsUser != null)
        assertFalse("nothing after the user's step runs", host.ran.contains("connect"))
    }

    @Test
    fun `a full run reports what was proven, not what was attempted`() = runBlocking {
        val initial = facts(readiness = DeviceReadiness.CONNECTED, trust = PeerTrust.TOFU, routes = listOf(route("192.168.1.20")))
        lateinit var host: FakeHost
        host =
            FakeHost(initial) { step ->
                if (step.kind == ProvisioningStepKind.TEST_EXECUTION) {
                    // The proof arrives here and nothing else changes it: the final status has to come
                    // from this measurement, not from the fact that a step returned "ok".
                    host.facts = host.facts.copy(readiness = DeviceReadiness.EXECUTION_VERIFIED)
                    StepOutcome.Completed("the device answered", evidence = "getprop ro.product.model")
                } else {
                    StepOutcome.Completed("ok")
                }
            }

        val report = ProvisioningEngine(host).provision(request(persistence = false))

        assertEquals(ProvisioningStatus.PROVISIONED, report.status)
        assertEquals(DeviceReadiness.EXECUTION_VERIFIED, report.readiness)
        assertTrue(report.steps.all { it.completed })
        assertTrue("the summary names the readiness it measured", report.summary.contains("execution_verified"))
    }

    @Test
    fun `an unsupported step does not end a run that can still connect`() = runBlocking {
        val host =
            FakeHost(facts(readiness = DeviceReadiness.CONNECTED, trust = PeerTrust.TOFU, routes = listOf(route("192.168.1.20")))) { step ->
                    if (step.id.startsWith("persist")) {
                        StepOutcome.Unsupported("this build does not expose settings")
                    } else {
                        StepOutcome.Completed("ok")
                    }
            }

        val report = ProvisioningEngine(host).provision(request())

        assertEquals(ProvisioningStatus.PARTIAL, report.status)
        assertTrue(host.ran.contains("test-execution"))
        assertTrue(report.steps.any { it.outcome is StepOutcome.Unsupported })
    }

    @Test
    fun `a failing step stops the run and is reported with its reason`() = runBlocking {
        val host =
            FakeHost(facts(readiness = DeviceReadiness.CONNECTED, trust = PeerTrust.TOFU, routes = listOf(route("192.168.1.20")))) { step ->
                    if (step.kind == ProvisioningStepKind.VERIFY) {
                        StepOutcome.Failed("device offline", "DEVICE_OFFLINE")
                    } else {
                        StepOutcome.Completed("ok")
                    }
            }

        val report = ProvisioningEngine(host).provision(request(persistence = false))

        assertEquals(ProvisioningStatus.PARTIAL, report.status)
        assertEquals("device offline", (report.failure?.outcome as StepOutcome.Failed).detail)
        assertFalse(host.ran.contains("test-execution"))
        assertEquals("device offline", report.headline())
    }

    @Test
    fun `a step that throws is classified, not propagated`() = runBlocking {
        val host =
            FakeHost(facts(readiness = DeviceReadiness.CONNECTED, trust = PeerTrust.TOFU, routes = listOf(route("192.168.1.20")))) { step ->
                if (step.kind == ProvisioningStepKind.VERIFY) throw IllegalStateException("adbd went away") else StepOutcome.Completed("ok")
            }

        val report = ProvisioningEngine(host).provision(request(persistence = false))

        assertTrue(report.failure?.outcome is StepOutcome.Failed)
        assertTrue((report.failure?.outcome as StepOutcome.Failed).detail.contains("adbd went away"))
    }
}
