package com.mushrea.code.device.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every device result now carries a verification, so this pins the two properties that keep it
 * honest: an executor that checked nothing is read as unverified (never as success), and an
 * executor's own claim is quoted rather than silently dropped.
 *
 * The checks themselves live in the executors and need a phone or a real transfer; their behaviour
 * is `Cannot Verify — Environment Limitation` here and is listed in the audit and the feature matrix.
 */
class ToolVerificationTest {
    @Test
    fun `an execution that recorded nothing is unverified by the caller`() {
        assertNull(ToolVerification.of(JSONObject().put("summary", "did something")))
    }

    @Test
    fun `a declared verification is kept and re-exported unchanged`() {
        val payload =
            JSONObject()
                .put("summary", "deleted /sdcard/a.txt")
                .put("verified", true)
                .put("verification", "the file no longer exists on disk")

        val found = requireNotNull(ToolVerification.of(payload))
        assertTrue(found.verified)
        assertEquals("the file no longer exists on disk", found.detail)

        ToolVerification.apply(payload, OutcomeVerification.unverified("nothing checked it"))
        assertFalse(payload.getBoolean("verified"))
        assertEquals("nothing checked it", payload.getString("verification"))
    }

    @Test
    fun `the legacy mixed shape reads a reason string as unverified`() {
        val legacy = JSONObject().put("verified", "unverified (accessibility not reporting this app)")

        val found = requireNotNull(ToolVerification.of(legacy))
        assertFalse("a string must never be read as a confirmed success", found.verified)
        assertEquals("unverified (accessibility not reporting this app)", found.detail)
    }

    @Test
    fun `the legacy boolean shape reads true as verified`() {
        val legacy = JSONObject().put("verified", true)

        assertTrue(requireNotNull(ToolVerification.of(legacy)).verified)
    }

    @Test
    fun `a failed action has nothing to verify`() {
        assertFalse(OutcomeVerification.failed().verified)
        assertTrue(OutcomeVerification.failed().detail.contains("failed"))
    }
}
