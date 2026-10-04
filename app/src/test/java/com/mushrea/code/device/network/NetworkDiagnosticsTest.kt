package com.mushrea.code.device.network

import com.mushrea.code.device.usb.AdbException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsTest {
    @Test
    fun `iputils ping output is parsed`() {
        val output =
            """
            PING example.com (93.184.216.34) 56(84) bytes of data.
            64 bytes from 93.184.216.34: icmp_seq=1 ttl=56 time=12.3 ms
            64 bytes from 93.184.216.34: icmp_seq=2 ttl=56 time=11.0 ms
            --- example.com ping statistics ---
            4 packets transmitted, 4 received, 0% packet loss, time 3005ms
            rtt min/avg/max/mdev = 11.0/12.4/14.1/1.0 ms
            """.trimIndent()
        val stats = NetworkDiagnostics.parsePingOutput(output)
        assertEquals(4, stats.transmitted)
        assertEquals(4, stats.received)
        assertEquals(0.0, stats.lossPercent)
        assertEquals(11.0, stats.minMs)
        assertEquals(12.4, stats.avgMs)
        assertEquals(14.1, stats.maxMs)
        assertEquals(2, stats.sampleMs.size)
    }

    @Test
    fun `http methods are normalised and unknown ones refused`() {
        assertEquals("GET", NetworkDiagnostics.httpMethod("get"))
        assertEquals("PATCH", NetworkDiagnostics.httpMethod("PATCH"))
        val failure =
            runCatching { NetworkDiagnostics.httpMethod("TRACE") }.exceptionOrNull()
        assertTrue(failure is AdbException)
    }

    @Test
    fun `websocket urls must be ws or wss`() {
        NetworkDiagnostics.requireWebSocketUrl("wss://example.com/socket")
        val failure = runCatching { NetworkDiagnostics.requireWebSocketUrl("https://example.com") }.exceptionOrNull()
        assertTrue(failure is AdbException)
    }

    @Test
    fun `host and port validation fail closed`() {
        assertEquals("example.com", NetworkDiagnostics.requireHost(" example.com "))
        assertEquals(443, NetworkDiagnostics.requirePort(443))
        assertTrue(runCatching { NetworkDiagnostics.requireHost("  ") }.exceptionOrNull() is AdbException)
        assertTrue(runCatching { NetworkDiagnostics.requirePort(0) }.exceptionOrNull() is AdbException)
        assertTrue(runCatching { NetworkDiagnostics.requirePort(70000) }.exceptionOrNull() is AdbException)
    }

    @Test
    fun `wifi band and ipv4 formatting`() {
        assertEquals("2.4 GHz", NetworkDiagnostics.bandOf(2412))
        assertEquals("5 GHz", NetworkDiagnostics.bandOf(5180))
        assertEquals("6 GHz", NetworkDiagnostics.bandOf(6100))
        assertEquals("unknown", NetworkDiagnostics.bandOf(100))
        // little-endian Android WifiInfo.ipAddress for 192.168.1.10
        assertEquals("192.168.1.10", NetworkDiagnostics.intToIpv4(0x0A01A8C0))
    }

    @Test
    fun `clip keeps a short string and the tail of a long one`() {
        assertEquals("abc", NetworkDiagnostics.clip("abc", 10))
        assertEquals("cdef", NetworkDiagnostics.clip("abcdef", 4))
    }
}
