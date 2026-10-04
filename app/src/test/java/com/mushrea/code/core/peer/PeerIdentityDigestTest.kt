package com.mushrea.code.core.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity is what survives a changed port and a changed network - and what must *not* survive a
 * different phone. Both halves are load-bearing: the first is why reconnection works without pairing
 * again, and the second is why a stale address is never adopted silently.
 */
class PeerIdentityDigestTest {
    @Test
    fun `the same device always hashes the same, whatever its address is`() {
        val first = PeerIdentityDigest.of("adb-37123XYZ", model = "Pixel 7", androidVersion = "14", sdk = 34)
        val second = PeerIdentityDigest.of("adb-37123XYZ", model = "Pixel 7", androidVersion = "14", sdk = 34)

        assertEquals(first, second)
        assertEquals("stable, so it can be stored and compared", first.length, second.length)
    }

    @Test
    fun `a different device hashes differently even with the same model`() {
        val mine = PeerIdentityDigest.of("adb-37123XYZ", model = "Pixel 7")
        val other = PeerIdentityDigest.of("adb-999OTHER", model = "Pixel 7")

        assertNotEquals(mine, other)
    }

    @Test
    fun `identity is insensitive to case and padding, which adb is not`() {
        assertEquals(PeerIdentityDigest.of(" adb-37123XYZ "), PeerIdentityDigest.of("ADB-37123XYZ"))
    }

    @Test
    fun `nothing measured means no identity rather than a hash of nothing`() {
        assertEquals("", PeerIdentityDigest.of(""))
        assertEquals("", PeerIdentityDigest.of("   "))
        assertTrue(PeerIdentityDigest.of("adb-1").isNotBlank())
    }

    @Test
    fun `the digest of a report is the digest of the same facts written out`() {
        // One identity, two spellings: a probe answers with a PeerIdentity, a stored row carries the
        // fields, and both must produce the same key or a reconnect would look like a new device.
        val serialOnly = PeerIdentityDigest.of("adb-37123XYZ")
        val withFacts = PeerIdentityDigest.of("adb-37123XYZ", model = "Pixel 7", sdk = 34)

        assertNotEquals("more facts is a more specific identity", serialOnly, withFacts)
        assertEquals(PeerIdentityDigest.of("adb-37123XYZ", PeerIdentity(model = "Pixel 7", sdk = 34)), withFacts)
    }

    @Test
    fun `trust is a level and pairing is the rung before approval`() {
        assertTrue(PeerTrust.USER_APPROVED.mayReconnect)
        assertTrue(PeerTrust.TOFU.mayReconnect)
        assertEquals(false, PeerTrust.UNKNOWN.mayReconnect)
        assertEquals(false, PeerTrust.REVOKED.mayReconnect)
        assertTrue(PeerTrust.REVOKED.needsUser)
    }
}
