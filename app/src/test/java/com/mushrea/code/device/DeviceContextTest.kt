package com.mushrea.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceContextTest {
    @Test
    fun `json round trip preserves everything`() {
        val context =
            DeviceContext(
                currentApp = "com.google.android.youtube",
                currentActivity = "HomeActivity",
                currentTask = "send this video to Ahmed",
                lastFile = "/sdcard/Download/report.pdf",
                recentApps = listOf("com.whatsapp", "com.android.chrome"),
                updatedAtMillis = 1_720_000_000_000,
            )
        val restored = DeviceContext.fromJson(context.toJson().toString())
        assertEquals(context, restored)
    }

    @Test
    fun `malformed json yields null and blank fields become null`() {
        assertNull(DeviceContext.fromJson("not json"))
        val minimal = DeviceContext.fromJson("""{"current_app":"com.x"}""")
        assertNotNull(minimal)
        assertNull(minimal?.currentActivity)
        assertNull(minimal?.lastFile)
        assertEquals("", minimal?.currentTask)
    }

    @Test
    fun `reducer tracks the app trail without duplicates`() {
        var context = DeviceContext.EMPTY
        context = DeviceContextReducer.withApp(context, "com.android.chrome", "MainActivity", nowMillis = 1)
        context = DeviceContextReducer.withApp(context, "com.whatsapp", null, nowMillis = 2)
        context = DeviceContextReducer.withApp(context, "com.whatsapp", "ChatActivity", nowMillis = 3)
        context = DeviceContextReducer.withApp(context, "com.android.chrome", null, nowMillis = 4)

        assertEquals("com.android.chrome", context.currentApp)
        assertEquals(listOf("com.whatsapp", "com.android.chrome").distinct(), context.recentApps.distinct())
        assertEquals(4, context.updatedAtMillis)
    }

    @Test
    fun `reducer keeps context on blank app`() {
        val context = DeviceContextReducer.withApp(DeviceContext.EMPTY, "", null, nowMillis = 1)
        assertEquals(DeviceContext.EMPTY, context)
    }

    @Test
    fun `task and last file updates are independent`() {
        var context = DeviceContext.EMPTY
        context = DeviceContextReducer.withTask(context, " send this article to Ahmed ", nowMillis = 1)
        assertEquals("send this article to Ahmed", context.currentTask)
        context = DeviceContextReducer.withLastFile(context, "/sdcard/Download/a.pdf", nowMillis = 2)
        assertEquals("/sdcard/Download/a.pdf", context.lastFile)
        assertEquals("send this article to Ahmed", context.currentTask)
    }

    @Test
    fun `confidence heuristics follow the spec table`() {
        // No reference at all → nothing to be unsure about.
        assertEquals(ContextConfidence.HIGH, ContextConfidenceHeuristics.fromMatchCount(0, askedWithQuery = false))
        // One clear hit → HIGH.
        assertEquals(ContextConfidence.HIGH, ContextConfidenceHeuristics.fromMatchCount(1, askedWithQuery = true))
        // A few candidates → MEDIUM (act, say what was picked).
        assertEquals(ContextConfidence.MEDIUM, ContextConfidenceHeuristics.fromMatchCount(2, askedWithQuery = true))
        assertEquals(ContextConfidence.MEDIUM, ContextConfidenceHeuristics.fromMatchCount(3, askedWithQuery = true))
        // Too many or none → LOW (ask).
        assertEquals(ContextConfidence.LOW, ContextConfidenceHeuristics.fromMatchCount(4, askedWithQuery = true))
        assertEquals(ContextConfidence.LOW, ContextConfidenceHeuristics.fromMatchCount(0, askedWithQuery = true))
    }

    @Test
    fun `set_task is an auto-level action`() {
        val firewall = DeviceActionFirewall()
        assertEquals(ConfirmationLevel.AUTO, firewall.levelFor(DeviceActionFirewall.ACTION_SET_TASK))
    }
}
