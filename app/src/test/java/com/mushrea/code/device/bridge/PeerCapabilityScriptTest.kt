package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe that answers "what can this phone actually do?" - and the parser that reads it. */
class PeerCapabilityScriptTest {
    private val sample =
        """
        bin:sh=/system/bin/sh
        bin:toybox=/system/bin/toybox
        bin:cmd=/system/bin/cmd
        bin:pm=/system/bin/pm
        bin:am=/system/bin/am
        bin:dumpsys=/system/bin/dumpsys
        bin:settings=/system/bin/settings
        bin:logcat=/system/bin/logcat
        bin:screencap=/system/bin/screencap
        bin:input=/system/bin/input
        bin:uiautomator=
        bin:su=
        bin:python3=
        bin:bash=/system/bin/bash
        prop:ro.product.model=Pixel 6a
        prop:ro.product.manufacturer=Google
        prop:ro.build.version.release=13
        prop:ro.build.version.sdk=33
        prop:ro.product.cpu.abi=arm64-v8a
        marker=probe-done
        """.trimIndent()

    @Test
    fun `the script is safe to wrap in one quoted argument`() {
        val script = PeerCapabilityScript.script()

        assertFalse("the probe must not contain a single quote", script.contains("'"))
        assertTrue(script.contains("bin:sh="))
        assertTrue(script.contains("prop:ro.product.model="))
    }

    @Test
    fun `a device that answered reports what it has and what it does not`() {
        val report = PeerCapabilityScript.parse(sample)

        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.SHELL))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.PM))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.PACKAGE_MANAGER))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.SYNC))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.BASH))
        assertEquals(CapabilityStatus.MISSING, report.status(CapabilityNames.PYTHON))
        assertEquals(CapabilityStatus.MISSING, report.status(CapabilityNames.UI_AUTOMATOR))
        assertEquals(CapabilityStatus.MISSING, report.status(CapabilityNames.SU))
        assertEquals("/system/bin/pm", report.detail(CapabilityNames.PM))
    }

    @Test
    fun `a device whose shell never answered proves nothing`() {
        val report = PeerCapabilityScript.parse("")

        // Unknown, not missing: the platform must not report a phone as incapable because the probe
        // never reached it.
        assertEquals(CapabilityStatus.UNKNOWN, report.status(CapabilityNames.SHELL))
        assertTrue(report.names.isEmpty())
    }

    @Test
    fun `the identity comes from the same round trip`() {
        val identity = PeerCapabilityScript.identity(sample)

        assertEquals("Pixel 6a", identity.model)
        assertEquals("Google", identity.manufacturer)
        assertEquals("13", identity.androidVersion)
        assertEquals(33, identity.sdk)
        assertEquals("arm64-v8a", identity.abi)
        assertTrue(identity.known)
    }
}
