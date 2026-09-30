package com.mushrea.code.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {
    @Test
    fun `blank text stays blank`() {
        assertEquals("", textForSpeech("   \n "))
    }

    @Test
    fun `fenced code blocks collapse into a short placeholder`() {
        val spoken = textForSpeech("قبل\n```kotlin\nval x = 1\n```\nبعد", codePlaceholder = "كود")
        assertEquals("قبل كود. بعد", spoken)
    }

    @Test
    fun `an unterminated code fence still collapses`() {
        val spoken = textForSpeech("نص\n```python\nprint(1)", codePlaceholder = "كود")
        assertEquals("نص كود.", spoken)
    }

    @Test
    fun `inline code keeps only its content`() {
        assertEquals("قيمة x هنا", textForSpeech("قيمة `x` هنا"))
    }

    @Test
    fun `markdown links keep only their label`() {
        assertEquals(
            "راجع التوثيق اليوم",
            textForSpeech("راجع [التوثيق](https://example.com/a) اليوم"),
        )
    }

    @Test
    fun `bare urls are dropped`() {
        assertEquals("افتح الآن", textForSpeech("افتح https://example.com/x الآن"))
    }

    @Test
    fun `markdown decorations are stripped`() {
        val spoken = textForSpeech("## عنوان\n- **نقطة** مهمة")
        assertEquals("عنوان نقطة مهمة", spoken)
    }

    @Test
    fun `very long replies are capped with an ellipsis`() {
        val spoken = textForSpeech("كلمة ".repeat(400))
        assertEquals(1201, spoken.length)
        assertTrue(spoken.endsWith("…"))
    }
}
