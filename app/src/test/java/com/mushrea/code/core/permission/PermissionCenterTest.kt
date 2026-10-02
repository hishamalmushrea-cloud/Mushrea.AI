package com.mushrea.code.core.permission

import com.mushrea.code.device.permission.DeviceToolPolicy
import com.mushrea.code.device.tool.DeviceToolCatalog
import com.mushrea.code.runtime.permission.RuntimePermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The center's own contract, tested against the two real policies plus fixed stand-ins.
 *
 * The cases here are the ones the phase asked for: the four levels, routing, fail-closed coverage,
 * Read-Only, Emergency Stop, risk, the audit hook — and the cross-subsystem claim, which is only
 * true if *every* catalog tool and *every* domain is answered by the same center.
 */
class PermissionCenterTest {
    private fun result(
        level: ConfirmationLevel,
        reason: String = "because",
    ) = PermissionResult(level = level, reason = reason, decidedBy = "fixed")

    private fun request(
        domain: PermissionDomain = PermissionDomain.DEVICE,
        operation: String = "op",
        readOnly: Boolean = false,
        emergencyStop: Boolean = false,
        safetyOperation: Boolean = false,
        mutatesState: Boolean = true,
        risk: PermissionRisk? = PermissionRisk.LOW,
        source: PermissionSource = PermissionSource.AGENT,
        preAuthorized: Boolean = false,
    ) = PermissionRequest(
        domain = domain,
        operation = operation,
        source = source,
        risk = risk,
        mutatesState = mutatesState,
        readOnly = readOnly,
        emergencyStop = emergencyStop,
        safetyOperation = safetyOperation,
        preAuthorized = preAuthorized,
    )

    private val device = DeviceToolPolicy()
    private val runtime = RuntimePermissionPolicy()

    /** The center as the app wires it: the device catalog and the runtime, nothing else. */
    private fun center(listener: ((PermissionRequest, PermissionResult) -> Unit)? = null) =
        PermissionCenter(listOf(device, runtime), listener)

    // ---------------------------------------------------------------- routing and levels

    @Test
    fun `a request is routed to the policy that owns its domain`() {
        val fixed =
            fixedPolicy("only.self", setOf(PermissionDomain.SSH)) { result(ConfirmationLevel.CONFIRM) }
        val center = PermissionCenter(listOf(fixed))

        val decided = center.decide(request(domain = PermissionDomain.SSH))

        assertEquals(ConfirmationLevel.CONFIRM, decided.level)
        assertEquals("only.self", decided.decidedBy)
        assertTrue(decided.needsConfirmation)
        assertFalse(decided.isAllowed)
        assertFalse(decided.isDenied)
    }

    @Test
    fun `auto, confirm and deny each come back as themselves`() {
        val auto =
            PermissionCenter(listOf(fixedPolicy("p", setOf(PermissionDomain.FILES)) { result(ConfirmationLevel.AUTO) }))
                .decide(request(domain = PermissionDomain.FILES))
        val confirm =
            PermissionCenter(listOf(fixedPolicy("p", setOf(PermissionDomain.SCREEN)) { result(ConfirmationLevel.CONFIRM) }))
                .decide(request(domain = PermissionDomain.SCREEN))
        val deny =
            PermissionCenter(listOf(fixedPolicy("p", setOf(PermissionDomain.NETWORK)) { result(ConfirmationLevel.DENY) }))
                .decide(request(domain = PermissionDomain.NETWORK))

        assertTrue(auto.isAllowed)
        assertFalse(auto.isDenied)
        assertFalse(auto.needsConfirmation)

        assertFalse(confirm.isAllowed)
        assertTrue(confirm.needsConfirmation)

        assertTrue(deny.isDenied)
        assertFalse(deny.isAllowed)
    }

    @Test
    fun `two policies claiming one domain is a wiring error, not a silent winner`() {
        val first = fixedPolicy("first", setOf(PermissionDomain.USB)) { result(ConfirmationLevel.AUTO) }
        val second = fixedPolicy("second", setOf(PermissionDomain.USB)) { result(ConfirmationLevel.DENY) }

        val error = assertThrows(IllegalArgumentException::class.java) { PermissionCenter(listOf(first, second)) }

        assertTrue(error.message.orEmpty().contains("USB"))
    }

    // ---------------------------------------------------------------- fail-closed

    @Test
    fun `a domain with no policy is denied, not allowed`() {
        val center = PermissionCenter(listOf(runtime))

        val decided = center.decide(request(domain = PermissionDomain.SSH, operation = "ssh.exec"))

        assertTrue(decided.isDenied)
        assertEquals(PermissionResult.DECIDED_BY_CENTER, decided.decidedBy)
        assertTrue(decided.reason.contains("no policy"))
    }

    @Test
    fun `an operation a policy does not cover is denied, so a new one cannot default to AUTO`() {
        val policy = fixedPolicy("picky", setOf(PermissionDomain.FILES)) { null }
        val center = PermissionCenter(listOf(policy))

        val decided = center.decide(request(domain = PermissionDomain.FILES, operation = "files.new_thing"))

        assertTrue(decided.isDenied)
        assertTrue(decided.reason.contains("does not cover"))
        assertTrue(decided.reason.contains("files.new_thing"))
    }

    // ---------------------------------------------------------------- emergency stop

    @Test
    fun `the emergency stop denies every operation through the center`() {
        val center = center()

        val decided =
            center.decide(
                request(
                    domain = PermissionDomain.RUNTIME_LIFECYCLE,
                    operation = RuntimePermissionPolicy.OP_LIFECYCLE_START,
                    emergencyStop = true,
                    source = PermissionSource.USER,
                ),
            )

        assertTrue(decided.isDenied)
        assertEquals(PermissionResult.DECIDED_BY_CENTER, decided.decidedBy)
        assertTrue(decided.reason.contains("emergency stop"))
    }

    @Test
    fun `the stop action itself stays reachable while the stop is set`() {
        val center = center()

        val decided =
            center.decide(
                request(
                    domain = PermissionDomain.SCREEN,
                    operation = "stop_agent",
                    emergencyStop = true,
                    safetyOperation = true,
                ),
            )

        assertFalse(decided.isDenied)
        assertEquals(DeviceToolPolicy.ID, decided.decidedBy)
    }

    @Test
    fun `a safety operation is not confused with an ordinary one`() {
        val center = center()

        val ordinary =
            center.decide(
                request(domain = PermissionDomain.DEVICE, operation = "read_screen", emergencyStop = true),
            )

        assertTrue(ordinary.isDenied)
    }

    // ---------------------------------------------------------------- read-only

    @Test
    fun `read-only turns an allowed state-changing operation into a denial`() {
        val fixed = fixedPolicy("stateful", setOf(PermissionDomain.FILES)) { result(ConfirmationLevel.AUTO) }
        val center = PermissionCenter(listOf(fixed))

        val decided = center.decide(request(domain = PermissionDomain.FILES, readOnly = true, mutatesState = true))

        assertTrue(decided.isDenied)
        assertEquals(PermissionResult.DECIDED_BY_CENTER, decided.decidedBy)
        assertTrue(decided.reason.contains("Read-Only"))
    }

    @Test
    fun `read-only leaves a read alone`() {
        val fixed = fixedPolicy("reader", setOf(PermissionDomain.FILES)) { result(ConfirmationLevel.AUTO) }
        val center = PermissionCenter(listOf(fixed))

        val decided = center.decide(request(domain = PermissionDomain.FILES, readOnly = true, mutatesState = false))

        assertTrue(decided.isAllowed)
    }

    @Test
    fun `read-only never blocks the safety operation`() {
        val fixed = fixedPolicy("stateful", setOf(PermissionDomain.SCREEN)) { result(ConfirmationLevel.AUTO) }
        val center = PermissionCenter(listOf(fixed))

        val decided =
            center.decide(
                request(
                    domain = PermissionDomain.SCREEN,
                    operation = "stop_agent",
                    readOnly = true,
                    mutatesState = true,
                    safetyOperation = true,
                ),
            )

        assertTrue(decided.isAllowed)
    }

    @Test
    fun `a standing authorization is an input the policy judges, never a bypass`() {
        // The policy here ignores `preAuthorized` completely; if the center treated the flag as an
        // authorization of its own, this denial would turn into an allow.
        val policy = fixedPolicy("strict", setOf(PermissionDomain.AGENT_RUNTIME)) { result(ConfirmationLevel.DENY) }
        val center = PermissionCenter(listOf(policy))

        val decided =
            center.decide(
                request(domain = PermissionDomain.AGENT_RUNTIME, preAuthorized = true, operation = "agent.permission.auto_accept"),
            )

        assertTrue(decided.isDenied)
        assertEquals("strict", decided.decidedBy)
    }

    @Test
    fun `the risk the caller declares is what the policy sees`() {
        var seen: PermissionRisk? = null
        val policy =
            fixedPolicy("watcher", setOf(PermissionDomain.FILES)) { request ->
                seen = request.risk
                result(ConfirmationLevel.AUTO)
            }
        val center = PermissionCenter(listOf(policy))

        center.decide(request(domain = PermissionDomain.FILES, risk = PermissionRisk.HIGH))

        assertEquals(PermissionRisk.HIGH, seen)
    }

    // ---------------------------------------------------------------- audit hook

    @Test
    fun `the audit listener sees the request and the decision`() {
        val seen = mutableListOf<Pair<PermissionRequest, PermissionResult>>()
        val center = center { request, decided -> seen += request to decided }

        center.decide(request(domain = PermissionDomain.DEVICE, operation = "read_screen"))

        assertEquals(1, seen.size)
        assertEquals("read_screen", seen.single().first.operation)
        assertEquals(DeviceToolPolicy.ID, seen.single().second.decidedBy)
    }

    @Test
    fun `a failing audit listener cannot change or block a decision`() {
        val center = center { _, _ -> error("the audit store is gone") }

        val decided = center.decide(request(domain = PermissionDomain.DEVICE, operation = "read_screen"))

        assertFalse(decided.isDenied)
    }

    // ---------------------------------------------------------------- vocabulary bridge

    @Test
    fun `every level maps to the bridge's decision type with its reason intact`() {
        val cases =
            listOf(
                ConfirmationLevel.AUTO to "allow",
                ConfirmationLevel.CONFIRM to "confirm",
                ConfirmationLevel.STRONG_CONFIRM to "confirm",
                ConfirmationLevel.DENY to "deny",
            )
        for ((level, label) in cases) {
            val decided = PermissionResult(level, "why $level", "fixed")
            val decision = decided.asDecision()

            assertEquals(label, decision.label)
            assertEquals("why $level", decision.reason)
            if (level.needsUserAnswer) {
                assertEquals(level, (decision as PermissionDecision.Confirm).level)
            }
        }
    }

    @Test
    fun `the pre-P2 strong spelling still parses, so stored overrides survive`() {
        assertEquals(ConfirmationLevel.STRONG_CONFIRM, ConfirmationLevel.parseOrNull("STRONG"))
        assertEquals(ConfirmationLevel.STRONG_CONFIRM, ConfirmationLevel.parseOrNull("strong_confirm"))
        assertEquals(ConfirmationLevel.CONFIRM, ConfirmationLevel.parseOrNull("CONFIRM"))
        assertEquals(ConfirmationLevel.AUTO, ConfirmationLevel.parseOrNull("auto"))
        assertNull(ConfirmationLevel.parseOrNull("something else"))
        assertNull(ConfirmationLevel.parseOrNull(null))
    }

    // ---------------------------------------------------------------- cross-subsystem

    /**
     * The claim "every subsystem asks the same center" is checked as data: all 90 catalog tools
     * (which is where Screen, Files, Network, USB, SSH, Remote, Bluetooth, calls and Termux live)
     * plus the runtime domain must produce a policy answer — never the center's fail-closed refusal,
     * and never a level the policy invented.
     */
    @Test
    fun `every catalog tool is answered by the device policy through the center`() {
        val center = center()
        val unexplained = mutableListOf<String>()

        for (tool in DeviceToolCatalog.all) {
            val decided =
                center.decide(
                    DeviceToolPolicy.deviceRequest(
                        action = tool.id,
                        source = PermissionSource.AGENT,
                        readOnly = false,
                        emergencyStop = false,
                    ),
                )
            if (decided.isDenied || decided.decidedBy != DeviceToolPolicy.ID) {
                unexplained += "${tool.id}: ${decided.level}/${decided.decidedBy}"
            }
        }

        assertEquals(emptyList<String>(), unexplained)
    }

    @Test
    fun `every domain of the app is claimed by exactly one policy`() {
        val center = center()

        for (domain in PermissionDomain.entries) {
            assertNotNull("no policy answers $domain", center.policyFor(domain))
        }
        assertEquals(PermissionDomain.entries.toSet(), center.domains)
    }

    @Test
    fun `the runtime domain is answered by the runtime policy through the same center`() {
        val center = center()

        val start =
            center.decide(
                RuntimePermissionPolicy.lifecycleRequest(
                    RuntimePermissionPolicy.OP_LIFECYCLE_START,
                    PermissionSource.SCHEDULE,
                    target = "opencode",
                ),
            )
        val prompt =
            center.decide(
                RuntimePermissionPolicy.agentPromptRequest(
                    source = PermissionSource.AGENT,
                    preAuthorized = true,
                ),
            )

        assertEquals(RuntimePermissionPolicy.ID, start.decidedBy)
        assertTrue(start.isAllowed)
        assertEquals(RuntimePermissionPolicy.ID, prompt.decidedBy)
        assertTrue(prompt.isAllowed)
    }

    @Test
    fun `a device request built for a tool carries the catalog's risk and state flag`() {
        val tool = DeviceToolCatalog.tool("read_screen")
        assertNotNull(tool)

        val built =
            DeviceToolPolicy.deviceRequest(
                action = tool!!.id,
                source = PermissionSource.AGENT,
                readOnly = false,
                emergencyStop = false,
            )

        assertEquals(tool.risk, built.risk)
        assertEquals(!tool.readOnly, built.mutatesState)
    }
}

/**
 * A policy that answers exactly what a test tells it to, so the center's own rules can be tested
 * without borrowing a subsystem's.
 */
private fun fixedPolicy(
    policyId: String,
    policyDomains: Set<PermissionDomain>,
    answer: (PermissionRequest) -> PermissionResult?,
): PermissionPolicy =
    object : PermissionPolicy {
        override val id: String = policyId
        override val domains: Set<PermissionDomain> = policyDomains

        override fun evaluate(request: PermissionRequest): PermissionResult? = answer(request)
    }
