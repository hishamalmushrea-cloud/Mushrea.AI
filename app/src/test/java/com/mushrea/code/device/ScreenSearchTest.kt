package com.mushrea.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSearchTest {
    @Test
    fun `arabic query reaches english labels through the concept group`() {
        val snapshot =
            ScreenSnapshot(
                packageName = "test",
                activity = null,
                elements =
                    listOf(
                        element(0, description = "Back", clickable = true),
                        element(1, text = "Home", clickable = true),
                        element(2, text = "Settings", clickable = true),
                    ),
                truncated = false,
            )

        val hits = ScreenSnapshotFormatter.find(snapshot, "زر الرجوع")

        assertEquals(0, hits.first().index)
    }

    @Test
    fun `english query reaches arabic labels through the concept group`() {
        val snapshot =
            ScreenSnapshot(
                packageName = "test",
                activity = null,
                elements =
                    listOf(
                        element(0, text = "الرئيسية", clickable = true),
                        element(1, description = "رجوع", clickable = true),
                    ),
                truncated = false,
            )

        val hits = ScreenSnapshotFormatter.find(snapshot, "back button")

        assertEquals(1, hits.first().index)
    }

    @Test
    fun `role words break ties toward the named trait`() {
        val snapshot =
            ScreenSnapshot(
                packageName = "test",
                activity = null,
                elements =
                    listOf(
                        element(0, text = "بحث", clickable = true),
                        element(1, text = "بحث", editable = true),
                    ),
                truncated = false,
            )

        val editableFirst = ScreenSnapshotFormatter.find(snapshot, "حقل بحث")
        assertEquals(1, editableFirst.first().index)

        val clickableFirst = ScreenSnapshotFormatter.find(snapshot, "زر بحث")
        assertEquals(0, clickableFirst.first().index)
    }

    @Test
    fun `a direct label hit still outranks a concept sibling`() {
        val snapshot =
            ScreenSnapshot(
                packageName = "test",
                activity = null,
                elements =
                    listOf(
                        element(0, description = "Back", clickable = true),
                        element(1, text = "رجوع", clickable = true),
                    ),
                truncated = false,
            )

        val hits = ScreenSnapshotFormatter.find(snapshot, "رجوع")

        assertEquals(1, hits.first().index)
    }

    @Test
    fun `expansion names sibling terms and role hints`() {
        val expansion = ScreenSearch.expand("زر الرجوع")

        assertTrue("back" in expansion.terms)
        assertTrue("ارجع" in expansion.terms)
        assertTrue(expansion.wantClickable == true)
        assertNull(expansion.wantEditable)
    }

    @Test
    fun `a query with no concept expands to nothing`() {
        assertTrue(ScreenSearch.expand("طقس الليلة").isEmpty)
        assertTrue(ScreenSearch.expand("   ").isEmpty)
        assertFalse(ScreenSearch.expand("حقل البحث").isEmpty)
    }

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
}
