package com.mushrea.code.device.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The availability evaluator is the only place the catalog's `requires` declarations turn into a
 * real answer, so these tests pin the three behaviours that matter:
 *
 *  * a probe that says "not now" blocks the tool and its reason reaches the caller;
 *  * a requirement the *request* satisfies (a host, a file path, an attached device) never blocks —
 *    guessing there would stop working tools;
 *  * an id that is not in the catalog is not refused by this layer (the permission policy owns
 *    unknown ids).
 *
 * The device probes themselves (`DeviceProbes`) read real Android state and are exercised on a
 * device, not here: `Cannot Verify — Environment Limitation` in the audit.
 */
class DeviceAvailabilityTest {
    private val accessibilityTool = requireNotNull(DeviceToolCatalog.tool("device_status"))
    private val sshTool = requireNotNull(DeviceToolCatalog.tool("ssh_exec"))

    @Test
    fun `a blocked requirement blocks the tool and carries the reason`() {
        val availability =
            DeviceAvailability { requirement ->
                if (requirement == ToolRequirement.ACCESSIBILITY) {
                    ToolAvailability.Blocked("the Mushrea Code accessibility service is not enabled")
                } else {
                    ToolAvailability.Ready
                }
            }

        val verdict = availability.of(accessibilityTool)
        assertTrue("expected Blocked, got $verdict", verdict is ToolAvailability.Blocked)
        assertEquals(
            "the Mushrea Code accessibility service is not enabled",
            (verdict as ToolAvailability.Blocked).reason,
        )
        assertEquals(
            "the reason must be what the bridge reports",
            "the Mushrea Code accessibility service is not enabled",
            availability.blockedReason(accessibilityTool.id),
        )
    }

    @Test
    fun `call-dependent requirements never block`() {
        val availability = DeviceAvailability { ToolAvailability.CallDependent }

        assertEquals(ToolAvailability.Ready, availability.of(sshTool))
        assertNull(availability.blockedReason(sshTool.id))
    }

    @Test
    fun `a single blocked requirement is enough among several`() {
        val availability =
            DeviceAvailability { requirement ->
                if (requirement == ToolRequirement.TERMUX_BRIDGE) {
                    ToolAvailability.Blocked("Termux is not ready: com.termux")
                } else {
                    ToolAvailability.CallDependent
                }
            }

        val termuxTool =
            DeviceToolCatalog.all.first { ToolRequirement.TERMUX_BRIDGE in it.requires && it.requires.size > 1 }
        assertEquals("Termux is not ready: com.termux", availability.blockedReason(termuxTool.id))
    }

    @Test
    fun `an id outside the catalog is left to the permission policy`() {
        val availability = DeviceAvailability { ToolAvailability.Blocked("never") }

        assertNull(availability.blockedReason("no_such_tool"))
    }

    @Test
    fun `blockedTools lists exactly the tools whose requirement failed`() {
        val availability =
            DeviceAvailability { requirement ->
                if (requirement == ToolRequirement.USB_SERIAL_DEVICE) {
                    ToolAvailability.Blocked("no USB serial adapter is attached")
                } else {
                    ToolAvailability.Ready
                }
            }

        val blocked = availability.blockedTools()
        val expected =
            DeviceToolCatalog.all
                .filter { ToolRequirement.USB_SERIAL_DEVICE in it.requires }
                .map { it.id }
        assertEquals(expected, blocked.map { it.first })
        assertTrue("every entry carries a reason", blocked.all { it.second.isNotBlank() })
        assertTrue("only serial tools are blocked", blocked.none { ToolRequirement.USB_SERIAL_DEVICE !in requireNotNull(DeviceToolCatalog.tool(it.first)).requires })
    }
}
