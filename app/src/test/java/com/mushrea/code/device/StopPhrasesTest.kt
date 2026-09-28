package com.mushrea.code.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopPhrasesTest {
    @Test
    fun `arabic stop words match`() {
        for (phrase in listOf("توقف", "أوقف", "اوقف", "توقفي", "الغاء الأمر")) {
            assertTrue(phrase, StopPhrases.isStopCommand(phrase))
        }
    }

    @Test
    fun `arabic matches survive orthography and diacritics`() {
        assertTrue(StopPhrases.isStopCommand("تَوَقُّف"))
        assertTrue(StopPhrases.isStopCommand("اؤوقف"))
    }

    @Test
    fun `english stop words match`() {
        for (phrase in listOf("stop", "Stop", "stop agent", "cancel", "abort")) {
            assertTrue(phrase, StopPhrases.isStopCommand(phrase))
        }
    }

    @Test
    fun `wake word prefixed stops match`() {
        assertTrue(StopPhrases.isStopCommand("hey mushrea stop"))
        assertTrue(StopPhrases.isStopCommand("يا مشيرة توقف"))
        assertTrue(StopPhrases.isStopCommand("mushrea توقف"))
    }

    @Test
    fun `normal sentences do not trigger the emergency stop`() {
        assertFalse(StopPhrases.isStopCommand("stop the music"))
        assertFalse(StopPhrases.isStopCommand("stop working on the tests and continue"))
        assertFalse(StopPhrases.isStopCommand("افتح يوتيوب"))
        assertFalse(StopPhrases.isStopCommand(""))
        assertFalse(StopPhrases.isStopCommand("stopping by to say hi"))
    }
}
