package com.mushrea.code.core.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a route lives decides whether the platform will use it, so the classification has to be a
 * *measurement* of the address - not a guess from a hostname, and not "it is private because it looks
 * unusual". These cases are the real shapes an ADB endpoint arrives in.
 */
class NetworkScopeTest {
    @Test
    fun `a loopback address is the closest thing there is`() {
        assertEquals(NetworkScope.LOOPBACK, NetworkScope.of("127.0.0.1"))
        assertEquals(NetworkScope.LOOPBACK, NetworkScope.of("127.0.0.1:5555"))
        assertEquals(NetworkScope.LOOPBACK, NetworkScope.of("[::1]:5555"))
        assertTrue(NetworkScope.LOOPBACK.private)
        assertFalse(NetworkScope.LOOPBACK.crossesPublicNetwork)
    }

    @Test
    fun `private ranges are the local network`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.4.4", "172.31.255.1").forEach { address ->
            assertEquals(address, NetworkScope.LAN, NetworkScope.of(address))
        }
        assertEquals(NetworkScope.LOCAL_LINK, NetworkScope.of("169.254.10.10"))
        assertEquals(NetworkScope.LOCAL_LINK, NetworkScope.of("fe80::1"))
    }

    @Test
    fun `the address space a private overlay hands out is not called a local network`() {
        // 100.64.0.0/10 is Tailscale's (and Headscale's) carrier-grade range: private, but private
        // *because a tunnel routes it*, which is a different fact from sitting on the same Wi-Fi.
        assertEquals(NetworkScope.PRIVATE_OVERLAY, NetworkScope.of("100.101.102.103"))
        assertEquals(NetworkScope.PRIVATE_OVERLAY, NetworkScope.of("fd7a:115c:a1e0::1"))
        assertTrue(NetworkScope.PRIVATE_OVERLAY.crossesPublicNetwork)
        assertTrue(NetworkScope.PRIVATE_OVERLAY.private)
    }

    @Test
    fun `a public address is known to be one`() {
        assertEquals(NetworkScope.PUBLIC_INTERNET, NetworkScope.of("8.8.8.8"))
        assertEquals(NetworkScope.PUBLIC_INTERNET, NetworkScope.of("2606:4700::1111"))
        assertFalse(NetworkScope.PUBLIC_INTERNET.private)
    }

    @Test
    fun `a name we cannot classify stays unknown instead of being called public`() {
        // A hostname is not resolved here: DNS is a network operation, and turning "phone.local" into
        // a public-looking verdict would be a guess about a network nobody looked at.
        assertEquals(NetworkScope.UNKNOWN, NetworkScope.of("phone.local"))
        assertEquals(NetworkScope.UNKNOWN, NetworkScope.of(""))
        assertTrue(NetworkScope.UNKNOWN.rank > NetworkScope.PUBLIC_INTERNET.rank)
    }

    @Test
    fun `the interface a route came from breaks the ties the address cannot`() {
        // The same literal means different things on different interfaces, and only the provider knows
        // which one it is.
        assertEquals(NetworkScope.PRIVATE_OVERLAY, NetworkScope.of("192.168.1.20", "tailscale0"))
        assertEquals(NetworkScope.LAN, NetworkScope.of("192.168.1.20", "wlan0"))
        assertEquals(NetworkScope.PUBLIC_INTERNET, NetworkScope.of("10.1.2.3", "rmnet_data0"))
        assertEquals(NetworkScope.LAN, NetworkScope.of("100.70.0.2", "eth0"))
    }

    @Test
    fun `preference order is loopback then local network then overlay then the public internet`() {
        val order =
            listOf(
                NetworkScope.UNKNOWN,
                NetworkScope.PUBLIC_INTERNET,
                NetworkScope.PRIVATE_OVERLAY,
                NetworkScope.LAN,
                NetworkScope.LOCAL_LINK,
                NetworkScope.LOOPBACK,
            ).sortedBy { it.rank }

        assertEquals(
            listOf(
                NetworkScope.LOOPBACK,
                NetworkScope.LOCAL_LINK,
                NetworkScope.LAN,
                NetworkScope.PRIVATE_OVERLAY,
                NetworkScope.PUBLIC_INTERNET,
                NetworkScope.UNKNOWN,
            ),
            order,
        )
    }
}
