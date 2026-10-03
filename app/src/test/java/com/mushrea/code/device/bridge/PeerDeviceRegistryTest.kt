package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.peer.InMemoryPeerDeviceStore
import com.mushrea.code.core.peer.PeerDeviceCodec
import com.mushrea.code.core.peer.PeerDeviceState
import com.mushrea.code.core.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is where "which phone was that?" is answered, and it is deliberately hard to lose
 * information in: an announcement that carries only an address must not erase the identity learned
 * from the last connection, and a phone that goes quiet keeps its pairing.
 */
class PeerDeviceRegistryTest {
    private val connectService =
        PeerAdbService(
            type = PeerAdbServiceType.CONNECT,
            instanceName = "adb-43081FDAS000VS-QXjCrW",
            host = "192.168.1.20",
            port = 37099,
        )

    @Test
    fun `a discovered device is remembered by the serial adb will use`() {
        val registry = PeerDeviceRegistry()

        val device = registry.discovered(connectService)

        assertEquals("adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp", device.serial)
        assertEquals(PeerDeviceState.DISCOVERED, device.state)
        assertFalse(device.connected)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `a later announcement does not erase the identity or the pairing`() {
        val registry = PeerDeviceRegistry(clock = { 1_000L })
        registry.paired(connectService)
        registry.connected(connectService.adbSerial, connectService.host, connectService.port, PeerIdentity(model = "Pixel 6a", androidVersion = "13", sdk = 33))
        registry.verified(connectService.adbSerial)

        // The phone is rediscovered with nothing but an address (a restart, a new port).
        val port = 38123
        val rediscovered =
            registry.discovered(PeerAdbService(PeerAdbServiceType.CONNECT, connectService.instanceName, connectService.host, port))

        assertEquals("Pixel 6a", rediscovered.model)
        assertEquals("13", rediscovered.androidVersion)
        assertEquals(33, rediscovered.sdk)
        assertEquals(port, rediscovered.port)
        assertEquals(PeerDeviceState.DISCOVERED, rediscovered.state)
        assertFalse("a fresh announcement is not a verified device", rediscovered.state.readyForExecution)
    }

    @Test
    fun `only a verified device is ready for execution`() {
        val registry = PeerDeviceRegistry()
        registry.paired(connectService)

        assertFalse(registry.find(connectService.adbSerial)?.connected == true)

        registry.connected(connectService.adbSerial, connectService.host, connectService.port)
        assertTrue(registry.find(connectService.adbSerial)?.connected == true)
        assertFalse(registry.find(connectService.adbSerial)?.state?.readyForExecution == true)

        registry.verified(connectService.adbSerial, PeerIdentity(model = "Pixel 6a"))
        assertTrue(registry.find(connectService.adbSerial)?.state?.readyForExecution == true)
    }

    @Test
    fun `capabilities survive a round trip through the store`() {
        val store = InMemoryPeerDeviceStore()
        val registry = PeerDeviceRegistry(store = store)
        registry.verified(connectService.adbSerial)
        val report =
            CapabilityReport.of(
                listOf(
                    CapabilityReport.available(CapabilityNames.SHELL, "shell answered"),
                    CapabilityReport.missing(CapabilityNames.PYTHON, "not on this device"),
                ),
            )

        registry.capabilities(connectService.adbSerial, report)

        // A second registry reading the same store sees what was discovered: this is what makes the
        // settings-backed store useful rather than write-only.
        val reopened = PeerDeviceRegistry(store = store)
        val device = reopened.find(connectService.adbSerial)
        assertNotNull(device)
        assertTrue(device!!.capabilityReport().has(CapabilityNames.SHELL))
        assertTrue(device.capabilityReport().missing(CapabilityNames.PYTHON))
        assertEquals("shell answered", device.capabilityReport().detail(CapabilityNames.SHELL))
    }

    @Test
    fun `forgetting a device removes it and reports whether anything was removed`() {
        val registry = PeerDeviceRegistry()
        registry.paired(connectService)

        assertTrue(registry.forget(connectService.adbSerial))
        assertNull(registry.find(connectService.adbSerial))
        assertFalse(registry.forget(connectService.adbSerial))
    }

    @Test
    fun `the state machine can move a device backwards when the channel drops`() {
        val registry = PeerDeviceRegistry()
        registry.verified(connectService.adbSerial)

        val disconnected = registry.state(connectService.adbSerial, PeerDeviceState.DISCONNECTED)

        assertEquals(PeerDeviceState.DISCONNECTED, disconnected.state)
        assertFalse(disconnected.connected)
        assertTrue("the pairing record stays", registry.all().isNotEmpty())
    }

    @Test
    fun `the target names the device and never a default`() {
        val registry = PeerDeviceRegistry()
        registry.verified(connectService.adbSerial, PeerIdentity(model = "Pixel 6a"))

        val target = registry.find(connectService.adbSerial)!!.target()

        assertEquals(connectService.adbSerial, target.id)
        assertEquals("Pixel 6a", target.label)
        assertEquals(com.mushrea.code.core.execution.ExecutionTransport.PEER_ADB, target.transport)
    }

    @Test
    fun `a store holding junk decodes to no devices instead of a crash`() {
        // The settings-backed store can be edited by a future version, an import or a bug; a device
        // list that fails to parse must read as empty, not take the app down.
        assertTrue(PeerDeviceCodec.decode("not json at all").isEmpty())
        assertTrue(PeerDeviceCodec.decode("").isEmpty())
        assertTrue(PeerDeviceCodec.decode("[]").isEmpty())
    }
}
