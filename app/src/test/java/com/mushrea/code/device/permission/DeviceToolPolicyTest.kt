package com.mushrea.code.device.permission

import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionCenter
import com.mushrea.code.core.permission.PermissionDomain
import com.mushrea.code.core.permission.PermissionResult
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource
import com.mushrea.code.device.DeviceActionFirewall
import com.mushrea.code.device.tool.DeviceToolCatalog
import com.mushrea.code.device.tool.ToolFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device catalog's rules as the center sees them.
 *
 * `ToolPermissionPolicyTest` still owns the per-tool precedence cases; these tests are about the
 * adapter P2 added: that the catalog is routed by family to a domain, that risk and state are taken
 * from the catalog rather than from the caller, that the user's overrides and Read-Only survive the
 * move, and that an id which is not a tool cannot reach an automatic level.
 */
class DeviceToolPolicyTest {
    private val policy = DeviceToolPolicy()
    private val center = PermissionCenter(listOf(policy))

    private fun decide(
        action: String,
        arguments: Map<String, ConfirmationLevel> = emptyMap(),
        readOnly: Boolean = false,
        tapLabel: String? = null,
        domain: PermissionDomain? = null,
        source: PermissionSource = PermissionSource.AGENT,
    ) =
        PermissionCenter(listOf(DeviceToolPolicy(overrides = { arguments }))).decide(
            DeviceToolPolicy
                .deviceRequest(
                    action = action,
                    source = source,
                    readOnly = readOnly,
                    emergencyStop = false,
                    tapLabel = tapLabel,
                ).let { request -> if (domain == null) request else request.copy(domain = domain) },
        )

    @Test
    fun `every tool in the catalog keeps the level its entry declares`() {
        val wrong = mutableListOf<String>()
        for (tool in DeviceToolCatalog.all) {
            val decided = decide(tool.id)
            if (decided.level != tool.confirmation) {
                wrong += "${tool.id}: catalog ${tool.confirmation}, center ${decided.level}"
            }
        }

        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `a high-risk tool is never automatic`() {
        val automaticHighRisk =
            DeviceToolCatalog.all
                .filter { it.risk == PermissionRisk.HIGH && !it.readOnly }
                .filter { decide(it.id).level == ConfirmationLevel.AUTO }
                .map { it.id }

        assertEquals(emptyList<String>(), automaticHighRisk)
        // The catalog's risk really does drive the level, so the test above is not vacuous.
        assertTrue(DeviceToolCatalog.all.any { it.risk == PermissionRisk.HIGH })
    }

    @Test
    fun `risk travels with the request instead of being restated by the caller`() {
        val mismatched =
            DeviceToolCatalog.all.filter { tool ->
                val request =
                    DeviceToolPolicy.deviceRequest(
                        action = tool.id,
                        source = PermissionSource.AGENT,
                        readOnly = false,
                        emergencyStop = false,
                    )
                val sameDomain = request.domain == DeviceToolPolicy.domainForFamily(tool.family)
                request.risk != tool.risk || request.mutatesState != !tool.readOnly || !sameDomain
            }

        assertEquals(emptyList<String>(), mismatched.map { it.id })
    }

    @Test
    fun `the catalog family decides the domain, so a caller cannot misroute a tool`() {
        val families =
            ToolFamily.entries.map { family -> family to DeviceToolPolicy.domainForFamily(family) }

        assertEquals(PermissionDomain.SCREEN, families.toMap()[ToolFamily.SCREEN])
        assertEquals(PermissionDomain.USB, families.toMap()[ToolFamily.SERIAL])
        assertEquals(PermissionDomain.USB, families.toMap()[ToolFamily.HUB])
        assertEquals(PermissionDomain.USB, families.toMap()[ToolFamily.MTP])
        assertEquals(PermissionDomain.SSH, families.toMap()[ToolFamily.SSH])
        assertEquals(PermissionDomain.REMOTE, families.toMap()[ToolFamily.REMOTE])
        assertEquals(PermissionDomain.NETWORK, families.toMap()[ToolFamily.NETWORK])
        assertEquals(PermissionDomain.FILES, families.toMap()[ToolFamily.FILES])
        assertEquals(PermissionDomain.DEVICE, families.toMap()[ToolFamily.CALL])
        assertEquals(PermissionDomain.DEVICE, families.toMap()[ToolFamily.TERMUX])

        // A request that claims the wrong domain is refused by the policy, not evaluated anyway.
        val misrouted = decide(DeviceActionFirewall.ACTION_SSH_UPLOAD, domain = PermissionDomain.SCREEN)
        assertTrue(misrouted.isDenied)
        assertTrue(misrouted.reason.contains("does not cover"))
    }

    @Test
    fun `an id that is not a tool is denied, whatever it looks like`() {
        val decided = decide("usb_adb_shell_v2")

        assertTrue(decided.isDenied)
        assertEquals(PermissionResult.DECIDED_BY_CENTER, decided.decidedBy)
        assertTrue(decided.reason.contains("does not cover"))
        assertTrue(decided.reason.contains("usb_adb_shell_v2"))
    }

    @Test
    fun `an unknown id is treated as high risk while it is being refused`() {
        val request =
            DeviceToolPolicy.deviceRequest(
                action = "not_a_tool",
                source = PermissionSource.AGENT,
                readOnly = false,
                emergencyStop = false,
            )

        assertEquals(PermissionRisk.HIGH, request.risk)
        assertTrue(request.mutatesState)
        assertFalse(request.safetyOperation)
    }

    @Test
    fun `the user's override still wins over the catalog level`() {
        val raised =
            decide(
                DeviceActionFirewall.ACTION_DELETE_FILE,
                arguments = mapOf(DeviceActionFirewall.ACTION_DELETE_FILE to ConfirmationLevel.STRONG_CONFIRM),
            )
        val dropped =
            decide(
                DeviceActionFirewall.ACTION_DELETE_FILE,
                arguments = mapOf(DeviceActionFirewall.ACTION_DELETE_FILE to ConfirmationLevel.AUTO),
            )

        assertEquals(ConfirmationLevel.STRONG_CONFIRM, raised.level)
        assertTrue(raised.overridden)
        assertEquals(ConfirmationLevel.AUTO, dropped.level)
        assertTrue(dropped.overridden)
    }

    @Test
    fun `an override that repeats the catalog level is not reported as an override`() {
        val decided =
            decide(
                DeviceActionFirewall.ACTION_DELETE_FILE,
                arguments = mapOf(DeviceActionFirewall.ACTION_DELETE_FILE to ConfirmationLevel.CONFIRM),
            )

        assertEquals(ConfirmationLevel.CONFIRM, decided.level)
        assertFalse(decided.overridden)
    }

    @Test
    fun `read-only refuses a state-changing tool before the confirmation prompt`() {
        val decided = decide(DeviceActionFirewall.ACTION_DELETE_FILE, readOnly = true)

        assertTrue(decided.isDenied)
        assertEquals(DeviceToolPolicy.ID, decided.decidedBy)
        assertTrue(decided.reason.contains("Read-Only"))
    }

    @Test
    fun `read-only still allows a reader and the stop action`() {
        val reader = decide(DeviceActionFirewall.ACTION_READ_SCREEN, readOnly = true)
        val stop = decide(DeviceActionFirewall.ACTION_STOP, readOnly = true)

        assertFalse(reader.isDenied)
        assertFalse(stop.isDenied)
    }

    @Test
    fun `a sensitive tap label escalates through the policy`() {
        val sensitive = decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "Pay now")
        val arabic = decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "احذف الحساب")
        val plain = decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "Home")

        assertEquals(ConfirmationLevel.CONFIRM, sensitive.level)
        assertEquals(ConfirmationLevel.CONFIRM, arabic.level)
        assertFalse(plain.isDenied)
    }

    @Test
    fun `the stop action is the only safety operation in the catalog`() {
        val safety = DeviceToolCatalog.all.filter { DeviceToolPolicy.isSafetyOperation(it.id) }

        assertEquals(listOf(DeviceActionFirewall.ACTION_STOP), safety.map { it.id })
    }

    @Test
    fun `the policy claims every device family domain and nothing else`() {
        assertEquals(
            setOf(
                PermissionDomain.DEVICE,
                PermissionDomain.SCREEN,
                PermissionDomain.FILES,
                PermissionDomain.NETWORK,
                PermissionDomain.USB,
                PermissionDomain.SSH,
                PermissionDomain.REMOTE,
            ),
            policy.domains,
        )
        assertEquals(DeviceToolPolicy.ID, policy.id)
        assertNull(policy.domains.firstOrNull { it == PermissionDomain.RUNTIME_LIFECYCLE })
        assertNotNull(center.policyFor(PermissionDomain.USB))
    }
}
