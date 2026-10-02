package com.mushrea.code.feature.workspace

import com.mushrea.code.core.connection.ConnectionProfile
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormStateTest {
    @Test
    fun `save is disabled when name or endpoint is missing`() {
        assertFalse(ConnectionFormState().canSave)
        assertFalse(ConnectionFormState(name = "Mac mini").canSave)
        assertFalse(ConnectionFormState(baseUrl = "192.168.1.10:4096").canSave)
    }

    @Test
    fun `save is enabled for valid LAN and HTTPS endpoints`() {
        assertTrue(
            ConnectionFormState(
                name = "Mac mini",
                baseUrl = "192.168.1.10:4096",
                allowInsecureLan = true,
            ).canSave,
        )
        assertTrue(
            ConnectionFormState(
                name = "Server",
                baseUrl = "https://opencode.example.com",
            ).canSave,
        )
    }

    @Test
    fun `plain LAN endpoint is saveable without a separate cleartext opt-in`() {
        val form = ConnectionFormState(name = "Mac mini", baseUrl = "192.168.1.10:4096")

        assertTrue(form.canSave)
        val profile = form.toProfile()
        assertEquals("http://192.168.1.10:4096/", profile.baseUrl)
        assertTrue(profile.allowInsecureLan)
    }

    @Test
    fun `https endpoint is not marked as allowing cleartext`() {
        val profile =
            ConnectionFormState(
                name = "Server",
                baseUrl = "https://opencode.example.com",
            ).toProfile()

        assertFalse(profile.allowInsecureLan)
    }

    @Test
    fun `public cleartext endpoint cannot be saved`() {
        assertFalse(
            ConnectionFormState(
                name = "Unsafe",
                baseUrl = "http://example.com:4096",
                allowInsecureLan = true,
            ).canSave,
        )
    }

    @Test
    fun `pin must be a base64 sha256 digest before the connection can be saved`() {
        val base = ConnectionFormState(name = "Server", baseUrl = "https://opencode.example.com")

        assertFalse(base.copy(pinSha256 = "a".repeat(64)).canSave)
        assertFalse(base.copy(pinSha256 = "not-a-pin").canSave)
        assertTrue(base.copy(pinSha256 = VALID_PIN).canSave)
    }

    @Test
    fun `pin survives an edit round trip instead of being dropped`() {
        val stored =
            ConnectionProfile(
                id = "p1",
                name = "Server",
                baseUrl = "https://opencode.example.com",
                pinSha256 = VALID_PIN,
            )

        val form = ConnectionFormState.from(stored)

        assertEquals(VALID_PIN, form.pinSha256)
        assertEquals(VALID_PIN, form.toProfile().pinSha256)
    }

    @Test
    fun `pin is stored canonically and can be cleared`() {
        val form =
            ConnectionFormState(
                name = "Server",
                baseUrl = "https://opencode.example.com",
                pinSha256 = "  sha256/${VALID_PIN.removeSuffix("=")}  ",
            )

        assertEquals(VALID_PIN, form.toProfile().pinSha256)
        assertNull(form.copy(pinSha256 = "").toProfile().pinSha256)
    }

    @Test
    fun `pin on a plaintext lan endpoint is reported as ignored`() {
        assertTrue(
            ConnectionFormState(
                name = "Mac mini",
                baseUrl = "192.168.1.10:4096",
                pinSha256 = VALID_PIN,
            ).pinIgnored,
        )
        assertFalse(
            ConnectionFormState(
                name = "Server",
                baseUrl = "https://opencode.example.com",
                pinSha256 = VALID_PIN,
            ).pinIgnored,
        )
    }

    @Test
    fun `toProfile refuses an invalid pin instead of silently dropping it`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionFormState(
                name = "Server",
                baseUrl = "https://opencode.example.com",
                pinSha256 = "not-a-pin",
            ).toProfile()
        }
    }

    private companion object {
        val VALID_PIN: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    }
}
