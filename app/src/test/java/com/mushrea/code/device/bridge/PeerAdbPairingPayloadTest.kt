package com.mushrea.code.device.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The pairing payload is the one string that has to be exactly right: the other phone's camera
 * handler parses it, and every field means something different from what a Wi-Fi QR means.
 */
class PeerAdbPairingPayloadTest {
    @Test
    fun `the encoded payload is the format AOSP parses`() {
        val payload = PeerAdbPairingPayload(serviceName = "studio-abc1234567", password = "xyz9876543")

        assertEquals("WIFI:T:ADB;S:studio-abc1234567;P:xyz9876543;;", payload.encode())
    }

    @Test
    fun `a random session uses the studio prefix and an unambiguous alphabet`() {
        val payload = PeerAdbPairingPayload.random(Random(7))

        assertTrue(payload.serviceName.startsWith(PeerAdbPairingPayload.DEFAULT_NAME_PREFIX))
        assertEquals(PeerAdbPairingPayload.DEFAULT_NAME_PREFIX.length + 10, payload.serviceName.length)
        assertEquals(10, payload.password.length)
        val token = payload.password
        // No printable character that would confuse a reader, and nothing that breaks the payload.
        assertTrue(
            "no ambiguous characters",
            token.none { it in setOf(';', '\\', 'l', 'o', '0', '1') },
        )
    }

    @Test
    fun `two sessions never share a name`() {
        val first = PeerAdbPairingPayload.random(Random(1))
        val second = PeerAdbPairingPayload.random(Random(2))

        assertFalse(first.serviceName == second.serviceName)
    }

    @Test
    fun `parsing our own payload round-trips`() {
        val payload = PeerAdbPairingPayload.random(Random(3))

        val parsed = PeerAdbPairingPayload.parse(payload.encode()).getOrThrow()

        assertEquals(payload, parsed)
    }

    @Test
    fun `a Wi-Fi QR is refused rather than half-read`() {
        val result = PeerAdbPairingPayload.parse("WIFI:T:WPA;S:MyNetwork;P:hunter2;;")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("not ADB"))
    }

    @Test
    fun `a payload with no password is refused`() {
        val result = PeerAdbPairingPayload.parse("WIFI:T:ADB;S:studio-abc;;")

        assertTrue(result.isFailure)
    }

    @Test
    fun `a payload with no WIFI prefix is refused`() {
        assertTrue(PeerAdbPairingPayload.parse("S:studio-abc;P:xyz;;").isFailure)
        assertTrue(PeerAdbPairingPayload.parse("https://example.com").isFailure)
    }
}
