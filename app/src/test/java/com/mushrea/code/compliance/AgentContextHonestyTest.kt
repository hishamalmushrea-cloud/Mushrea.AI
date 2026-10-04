package com.mushrea.code.compliance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The agent-context blurb is what every hosted agent reads on first install. If it still
 * describes a wired tool as "later", the model will refuse work the app can already do.
 */
class AgentContextHonestyTest {
    private val repoRoot: File by lazy {
        var dir = File(".").canonicalFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").exists()) return@lazy dir
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate repository root from ${File(".").canonicalPath}")
    }

    private val blurb: String by lazy {
        val file = File(repoRoot, "app/src/main/assets/mushrea-code-agent-context.md")
        assertTrue("Expected agent context at ${file.absolutePath}", file.isFile)
        file.readText()
    }

    @Test
    fun `the blurb names the hub and call tools that are wired`() {
        for (name in listOf("mtp_upload", "hid_read", "camera_capture", "call_record_start", "call_record_stop")) {
            assertTrue("agent context must name $name", blurb.contains(name))
        }
    }

    @Test
    fun `the blurb does not describe wired tools as later work`() {
        val forbidden =
            listOf(
                "upload arrives later",
                "frame capture is a later phase",
                "NOT decoded into keys",
                "recording is not implemented",
            )
        for (phrase in forbidden) {
            assertFalse("agent context still says '$phrase'", blurb.contains(phrase))
        }
    }

    @Test
    fun `call recording is described as near-end only`() {
        val lower = blurb.lowercase()
        assertTrue("near-end only must be stated", lower.contains("near-end"))
        assertTrue(
            "must not claim the other party's audio",
            lower.contains("other party's audio") || lower.contains("two-sided"),
        )
    }
}
