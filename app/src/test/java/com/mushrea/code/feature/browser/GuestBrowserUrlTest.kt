package com.mushrea.code.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestBrowserUrlTest {
    @Test
    fun `loopback and LAN urls are allowed`() {
        assertTrue(isAllowedGuestBrowserUrl("http://127.0.0.1:8080/"))
        assertTrue(isAllowedGuestBrowserUrl("http://192.168.1.20:3000/app"))
        assertTrue(isAllowedGuestBrowserUrl("http://localhost:4096/"))
    }

    @Test
    fun `public hosts, file and javascript urls are refused`() {
        assertFalse(isAllowedGuestBrowserUrl("https://example.com"))
        assertFalse(isAllowedGuestBrowserUrl("http://example.com"))
        assertFalse(isAllowedGuestBrowserUrl("file:///data/data/com.mushrea.code/files/secret"))
        assertFalse(isAllowedGuestBrowserUrl("javascript:alert(1)"))
        assertFalse(isAllowedGuestBrowserUrl("content://media/external/file/1"))
    }

    @Test
    fun `typing a public host falls back to loopback instead of loading it`() {
        assertEquals(GUEST_BROWSER_FALLBACK_URL, normalizeGuestBrowserUrl("example.com"))
        assertEquals(GUEST_BROWSER_FALLBACK_URL, normalizeGuestBrowserUrl("https://evil.example"))
        assertEquals("http://127.0.0.1:8080/", normalizeGuestBrowserUrl("http://127.0.0.1:8080/"))
    }
}
