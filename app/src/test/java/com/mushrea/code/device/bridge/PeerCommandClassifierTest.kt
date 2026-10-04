package com.mushrea.code.device.bridge

import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.core.permission.PermissionRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier is a *friction* mechanism, not a permission system: it can only raise what an
 * operation costs, never lower it, and anything it does not recognise is treated as writing. These
 * tests pin both halves - that the reads stay cheap, and that the unknown never becomes a bypass.
 */
class PeerCommandClassifierTest {
    @Test
    fun `a known reader is read-only and cheap`() {
        val verdict = PeerCommandClassifier.classify("getprop ro.product.model")

        assertEquals(PeerCommandClass.READ_ONLY, verdict.commandClass)
        assertEquals(PermissionRisk.LOW, verdict.risk)
        assertFalse(verdict.mutatesTarget)
        assertFalse(verdict.requiresStrongConfirmation)
        assertEquals("getprop", verdict.program)
    }

    @Test
    fun `listing packages is a read even though pm itself can write`() {
        val verdict = PeerCommandClassifier.classify("pm list packages -3")

        assertEquals(PeerCommandClass.READ_ONLY, verdict.commandClass)
        assertFalse(verdict.mutatesTarget)
    }

    @Test
    fun `listing through cmd is a read too, though cmd itself can write`() {
        // The catalogue's second route on a phone without `pm`. It must not refuse itself: the
        // generic "cmd " prefix is writing-shaped, but the whole segment is a known reader.
        val verdict = PeerCommandClassifier.classify("cmd package list packages")

        assertEquals(PeerCommandClass.READ_ONLY, verdict.commandClass)
        assertFalse(verdict.mutatesTarget)
        // And the destructive half still wins over the reader prefix.
        assertEquals(PeerCommandClass.DESTRUCTIVE, PeerCommandClassifier.classify("cmd package list; rm -rf /sdcard").commandClass)
    }

    @Test
    fun `installing and uninstalling are not the same thing`() {
        val install = PeerCommandClassifier.classify("pm install /sdcard/app.apk")
        val uninstall = PeerCommandClassifier.classify("pm uninstall com.example.app")

        assertEquals(PeerCommandClass.STATE_CHANGING, install.commandClass)
        assertTrue(install.mutatesTarget)
        assertFalse(install.requiresStrongConfirmation)
        assertEquals(PeerCommandClass.DESTRUCTIVE, uninstall.commandClass)
        assertTrue(uninstall.requiresStrongConfirmation)
        assertEquals(PermissionRisk.HIGH, uninstall.risk)
    }

    @Test
    fun `a destructive command hidden behind a separator still counts`() {
        val verdict = PeerCommandClassifier.classify("pm list packages; rm -rf /sdcard/Download")

        assertEquals(PeerCommandClass.DESTRUCTIVE, verdict.commandClass)
        assertTrue(verdict.requiresStrongConfirmation)
    }

    @Test
    fun `gaining root is privileged whatever it wraps`() {
        val verdict = PeerCommandClassifier.classify("su -c 'pm list packages'")

        assertEquals(PeerCommandClass.PRIVILEGED, verdict.commandClass)
        // Privileged outranks destructive in the order, and both need the strong confirmation.
        assertTrue(verdict.requiresStrongConfirmation)
        assertEquals(PermissionRisk.HIGH, verdict.risk)
    }

    @Test
    fun `an unknown program is treated as writing, not as safe`() {
        val verdict = PeerCommandClassifier.classify("frobnicate --all")

        assertEquals(PeerCommandClass.STATE_CHANGING, verdict.commandClass)
        assertTrue(verdict.mutatesTarget)
        assertEquals(PermissionRisk.MEDIUM, verdict.risk)
    }

    @Test
    fun `an argv is classified like the line it would become`() {
        val verdict = PeerCommandClassifier.classify("/system/bin/pm", listOf("list", "packages"))

        assertEquals(PeerCommandClass.READ_ONLY, verdict.commandClass)
    }

    @Test
    fun `the classes map to the platform's confirmation levels`() {
        assertEquals(ConfirmationLevel.AUTO, levelOf("getprop ro.product.model"))
        assertEquals(ConfirmationLevel.CONFIRM, levelOf("mkdir /sdcard/x"))
        assertEquals(ConfirmationLevel.STRONG_CONFIRM, levelOf("rm -rf /sdcard/x"))
    }

    private fun levelOf(command: String): ConfirmationLevel {
        val verdict = PeerCommandClassifier.classify(command)
        return when {
            verdict.requiresStrongConfirmation -> ConfirmationLevel.STRONG_CONFIRM
            verdict.mutatesTarget -> ConfirmationLevel.CONFIRM
            else -> ConfirmationLevel.AUTO
        }
    }
}
