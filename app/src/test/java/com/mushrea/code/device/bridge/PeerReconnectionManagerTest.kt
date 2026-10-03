package com.mushrea.code.device.bridge

import com.mushrea.code.core.connectivity.Endpoint
import com.mushrea.code.core.connectivity.EndpointSource
import com.mushrea.code.core.peer.InMemoryPeerDeviceStore
import com.mushrea.code.core.peer.PeerDevice
import com.mushrea.code.core.peer.PeerDeviceState
import com.mushrea.code.core.peer.PeerIdentityDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnection is where a remote setup either feels like a product or eats the battery: the phone
 * slept, Wi-Fi handed over, the port changed. These tests pin the two things that decide which one it
 * is - the *order* of attempts and the *wait* between them - plus the rule that a route is only adopted
 * when the device behind it is the device we asked for.
 */
class PeerReconnectionManagerTest {
    private val serial = "adb-37123XYZ._adb-tls-connect._tcp"
    private val identity = PeerIdentityDigest.of(serial, model = "Pixel 7")

    private fun registry(device: PeerDevice = known()): PeerDeviceRegistry =
        PeerDeviceRegistry(store = InMemoryPeerDeviceStore().apply { save(listOf(device)) })

    private fun known(
        host: String = "192.168.1.20",
        port: Int = 37123,
        endpoints: List<String> = emptyList(),
    ) = PeerDevice(
        serial = serial,
        host = host,
        port = port,
        state = PeerDeviceState.DISCONNECTED,
        identityKey = identity,
        knownEndpoints = endpoints,
    )

    /**
     * A manager wired to fakes, with two things the tests need: the waits it asked for, and [connect]
     * last so a trailing lambda means "the connect attempt".
     */
    private fun manager(
        device: PeerDevice,
        discover: suspend (PeerDevice) -> List<Endpoint> = { emptyList() },
        connect: suspend (PeerDevice, Endpoint) -> Result<PeerDevice>,
    ): Pair<PeerReconnectionManager, MutableList<Long>> {
        val waits = mutableListOf<Long>()
        val registry = registry(device)
        val manager =
            PeerReconnectionManager(
                device = { serial -> registry.find(serial) },
                discover = discover,
                connect = connect,
                sleep = { millis -> waits += millis },
            )
        return manager to waits
    }

    @Test
    fun `the wait grows and then stops growing`() {
        val policy = ReconnectPolicy(attempts = 6, initialDelayMillis = 1_000, maxDelayMillis = 8_000, factor = 2)

        assertEquals(0, policy.delayBefore(1))
        assertEquals(1_000, policy.delayBefore(2))
        assertEquals(2_000, policy.delayBefore(3))
        assertEquals(4_000, policy.delayBefore(4))
        assertEquals(8_000, policy.delayBefore(5))
        assertEquals("the cap holds the wait down", 8_000, policy.delayBefore(6))
    }

    @Test
    fun `a remembered route that answers ends the run at ready`() = runBlocking {
        val endpoint = Endpoint("192.168.1.20", 37123, EndpointSource.REMEMBERED)
        val (manager, waits) = manager(known()) { _, _ ->
            Result.success(known().copy(state = PeerDeviceState.VERIFIED))
        }

        val report = manager.reconnect(serial, ReconnectPolicy(attempts = 3))

        assertTrue(report.ready)
        assertEquals(ReconnectionState.READY, report.state)
        assertEquals("the first attempt waits for nothing", listOf<Long>(), waits)
        assertTrue(report.steps.any { it.state == ReconnectionState.CONNECTING && it.endpoint?.key == endpoint.key })
        assertTrue("the rung between a socket and a device is reported", report.steps.any { it.state == ReconnectionState.VERIFYING })
    }

    @Test
    fun `a stale address is not retried, and the second attempt discovers the new one`() = runBlocking {
        val liveEndpoint = Endpoint("192.168.1.44", 41234, EndpointSource.ANNOUNCED)
        val tried = mutableListOf<String>()
        val (manager, waits) =
            manager(
                device = known(host = "192.168.1.20", port = 37123),
                connect = { device, endpoint ->
                    tried += endpoint.key
                    if (endpoint.key == liveEndpoint.key) {
                        Result.success(device.copy(host = endpoint.address, port = endpoint.port, state = PeerDeviceState.VERIFIED))
                    } else {
                        Result.failure(IllegalStateException("connection refused"))
                    }
                },
                discover = { listOf(liveEndpoint) },
            )

        val report = manager.reconnect(serial, ReconnectPolicy(attempts = 3, initialDelayMillis = 500))

        assertTrue(report.ready)
        assertEquals(listOf("192.168.1.20:37123", "192.168.1.44:41234"), tried)
        assertEquals("the wait before the retry is reported", listOf(500L), waits)
    }

    @Test
    fun `a different device at a stale address is refused, not adopted`() = runBlocking {
        val other = known().copy(serial = "adb-999OTHER", identityKey = PeerIdentityDigest.of("adb-999OTHER"))
        val (manager, _) = manager(known()) { _, _ -> Result.success(other) }

        val report = manager.reconnect(serial, ReconnectPolicy(attempts = 2, initialDelayMillis = 0))

        assertEquals(ReconnectionState.GAVE_UP, report.state)
        assertFalse(report.ready)
        assertTrue(report.reason.contains("different device"))
        assertTrue(report.steps.all { it.state != ReconnectionState.VERIFYING })
    }

    @Test
    fun `everything failing gives up with the last reason, not a promise`() = runBlocking {
        val (manager, _) = manager(known()) { _, _ -> Result.failure(IllegalStateException("no route to host")) }

        val report = manager.reconnect(serial, ReconnectPolicy(attempts = 3, initialDelayMillis = 0))

        assertEquals(ReconnectionState.GAVE_UP, report.state)
        assertTrue(report.reason.contains("no route to host"))
        assertEquals(3, report.steps.count { it.state == ReconnectionState.CONNECTING })
        assertTrue(report.summary().contains("could not reconnect"))
    }

    @Test
    fun `an unknown identity is not guessed at`() = runBlocking {
        val registry = PeerDeviceRegistry(store = InMemoryPeerDeviceStore())
        val manager =
            PeerReconnectionManager(
                device = { name -> registry.find(name) },
                discover = { emptyList() },
                connect = { _, _ -> Result.failure(IllegalStateException("should not be called")) },
                sleep = { },
            )

        val report = manager.reconnect("nobody")

        assertEquals(ReconnectionState.GAVE_UP, report.state)
        assertTrue(report.reason.contains("no device with that identity"))
    }

    @Test
    fun `extra endpoints from a caller are tried without being remembered`() = runBlocking {
        val extra = Endpoint("127.0.0.1", 5555, EndpointSource.EXPLICIT)
        val tried = mutableListOf<String>()
        val (manager, _) = manager(known()) { device, endpoint ->
            tried += endpoint.key
            if (endpoint.key == extra.key) {
                Result.success(device.copy(state = PeerDeviceState.VERIFIED))
            } else {
                Result.failure(IllegalStateException("connection refused"))
            }
        }

        val report = manager.reconnect(serial, ReconnectPolicy(attempts = 1), listOf(extra))

        assertTrue(report.ready)
        assertTrue(tried.contains(extra.key))
    }
}
