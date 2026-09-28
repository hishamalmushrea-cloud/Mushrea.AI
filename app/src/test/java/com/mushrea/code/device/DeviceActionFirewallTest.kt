package com.mushrea.code.device

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
    fun `user overrides take precedence`() {
        val firewall = DeviceActionFirewall(mapOf(DeviceActionFirewall.ACTION_TAP to ConfirmationLevel.STRONG))
        assertEquals(ConfirmationLevel.STRONG, firewall.levelFor(DeviceActionFirewall.ACTION_TAP))
        // STRONG taps stay strong even on innocuous labels.
        assertEquals(ConfirmationLevel.STRONG, firewall.levelForTap("Search"))
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
