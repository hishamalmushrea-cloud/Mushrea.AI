package com.mushrea.code.device.permission

import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionDecision
import com.mushrea.code.device.DeviceActionFirewall
import com.mushrea.code.device.tool.DeviceToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision layer is now the only thing that answers "may this run?" before the bridge executes,
 * so these tests are about precedence: which rule wins, and that a refusal always states why.
 */
class ToolPermissionPolicyTest {
    private val policy = ToolPermissionPolicy()

    @Test
    fun `an automatic tool is allowed and says so`() {
        val decision = policy.decide(DeviceActionFirewall.ACTION_READ_SCREEN)

        assertTrue(decision.allowed)
        assertEquals("allow", decision.label)
        assertTrue(decision.reason.contains("read_screen"))
    }

    @Test
    fun `a confirmation tool asks and carries the level`() {
        val decision = policy.decide(DeviceActionFirewall.ACTION_DELETE_FILE)

        assertEquals("confirm", decision.label)
        assertEquals(ConfirmationLevel.CONFIRM, (decision as PermissionDecision.Confirm).level)
        assertFalse(decision.allowed)
        assertFalse(decision.denied)
    }

    /**
     * The hole this phase closed: the old firewall answered AUTO for any id it did not know, so a
     * typo or a future tool name would run unconfirmed. The decision layer refuses it outright.
     */
    @Test
    fun `an id that is not a tool is denied, not allowed by default`() {
        val decision = policy.decide("hack_the_planet")

        assertTrue(decision.denied)
        assertTrue(decision.reason.startsWith("unknown tool"))
        assertFalse(decision.allowed)
    }

    @Test
    fun `read-only mode denies a state changer with the reason`() {
        val readOnly = ToolPermissionPolicy(readOnly = true)

        val decision = readOnly.decide(DeviceActionFirewall.ACTION_USB_SHELL)

        assertTrue(decision.denied)
        assertTrue(decision.reason.contains("Read-Only"))
        assertTrue(decision.reason.contains("usb_shell"))
    }

    @Test
    fun `read-only mode still allows the readers and the emergency stop`() {
        val readOnly = ToolPermissionPolicy(readOnly = true)

        assertTrue(readOnly.decide(DeviceActionFirewall.ACTION_READ_SCREEN).allowed)
        assertTrue(readOnly.decide(DeviceActionFirewall.ACTION_USB_LIST).allowed)
        // The stop is the safety valve: it must work with the switch on and off.
        assertTrue(readOnly.decide(DeviceActionFirewall.ACTION_STOP).allowed)
    }

    @Test
    fun `a stored override wins over the table`() {
        val upgraded = ToolPermissionPolicy(mapOf(DeviceActionFirewall.ACTION_TAP to ConfirmationLevel.STRONG))
        val downgraded = ToolPermissionPolicy(mapOf(DeviceActionFirewall.ACTION_DELETE_FILE to ConfirmationLevel.AUTO))

        val strong = upgraded.decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "Search")
        assertEquals(ConfirmationLevel.STRONG, (strong as PermissionDecision.Confirm).level)

        assertTrue(downgraded.decide(DeviceActionFirewall.ACTION_DELETE_FILE).allowed)
    }

    @Test
    fun `a tap on a sensitive control is escalated`() {
        val plain = policy.decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "Search")
        val sensitive = policy.decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "Pay now")
        val arabic = policy.decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "احذف الملف")

        assertEquals("allow", plain.label)
        assertEquals("confirm", sensitive.label)
        assertEquals("confirm", arabic.label)
        assertTrue((sensitive as PermissionDecision.Confirm).reason.contains("sensitive"))
    }

    @Test
    fun `an override to strong is never lowered by escalation`() {
        val strong = ToolPermissionPolicy(mapOf(DeviceActionFirewall.ACTION_TAP to ConfirmationLevel.STRONG))

        val sensitive = strong.decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "Pay now")
        val plain = strong.decide(DeviceActionFirewall.ACTION_TAP, tapLabel = "Search")

        assertEquals(ConfirmationLevel.STRONG, (sensitive as PermissionDecision.Confirm).level)
        assertEquals(ConfirmationLevel.STRONG, (plain as PermissionDecision.Confirm).level)
    }

    @Test
    fun `read-only wins over an override that would allow the action`() {
        val both =
            ToolPermissionPolicy(
                overrides = mapOf(DeviceActionFirewall.ACTION_USB_SHELL to ConfirmationLevel.AUTO),
                readOnly = true,
            )

        assertTrue(both.decide(DeviceActionFirewall.ACTION_USB_SHELL).denied)
    }

    @Test
    fun `every catalog tool gets an answer, and a denial always has a reason`() {
        for (tool in DeviceToolCatalog.all) {
            val decision = ToolPermissionPolicy().decide(tool.id)
            assertTrue("${tool.id}: empty reason", decision.reason.isNotBlank())
            if (decision.denied) assertTrue("${tool.id}: denial without explanation", decision.reason.length > 10)
        }
    }
}
