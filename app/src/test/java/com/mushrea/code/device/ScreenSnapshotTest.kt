package com.mushrea.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSnapshotTest {
    private val snapshot =
        ScreenSnapshot(
            packageName = "com.google.android.youtube",
            activity = "HomeActivity",
            elements =
                listOf(
                    element(0, text = "Search", clickable = true),
                    element(1, text = "Home", clickable = true),
                    element(2, text = "Subscriptions", clickable = true),
                    element(3, text = "", description = "Open navigation drawer", clickable = true),
                    element(4, text = "Search or type URL", editable = true),
                ),
            truncated = false,
        )

    private fun element(
        index: Int,
        text: String = "",
        description: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
    ) = ScreenElement(
        index = index,
        text = text,
        contentDescription = description,
        className = if (editable) "android.widget.EditText" else "android.widget.TextView",
        viewIdResourceName = "com.example:id/element$index",
        boundsInScreen = ScreenElement.Rect(index * 10, 0, index * 10 + 10, 100),
        clickable = clickable,
        editable = editable,
        scrollable = false,
    )

    @Test
    fun `prompt text lists app, activity and numbered elements`() {
        val text = ScreenSnapshotFormatter.toPromptText(snapshot)
        assertTrue(text.contains("Current app: com.google.android.youtube"))
        assertTrue(text.contains("Current screen: HomeActivity"))
        assertTrue(text.contains("0. Search"))
        assertTrue(text.contains("[clickable]"))
        assertTrue(text.contains("Open navigation drawer"))
    }

    @Test
    fun `find matches by exact text first`() {
        val hits = ScreenSnapshotFormatter.find(snapshot, "Search")
        assertEquals(0, hits.first().index)
        // "Search or type URL" contains "Search" too but ranks lower.
        assertEquals(2, hits.size)
    }

    @Test
    fun `find matches content descriptions`() {
        val hits = ScreenSnapshotFormatter.find(snapshot, "navigation drawer")
        assertEquals(3, hits.first().index)
    }

    @Test
    fun `find matches arabic queries against normalized labels`() {
        val arabic =
            ScreenSnapshot(
                packageName = "test",
                activity = null,
                elements = listOf(element(0, text = "البحث")),
                truncated = false,
            )
        // Alef folding: "البحات" (typos swap) is NOT expected to match, but exact-normalized does.
        val hits = ScreenSnapshotFormatter.find(arabic, "البحث")
        assertEquals(0, hits.first().index)
    }

    @Test
    fun `index queries return exactly that element`() {
        val hits = ScreenSnapshotFormatter.find(snapshot, "#2")
        assertEquals(1, hits.size)
        assertEquals(2, hits.first().index)
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(ScreenSnapshotFormatter.find(snapshot, "nonexistent").isEmpty())
    }
}
