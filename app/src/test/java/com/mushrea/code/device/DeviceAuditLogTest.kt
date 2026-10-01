package com.mushrea.code.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the audit export format the owner is told to trust.
 *
 * [DeviceAuditLog.build] needs an Android context, so the per-entry mapping it uses is a pure
 * function and is tested here: a format-3 entry keeps its permission fields and its verification, an
 * older entry still exports with stated defaults, and a key the log does not define never reaches
 * the export.
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
                .put("confirmation_level", "STRONG")
                .put("reason", "the user approved the confirmation for device_tap")
                .put("verified", true)
                .put("verification", "the destination has the same 12 bytes as the source")

        val exported = DeviceAuditLog.exportEntry(entry)
        assertEquals("agent", exported.getString("actor"))
        assertEquals("strong", exported.getString("risk"))
        assertEquals("confirm", exported.getString("decision"))
        assertEquals("STRONG", exported.getString("confirmation_level"))
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
}
