package com.mushrea.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppResolverTest {

    private val apps =
        listOf(
            AppEntry("YouTube", "com.google.android.youtube"),
            AppEntry("YouTube Music", "com.google.android.apps.youtube.music"),
            AppEntry("WhatsApp", "com.whatsapp"),
            AppEntry("الإعدادات", "com.android.settings"),
            AppEntry("الكمبيوتر", "com.example.pc"),
        )

    @Test
    fun `exact package name wins`() {
        val result = AppResolver.resolve("com.whatsapp", apps)
        assertEquals(AppMatch.HIGH, result.confidence)
        assertEquals("com.whatsapp", result.best?.packageName)
    }

    @Test
    fun `exact label wins`() {
        val result = AppResolver.resolve("youtube", apps)
        assertEquals(AppMatch.HIGH, result.confidence)
        assertEquals("com.google.android.youtube", result.best?.packageName)
    }

    @Test
    fun `multiple candidates resolve to the shortest label with alternatives`() {
        val result = AppResolver.resolve("you", apps)
        assertEquals(AppMatch.MEDIUM, result.confidence)
        assertEquals("com.google.android.youtube", result.best?.packageName)
        assertEquals(listOf("YouTube Music"), result.alternatives.map { it.label })
    }

    @Test
    fun `arabic labels normalize before matching`() {
        // Ta-marbuta folds to ha, alef variants fold: "الاعدادات" still matches "الإعدادات".
        val result = AppResolver.resolve("الاعدادات", apps)
        assertEquals("com.android.settings", result.best?.packageName)
    }

    @Test
    fun `arabic-indic digits are folded to latin digits`() {
        assertEquals("app2", AppResolver.normalize("app٢"))
    }

    @Test
    fun `unknown app resolves to none`() {
        val result = AppResolver.resolve("notanapp", apps)
        assertNull(result.best)
        assertEquals(AppMatch.NONE, result.confidence)
    }

    @Test
    fun `blank query resolves to none`() {
        val result = AppResolver.resolve("  ", apps)
        assertNull(result.best)
    }
}
