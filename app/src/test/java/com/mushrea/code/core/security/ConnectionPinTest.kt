package com.mushrea.code.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import javax.net.ssl.SSLPeerUnverifiedException

class ConnectionPinTest {
    private val pin = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `a sha256 digest is canonicalised to padded base64`() {
        assertEquals(pin, ConnectionPin.normalize(pin))
        assertEquals(pin, ConnectionPin.normalize("sha256/$pin"))
        assertEquals(pin, ConnectionPin.normalize("SHA256/$pin"))
        assertEquals(pin, ConnectionPin.normalize("  $pin  "))
        assertEquals(pin, ConnectionPin.normalize(pin.removeSuffix("=")))
    }

    @Test
    fun `only 32 byte digests are accepted`() {
        // OkHttp pins the base64 of the 32-byte SHA-256, not a hex digest: the openssl `-sha256`
        // hex output is 64 characters and has to be rejected rather than half-understood.
        assertNull(ConnectionPin.normalize("a".repeat(64)))
        assertNull(ConnectionPin.normalize(Base64.getEncoder().encodeToString(ByteArray(20))))
        assertNull(ConnectionPin.normalize(Base64.getEncoder().encodeToString(ByteArray(31))))
        assertNull(ConnectionPin.normalize(Base64.getEncoder().encodeToString(ByteArray(33))))
    }

    @Test
    fun `blank and garbage produce no pin`() {
        assertNull(ConnectionPin.normalize(null))
        assertNull(ConnectionPin.normalize(""))
        assertNull(ConnectionPin.normalize("   "))
        assertNull(ConnectionPin.normalize("not-a-pin"))
        assertNull(ConnectionPin.normalize("sha256/"))
    }

    @Test
    fun `a pin failure is recognised, a hostname failure is not`() {
        assertTrue(
            ConnectionPin.isMismatch(
                SSLPeerUnverifiedException("Certificate pinning failure!\n  Peer certificate chain:"),
            ),
        )
        // The same exception type with a different problem: left alone so the fix is not mistaken
        // for a pin mismatch, which the pin mismatch message would falsely claim.
        assertFalse(
            ConnectionPin.isMismatch(
                SSLPeerUnverifiedException("Hostname opencode.example.com not verified:"),
            ),
        )
        assertTrue(
            ConnectionPin.isMismatch(
                IOException(
                    "connection failed",
                    SSLPeerUnverifiedException("Certificate pinning failure!"),
                ),
            ),
        )
        assertFalse(ConnectionPin.isMismatch(IOException("timeout")))
        assertFalse(ConnectionPin.isMismatch(null))
    }
}
