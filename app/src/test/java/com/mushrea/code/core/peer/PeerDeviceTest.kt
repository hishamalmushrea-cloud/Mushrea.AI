package com.mushrea.code.core.peer

import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.core.execution.ExecutionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peer record is the platform's memory of another phone: what it is, what it can do, and how far
 * it was taken. These tests pin the three things the rest of the path depends on - the label a user
 * reads, the target an execution names (never an implicit device), and the capability map that a
 * planner consults.
 */
class PeerDeviceTest {
    private fun device(
        state: PeerDeviceState = PeerDeviceState.DISCOVERED,
        capabilities: Map<String, String> = emptyMap(),
    ) = PeerDevice(
        serial = "192.168.1.40:5555",
        instanceName = "studio-abcd123456",
        host = "192.168.1.40",
        port = 37_123,
        state = state,
        model = "Pixel 6a",
        androidVersion = "13",
        sdk = 33,
        capabilities = capabilities,
    )

    @Test
    fun `a device with a model is labelled by it, and falls back to the serial otherwise`() {
        assertEquals("Pixel 6a", device().label)
        assertEquals("192.168.1.40:5555", device().copy(model = "").label)
    }

    @Test
    fun `the execution target is the device itself, named explicitly`() {
        val target = device().target()

        assertEquals("192.168.1.40:5555", target.id)
        assertEquals(ExecutionTransport.PEER_ADB, target.transport)
        assertEquals("Pixel 6a", target.label)
    }

    @Test
    fun `only a verified device is ready for execution`() {
        assertTrue(device(PeerDeviceState.VERIFIED).readyForExecution)
        listOf(
            PeerDeviceState.DISCOVERED,
            PeerDeviceState.PAIRED,
            PeerDeviceState.CONNECTED,
            PeerDeviceState.DISCONNECTED,
        ).forEach { state ->
                assertFalse("$state is not proof the phone answers", device(state).readyForExecution)
            }
    }

    @Test
    fun `connected means a channel exists, not that anything ran on it`() {
        assertTrue(device(PeerDeviceState.CONNECTED).connected)
        assertTrue(device(PeerDeviceState.VERIFIED).connected)
        assertFalse(device(PeerDeviceState.PAIRED).connected)
        assertFalse(device(PeerDeviceState.DISCONNECTED).connected)
    }

    @Test
    fun `a stored capability value becomes available, a blank one means the device said no`() {
        val report =
            device(
                capabilities = mapOf(
                    CapabilityNames.SHELL to "/system/bin/sh",
                    CapabilityNames.PYTHON to "",
                ),
            ).capabilityReport()

        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.SHELL))
        assertEquals("/system/bin/sh", report.detail(CapabilityNames.SHELL))
        assertEquals(CapabilityStatus.MISSING, report.status(CapabilityNames.PYTHON))
    }

    @Test
    fun `a capability that was never probed stays unknown rather than missing`() {
        val report = device(capabilities = emptyMap()).capabilityReport()

        assertEquals(CapabilityStatus.UNKNOWN, report.status(CapabilityNames.UI_AUTOMATOR))
        assertFalse("unknown must not read as a measured absence", report.missing(CapabilityNames.UI_AUTOMATOR))
    }

    @Test
    fun `a probe round-trips through the record without inventing capabilities`() {
        val report =
            CapabilityReport.of(
                listOf(
                    CapabilityReport.available(CapabilityNames.PM, "/system/bin/pm"),
                    CapabilityReport.missing(CapabilityNames.CMD, "not on this device"),
                ),
            )

        val stored = device().withCapabilities(report)
        val readBack = stored.capabilityReport()

        assertEquals("/system/bin/pm", readBack.detail(CapabilityNames.PM))
        assertEquals(CapabilityStatus.MISSING, readBack.status(CapabilityNames.CMD))
        assertEquals(2, stored.capabilities.size)
    }

    @Test
    fun `changing state does not disturb the identity learned from the device`() {
        val verified = device().withState(PeerDeviceState.VERIFIED)

        assertEquals(PeerDeviceState.VERIFIED, verified.state)
        assertEquals("Pixel 6a", verified.model)
        assertEquals(33, verified.sdk)
        assertEquals("studio-abcd123456", verified.instanceName)
    }

    @Test
    fun `the codec keeps every field a later reconnection needs`() {
        val original = device(PeerDeviceState.VERIFIED, mapOf(CapabilityNames.SHELL to "/system/bin/sh"))

        val decoded = PeerDeviceCodec.decode(PeerDeviceCodec.encode(listOf(original))).single()

        assertEquals(original, decoded)
        assertTrue("the serial survives the codec", decoded.serial.isNotBlank())
    }

    @Test
    fun `unreadable stored json yields no devices instead of a crash`() {
        assertTrue(PeerDeviceCodec.decode("not json at all").isEmpty())
        assertTrue(PeerDeviceCodec.decode("").isEmpty())
        assertTrue(PeerDeviceCodec.decode("[{\"unknownField\":1}]").isEmpty())
    }
}
