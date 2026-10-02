package com.mushrea.code.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the audit export format the owner is told to trust.
 *
 * [DeviceAuditLog.build] needs an Android context, so the per-entry mapping it uses is a pure
 * function and is tested here: a format-4 entry keeps its permission fields, its verification, its
 * parameter digest and its execution window; an older entry still exports with stated defaults; a
 * key the log does not define never reaches the export; and the digest is a comparable fingerprint
 * that never reveals the parameters it was computed from.
 */
class DeviceAuditLogTest {
    @Test
    fun `a format 3 entry keeps actor risk decision reason and verification`() {
        val entry =
            JSONObject()
                .put("ts", 1_700_000_000_000L)
                .put("action", "device_tap")
                .put("ok", true)
                .put("detail", "tapped Pay now")
                .put("actor", "agent")
                .put("risk", "strong")
                .put("decision", "confirm")
                .put("confirmation_level", "STRONG_CONFIRM")
                .put("reason", "the user approved the confirmation for device_tap")
                .put("verified", true)
                .put("verification", "the destination has the same 12 bytes as the source")

        val exported = DeviceAuditLog.exportEntry(entry)
        assertEquals("agent", exported.getString("actor"))
        assertEquals("strong", exported.getString("risk"))
        assertEquals("confirm", exported.getString("decision"))
        assertEquals("STRONG_CONFIRM", exported.getString("confirmation_level"))
        assertEquals("the user approved the confirmation for device_tap", exported.getString("reason"))
        // "action" is the log's own name; "tool" is the name the audit contract uses.
        assertEquals("device_tap", exported.getString("tool"))
        assertEquals("tapped Pay now", exported.getString("summary"))
        assertTrue("a proven outcome must say so", exported.getBoolean("verified"))
        assertEquals("the destination has the same 12 bytes as the source", exported.getString("verification"))
    }

    @Test
    fun `a format 1 entry still exports with stated defaults`() {
        val entry =
            JSONObject()
                .put("ts", 1L)
                .put("action", "device_read_screen")
                .put("ok", true)
                .put("detail", "read 12 nodes")

        val exported = DeviceAuditLog.exportEntry(entry)
        assertEquals("agent", exported.getString("actor"))
        assertEquals("unknown", exported.getString("risk"))
        assertEquals("unrecorded", exported.getString("decision"))
        assertFalse("an entry without a reason must not gain one", exported.has("reason"))
        assertFalse("an entry without a level must not gain one", exported.has("confirmation_level"))
        // Format 1 and 2 entries carry no verification: the export must not invent one.
        assertFalse("an unchecked entry must not gain a verification", exported.has("verified"))
        assertFalse(exported.has("verification"))
    }

    @Test
    fun `an explicitly unverified outcome is exported as unverified`() {
        val entry =
            JSONObject()
                .put("ts", 3L)
                .put("action", "device_tap")
                .put("ok", true)
                .put("detail", "tapped Send")
                .put("verified", false)
                .put("verification", "the executor reported success; no independent check covers this action")

        val exported = DeviceAuditLog.exportEntry(entry)
        assertFalse("a reported-only outcome must not read as verified", exported.getBoolean("verified"))
        assertTrue(exported.getString("verification").contains("no independent check"))
    }

    @Test
    fun `keys the log does not define never reach the export`() {
        val entry =
            JSONObject()
                .put("ts", 2L)
                .put("action", "device_stop")
                .put("ok", true)
                .put("detail", "stop requested")
                .put("token", "unlock-token-value")
                .put("params", JSONObject().put("password", "hunter2"))

        val exported = DeviceAuditLog.exportEntry(entry)
        assertFalse("the unlock token must never be exported", exported.has("token"))
        assertFalse("command parameters must never be exported", exported.has("params"))
        assertFalse(exported.toString().contains("hunter2"))
        assertTrue(exported.has("action"))
    }

    @Test
    fun `a format 4 entry keeps the parameter digest and the execution window`() {
        val entry =
            JSONObject()
                .put("ts", 1_700_000_000_500L)
                .put("action", "device_pull")
                .put("ok", true)
                .put("detail", "pulled 12 bytes")
                .put("params_digest", "hmac-sha256-0123456789abcdef0123456789abcdef")
                .put("started_at", 1_700_000_000_100L)

        val exported = DeviceAuditLog.exportEntry(entry)
        assertEquals("hmac-sha256-0123456789abcdef0123456789abcdef", exported.getString("params_digest"))
        assertEquals(1_700_000_000_100L, exported.getLong("started_at"))
        assertEquals(1_700_000_000_500L, exported.getLong("ended_at"))
        assertEquals(400L, exported.getLong("duration_ms"))
    }

    @Test
    fun `an entry with no execution window exports none`() {
        // A denied or blocked command never ran, so there is nothing to time - and a reader must not
        // read a missing window as a zero-length one.
        val entry = JSONObject().put("ts", 1_700_000_000_000L).put("action", "device_call").put("ok", false)

        val exported = DeviceAuditLog.exportEntry(entry)
        assertFalse(exported.has("started_at"))
        assertFalse(exported.has("ended_at"))
        assertFalse(exported.has("duration_ms"))
    }

    @Test
    fun `the digest is stable, distinguishes parameters and hides them`() {
        val first = JSONObject().put("path", "/sdcard/Download/report.pdf").put("mode", "copy")
        val second = JSONObject().put("path", "/sdcard/Download/report.pdf").put("mode", "move")

        val a = DeviceAuditLog.paramsDigest(first)
        val b = DeviceAuditLog.paramsDigest(JSONObject(first.toString()))
        val c = DeviceAuditLog.paramsDigest(second)

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertTrue(a!!.startsWith("hmac-sha256-"))
        assertFalse(a.contains("report.pdf"))
        assertFalse(a.contains("copy"))
    }

    @Test
    fun `a digest never carries a credential-shaped value`() {
        val withSecret =
            JSONObject()
                .put("host", "192.168.1.10")
                .put("password", "hunter2-not-a-real-password")
                .put("url", "https://example.com/callback?token=abc123")

        val digest = DeviceAuditLog.paramsDigest(withSecret)!!
        assertFalse(digest.contains("hunter2"))
        assertFalse(digest.contains("abc123"))
        // Same credential, same fingerprint: redaction happens before hashing, so the digest cannot
        // even be used to compare two guesses of a secret.
        assertEquals(digest, DeviceAuditLog.paramsDigest(JSONObject(withSecret.toString())))
    }

    @Test
    fun `there is nothing to fingerprint without parameters`() {
        assertNull(DeviceAuditLog.paramsDigest(null))
        assertNull(DeviceAuditLog.paramsDigest(JSONObject()))
    }
}
