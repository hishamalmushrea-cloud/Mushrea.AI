package com.mushrea.code.device.bridge

import com.mushrea.code.core.connectivity.Endpoint
import com.mushrea.code.core.connectivity.EndpointSource
import com.mushrea.code.core.peer.InMemoryPeerDeviceStore
import com.mushrea.code.core.peer.PeerDevice
import com.mushrea.code.core.peer.PeerDeviceState
import com.mushrea.code.core.peer.PeerIdentityDigest
import com.mushrea.code.core.peer.PeerTrust
import com.mushrea.code.core.provisioning.DeviceReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the platform remembers between sessions is what makes the second connection cheap - so the
 * merge rules are part of the product, not bookkeeping: a field nobody measured must not erase an
 * answer, and a level that was *proven* must not be lowered by a later, weaker event.
 */
class ProvisioningRegistryTest {
    private val serial = "adb-37123XYZ._adb-tls-connect._tcp"

    private fun registry() = PeerDeviceRegistry(store = InMemoryPeerDeviceStore())

    @Test
    fun `a device keeps its identity through announcements that know nothing about it`() {
        val registry = registry()
        val identity = PeerIdentityDigest.of(serial, model = "Pixel 7")
        registry.remember(PeerDevice(serial = serial, identityKey = identity, trust = PeerTrust.TOFU, state = PeerDeviceState.VERIFIED))

        // A later announcement carries only an address; the digest and the trust must survive it.
        registry.discovered(
            PeerAdbService(
                type = PeerAdbServiceType.CONNECT,
                // The announcement carries the bare instance name; adb's serial is that name plus the
                // type suffix, which is exactly the serial this device already has.
                instanceName = "adb-37123XYZ",
                host = "192.168.1.30",
                port = 41000,
            ),
        )

        val device = registry.find(serial)
        assertEquals(identity, device?.identityKey)
        assertEquals(PeerTrust.TOFU, device?.trust)
        assertEquals("192.168.1.30", device?.host)
    }

    @Test
    fun `a proven level is never lowered by a later weaker event`() {
        val registry = registry()
        registry.readiness(serial, DeviceReadiness.EXECUTION_VERIFIED)
        registry.readiness(serial, DeviceReadiness.CONNECTED)
        registry.state(serial, PeerDeviceState.CONNECTED)

        assertEquals(DeviceReadiness.EXECUTION_VERIFIED, registry.find(serial)?.readiness)
    }

    @Test
    fun `trust moves up by itself and down only when it is written down`() {
        val registry = registry()
        registry.trust(serial, PeerTrust.TOFU)
        registry.trust(serial, PeerTrust.UNKNOWN)

        assertEquals("an event that knows nothing about trust does not revoke it", PeerTrust.TOFU, registry.find(serial)?.trust)

        registry.trust(serial, PeerTrust.REVOKED)

        assertEquals(PeerTrust.REVOKED, registry.find(serial)?.trust)
    }

    @Test
    fun `routes are remembered newest first, without duplicates and without growing forever`() {
        val registry = registry()
        (1..12).forEach { index ->
            registry.rememberEndpoint(serial, Endpoint("192.168.1.$index", 37123, EndpointSource.REMEMBERED))
        }
        registry.rememberEndpoint(serial, Endpoint("192.168.1.12", 37123, EndpointSource.ANNOUNCED))

        val device = registry.find(serial)
        assertEquals(PeerDeviceRegistry.MAX_ENDPOINTS, device?.knownEndpoints?.size)
        assertEquals("192.168.1.12:37123", device?.knownEndpoints?.first())
        assertEquals("192.168.1.12:37123", device?.knownEndpoints?.distinct()?.first())
    }

    @Test
    fun `a device can be turned back into a target with the routes that used to work`() {
        val registry = registry()
        registry.rememberEndpoint(serial, Endpoint("192.168.1.20", 37123, EndpointSource.REMEMBERED))
        registry.state(serial, PeerDeviceState.VERIFIED)

        val target = registry.find(serial)?.remoteTarget()

        assertEquals(serial, target?.identityKey)
        assertTrue(target?.names.orEmpty().contains(serial))
        assertEquals("192.168.1.20:37123", target?.hints?.first()?.key)
    }
}
