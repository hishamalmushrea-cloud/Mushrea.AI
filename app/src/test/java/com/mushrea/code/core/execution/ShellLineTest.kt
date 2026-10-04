package com.mushrea.code.core.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recipes build shell lines out of agent-supplied parameters, so quoting is the last line of defence
 * between a parameter and the other phone's shell. These tests pin the two properties that matter:
 * a value can never break out of its argument, and a value that must be numeric never arrives as text.
 */
class ShellLineTest {
    @Test
    fun `a value is a single quoted argument`() {
        assertEquals("'/sdcard/Download'", ShellLine.quote("/sdcard/Download"))
    }

    @Test
    fun `an embedded quote cannot end the argument`() {
        val quoted = ShellLine.quote("a'; rm -rf /sdcard; echo 'b")

        assertEquals("'a'\\''; rm -rf /sdcard; echo '\\''b'", quoted)
        assertTrue("the value stays inside quotes", quoted.startsWith("'") && quoted.endsWith("'"))
    }

    @Test
    fun `a line quotes the program and every argument`() {
        assertEquals("'cat' '/sdcard/my file.txt'", ShellLine.of("cat", "/sdcard/my file.txt"))
    }

    @Test
    fun `numbers stay numbers so coordinates cannot smuggle a command`() {
        assertEquals("100 200", ShellLine.numbers("100", "200"))

        val thrown = runCatching { ShellLine.numbers("100; rm -rf /sdcard", "200") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `a count falls back to its default and refuses nonsense`() {
        assertEquals("200", ShellLine.count("", "lines", 200))
        assertEquals("25", ShellLine.count(" 25 ", "lines", 200))
        assertTrue(runCatching { ShellLine.count("0", "lines", 200) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ShellLine.count("1e9", "lines", 200) }.exceptionOrNull() is IllegalArgumentException)
    }
}
