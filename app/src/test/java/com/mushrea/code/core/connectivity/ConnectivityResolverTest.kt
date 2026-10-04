package com.mushrea.code.core.connectivity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver is the "connection discovery" stage: facts in, ranked routes out. What matters here is
 * that the order is a *rule* (closest and best-evidenced first), that two sources describing one route
 * do not become two attempts, and that a provider failing does not lose the others' answers.
 */
class ConnectivityResolverTest {
    private class FakeEndpointProvider(
        override val id: String,
        override val transportId: String,
        private val offered: List<Endpoint>,
        private val explode: Boolean = false,
    ) : EndpointProvider {
        var asked: RemoteTarget? = null

        override suspend fun endpoints(target: RemoteTarget): List<Endpoint> {
            asked = target
            if (explode) throw IllegalStateException("$id is broken")
            return offered
        }
    }

    private class FakeFacilityProvider(
        override val id: String,
        override val facility: ConnectivityFacility,
        private val facilities: Set<ConnectivityFacility>,
        private val addresses: List<LocalAddress> = emptyList(),
    ) : ConnectivityProvider {
        override suspend fun report(): ConnectivityReport = ConnectivityReport.of(facilities, addresses)
    }

    private fun target() = RemoteTarget(identityKey = "device-1", names = setOf("adb-123._adb-tls-connect._tcp"))

    @Test
    fun `routes are ranked by how close and how well evidenced they are`() = runBlocking {
        val resolver =
            ConnectivityResolver(
                endpointProviders =
                    listOf(
                        FakeEndpointProvider(
                            "remembered",
                            "peer-adb-tcp",
                            listOf(Endpoint("192.168.1.20", 37123, EndpointSource.REMEMBERED)),
                        ),
                        FakeEndpointProvider(
                            "announced",
                            "peer-adb-tcp",
                            listOf(Endpoint("100.101.102.103", 37123, EndpointSource.ANNOUNCED)),
                        ),
                        FakeEndpointProvider(
                            "handover",
                            "peer-adb-usb",
                            listOf(Endpoint("127.0.0.1", 5555, EndpointSource.HANDOVER)),
                        ),
                    ),
            )

        val routes = resolver.routes(target(), ConnectivityReport.unknown())

        // Loopback first: it never leaves the device. Then the local network, then the overlay.
        assertEquals(
            listOf(NetworkScope.LOOPBACK, NetworkScope.LAN, NetworkScope.PRIVATE_OVERLAY),
            routes.all.map { it.scope },
        )
        assertEquals("peer-adb-usb", routes.best()?.transportId)
    }

    @Test
    fun `the same route offered twice is one attempt, described by the stronger evidence`() = runBlocking {
        val resolver =
            ConnectivityResolver(
                endpointProviders =
                    listOf(
                        FakeEndpointProvider(
                            "remembered",
                            "peer-adb-tcp",
                            listOf(Endpoint("192.168.1.20", 37123, EndpointSource.REMEMBERED)),
                        ),
                        FakeEndpointProvider("handover", "peer-adb-usb", listOf(Endpoint("192.168.1.20", 37123, EndpointSource.HANDOVER))),
                    ),
            )

        val routes = resolver.routes(target(), ConnectivityReport.unknown())

        assertEquals(1, routes.all.size)
        assertEquals(EndpointSource.HANDOVER, routes.best()?.endpoint?.source)
        assertEquals("peer-adb-usb", routes.best()?.transportId)
        assertTrue(routes.best()?.reason.orEmpty().contains("handed over"))
    }

    @Test
    fun `a provider that fails does not take the others' routes with it`() = runBlocking {
        val resolver =
            ConnectivityResolver(
                endpointProviders =
                    listOf(
                        FakeEndpointProvider("mdns", "peer-adb-tcp", emptyList(), explode = true),
                        FakeEndpointProvider("remembered", "peer-adb-tcp", listOf(Endpoint("10.0.0.9", 4100, EndpointSource.REMEMBERED))),
                    ),
            )

        val routes = resolver.routes(target(), ConnectivityReport.unknown())

        assertEquals(1, routes.all.size)
        assertEquals("10.0.0.9", routes.best()?.endpoint?.address)
    }

    @Test
    fun `a route with no local interface for its scope is planned but not described as reachable`() = runBlocking {
        val resolver =
            ConnectivityResolver(
                endpointProviders =
                    listOf(
                        FakeEndpointProvider(
                            "remembered",
                            "peer-adb-tcp",
                            listOf(Endpoint("192.168.1.20", 37123, EndpointSource.REMEMBERED)),
                        ),
                    ),
            )

        val offline = resolver.routes(target(), ConnectivityReport.unknown())
        val online = resolver.routes(target(), ConnectivityReport.of(setOf(ConnectivityFacility.WIFI)))

        assertTrue("a route is still offered when nothing is up", offline.best() != null)
        assertTrue(offline.best()?.reason.orEmpty().contains("no local interface"))
        assertTrue(online.best()?.reason.orEmpty().contains("reachable"))
        assertFalse(offline.isEmpty)
    }

    @Test
    fun `the host report merges facilities and keeps the sentence behind each one`() = runBlocking {
        val resolver =
            ConnectivityResolver(
                providers =
                    listOf(
                        FakeFacilityProvider(
                            "wifi",
                            ConnectivityFacility.WIFI,
                            setOf(ConnectivityFacility.WIFI),
                            listOf(LocalAddress("wlan0", "192.168.1.7")),
                        ),
                        FakeFacilityProvider(
                            "overlay",
                            ConnectivityFacility.PRIVATE_OVERLAY,
                            setOf(ConnectivityFacility.PRIVATE_OVERLAY),
                            listOf(LocalAddress("tailscale0", "100.101.102.103")),
                        ),
                        FakeFacilityProvider("mdns", ConnectivityFacility.MDNS, emptySet()),
                    ),
            )

        val report = resolver.report()

        assertTrue(report.online)
        assertTrue(report.has(ConnectivityFacility.PRIVATE_OVERLAY))
        assertFalse(report.has(ConnectivityFacility.MDNS))
        assertEquals("100.101.102.103", report.localAddress(NetworkScope.PRIVATE_OVERLAY)?.address)
        assertTrue(report.summary().contains("wifi"))
        // A facility that is down is recorded as down, not silently absent.
        assertTrue(report.detailOf(ConnectivityFacility.MDNS).contains("down"))
    }

    @Test
    fun `an offline host does not claim a route is reachable`() = runBlocking {
        val resolver =
            ConnectivityResolver(
                endpointProviders =
                    listOf(
                        FakeEndpointProvider(
                            "remembered",
                            "peer-adb-tcp",
                            listOf(Endpoint("8.8.8.8", 5555, EndpointSource.REMEMBERED)),
                        ),
                    ),
            )

        val routes = resolver.routes(target(), ConnectivityReport.of(setOf(ConnectivityFacility.MDNS)))

        assertEquals(NetworkScope.PUBLIC_INTERNET, routes.best()?.scope)
        assertFalse(ConnectivityReport.unknown().online)
    }
}
