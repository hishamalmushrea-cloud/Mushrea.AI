package com.mushrea.code.core.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored capability map is what survives a restart, so reading it back has to preserve the two
 * facts it carries (measured present, measured absent) and to keep working for maps an older release
 * wrote under plain names.
 */
class CapabilityAliasesTest {
    @Test
    fun `a report round-trips through the stored map`() {
        val report =
            CapabilityReport.of(
                listOf(
                    CapabilityReport.available(CapabilityNames.binary("pm"), "/system/bin/pm"),
                    CapabilityReport.missing(CapabilityNames.binary("python3"), "not on this device"),
                ),
            )

        val stored = CapabilityAliases.store(report)
        val readBack = CapabilityAliases.report(stored)

        assertEquals(mapOf("bin:pm" to "/system/bin/pm", "bin:python3" to ""), stored)
        assertEquals(CapabilityStatus.AVAILABLE, readBack.status(CapabilityNames.binary("pm")))
        assertEquals(CapabilityStatus.MISSING, readBack.status(CapabilityNames.binary("python3")))
    }

    @Test
    fun `a map an older release wrote is read as the same capabilities`() {
        val readBack = CapabilityAliases.report(mapOf("pm" to "/system/bin/pm", "uiautomator" to ""))

        assertEquals(CapabilityStatus.AVAILABLE, readBack.status(CapabilityNames.binary("pm")))
        assertEquals(CapabilityStatus.MISSING, readBack.status(CapabilityNames.binary("uiautomator")))
        // A lookup by the old name finds the canonical capability, because it is the same fact.
        assertEquals("/system/bin/pm", readBack.detail(CapabilityNames.PM))
        assertEquals(CapabilityKind.BINARY, CapabilityKinds.of(CapabilityNames.PM))
    }

    @Test
    fun `a name that is not an alias is left exactly as it is`() {
        assertEquals("svc:input", CapabilityAliases.canonical("svc:input"))
        assertEquals("vendor:extra", CapabilityAliases.canonical("vendor:extra"))
        assertTrue(CapabilityAliases.aliasesOf(CapabilityNames.binary("pm")).contains(CapabilityNames.PM))
    }

    @Test
    fun `a capability the map never mentions stays unknown rather than absent`() {
        val readBack = CapabilityAliases.report(mapOf("bin:pm" to "/system/bin/pm"))

        assertEquals(CapabilityStatus.UNKNOWN, readBack.status(CapabilityNames.binary("cmd")))
        assertFalse(readBack.missing(CapabilityNames.binary("cmd")))
        assertTrue(readBack.unproven(CapabilityNames.binary("cmd")))
    }
}
