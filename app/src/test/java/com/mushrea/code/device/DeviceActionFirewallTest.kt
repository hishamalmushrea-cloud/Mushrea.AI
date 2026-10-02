package com.mushrea.code.device

import com.mushrea.code.core.permission.ConfirmationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionFirewallTest {
    @Test
    fun `read and navigation actions run automatically`() {
        val firewall = DeviceActionFirewall()
        for (
        action in
        listOf(
            DeviceActionFirewall.ACTION_GET_CURRENT_APP,
            DeviceActionFirewall.ACTION_READ_SCREEN,
            DeviceActionFirewall.ACTION_SEARCH_FILES,
            DeviceActionFirewall.ACTION_OPEN_APP,
            DeviceActionFirewall.ACTION_OPEN_FILE,
            DeviceActionFirewall.ACTION_SCROLL,
            DeviceActionFirewall.ACTION_TYPE_TEXT,
            DeviceActionFirewall.ACTION_TAP,
            DeviceActionFirewall.ACTION_PRESS_BACK,
        )
        ) {
            assertEquals(action, ConfirmationLevel.AUTO, firewall.levelFor(action))
        }
    }

    @Test
    fun `destructive file actions require confirmation`() {
        val firewall = DeviceActionFirewall()
        for (
        action in
        listOf(
            DeviceActionFirewall.ACTION_DELETE_FILE,
            DeviceActionFirewall.ACTION_SHARE_FILE,
            DeviceActionFirewall.ACTION_MOVE_FILE,
            DeviceActionFirewall.ACTION_COPY_FILE,
            DeviceActionFirewall.ACTION_RENAME_FILE,
        )
        ) {
            assertEquals(action, ConfirmationLevel.CONFIRM, firewall.levelFor(action))
        }
    }

    @Test
    fun `taps on sensitive controls are escalated to confirmation`() {
        val firewall = DeviceActionFirewall()
        assertEquals(ConfirmationLevel.AUTO, firewall.levelFor(DeviceActionFirewall.ACTION_TAP))
        assertEquals(ConfirmationLevel.CONFIRM, firewall.levelForTap("Pay now"))
        assertEquals(ConfirmationLevel.CONFIRM, firewall.levelForTap("احذف الملف"))
        assertEquals(ConfirmationLevel.CONFIRM, firewall.levelForTap("Send message"))
        assertEquals(ConfirmationLevel.AUTO, firewall.levelForTap("Search"))
        assertEquals(ConfirmationLevel.AUTO, firewall.levelForTap(null))
    }

    @Test
    fun `read-only mode allows readers and refuses state changers`() {
        for (
        action in
        listOf(
            DeviceActionFirewall.ACTION_READ_SCREEN,
            DeviceActionFirewall.ACTION_GET_CURRENT_APP,
            DeviceActionFirewall.ACTION_USB_DEVICES,
            DeviceActionFirewall.ACTION_FASTBOOT_GETVAR,
            DeviceActionFirewall.ACTION_USB_MODE,
            DeviceActionFirewall.ACTION_USB_DIAGNOSTICS,
            DeviceActionFirewall.ACTION_PAYLOAD_GUARD,
            DeviceActionFirewall.ACTION_SAFETY_PREFLIGHT,
            DeviceActionFirewall.ACTION_TERMUX_STATUS,
            DeviceActionFirewall.ACTION_STOP,
        )
        ) {
            assertTrue(action, DeviceActionFirewall.isAllowedInReadOnly(action))
        }
        for (
        action in
        listOf(
            DeviceActionFirewall.ACTION_DELETE_FILE,
            DeviceActionFirewall.ACTION_USB_PUSH,
            DeviceActionFirewall.ACTION_USB_SHELL,
            DeviceActionFirewall.ACTION_TCP_SHELL,
            DeviceActionFirewall.ACTION_SSH_EXEC,
            DeviceActionFirewall.ACTION_MIRROR_START,
            DeviceActionFirewall.ACTION_PAYLOAD_EXTRACT,
            DeviceActionFirewall.ACTION_FASTBOOT_GETVAR_FULL,
            DeviceActionFirewall.ACTION_TERMUX_RUN,
            DeviceActionFirewall.ACTION_TERMUX_FASTBOOT_RUN,
            DeviceActionFirewall.ACTION_MITOOL_WRAPPER,
            DeviceActionFirewall.ACTION_AUDIT_EXPORT,
            "something-the-future-adds",
        )
        ) {
            assertFalse(action, DeviceActionFirewall.isAllowedInReadOnly(action))
        }
    }

    @Test
    fun `the termux and flashing actions land on the confirmation side`() {
        val firewall = DeviceActionFirewall()
        for (
        action in
        listOf(
            DeviceActionFirewall.ACTION_FASTBOOT_GETVAR_FULL,
            DeviceActionFirewall.ACTION_TERMUX_RUN,
            DeviceActionFirewall.ACTION_TERMUX_FASTBOOT_RUN,
            DeviceActionFirewall.ACTION_MITOOL_WRAPPER,
            DeviceActionFirewall.ACTION_AUDIT_EXPORT,
        )
        ) {
            assertEquals(action, ConfirmationLevel.CONFIRM, firewall.levelFor(action))
        }
        assertEquals(ConfirmationLevel.AUTO, firewall.levelFor(DeviceActionFirewall.ACTION_USB_DIAGNOSTICS))
        assertEquals(ConfirmationLevel.AUTO, firewall.levelFor(DeviceActionFirewall.ACTION_SAFETY_PREFLIGHT))
    }

    @Test
    fun `user overrides take precedence`() {
        val firewall = DeviceActionFirewall(mapOf(DeviceActionFirewall.ACTION_TAP to ConfirmationLevel.STRONG_CONFIRM))
        assertEquals(ConfirmationLevel.STRONG_CONFIRM, firewall.levelFor(DeviceActionFirewall.ACTION_TAP))
        // STRONG_CONFIRM taps stay strong even on innocuous labels.
        assertEquals(ConfirmationLevel.STRONG_CONFIRM, firewall.levelForTap("Search"))
    }

    @Test
    fun `invalid override keys are dropped`() {
        val firewall = DeviceActionFirewall(mapOf("hack_the_planet" to ConfirmationLevel.AUTO))
        assertEquals(ConfirmationLevel.AUTO, firewall.levelFor("hack_the_planet"))
    }

    @Test
    fun `sensitive keyword detection is case insensitive`() {
        assertTrue(DeviceActionFirewall.containsSensitiveKeyword("CHECKOUT"))
        assertFalse(DeviceActionFirewall.containsSensitiveKeyword("settings"))
    }
}
