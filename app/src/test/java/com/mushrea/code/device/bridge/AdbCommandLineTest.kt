package com.mushrea.code.device.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every line the platform sends to adb is built here, so these tests are the quoting story: the
 * command the agent chose must reach the *other* phone's shell exactly as it was written.
 */
class AdbCommandLineTest {
    @Test
    fun `a serial that could change the command is refused`() {
        assertEquals("192.168.1.20:37099", AdbCommandLine.serial(" 192.168.1.20:37099 "))
        assertEquals("adb-x._adb-tls-connect._tcp", AdbCommandLine.serial("adb-x._adb-tls-connect._tcp"))
        assertThrows(IllegalArgumentException::class.java) { AdbCommandLine.serial("serial; rm -rf /") }
        assertThrows(IllegalArgumentException::class.java) { AdbCommandLine.serial("serial$(id)") }
        assertThrows(IllegalArgumentException::class.java) { AdbCommandLine.serial("") }
    }

    @Test
    fun `quoting survives an embedded single quote`() {
        assertEquals("'plain'", AdbCommandLine.quote("plain"))
        assertEquals("'it'\\''s'", AdbCommandLine.quote("it's"))
    }

    @Test
    fun `every shell line names its device`() {
        val line = AdbCommandLine.shell("192.168.1.20:37099", "getprop ro.product.model")

        assertEquals("adb -s '192.168.1.20:37099' 'shell' 'getprop ro.product.model'", line)
    }

    @Test
    fun `the script is one quoted argument so the local shell cannot expand it`() {
        val line = AdbCommandLine.shell("serial-x", "echo \"model=\$(getprop ro.product.model)\"")

        // The `$(...)` is inside single quotes: adb's local shell leaves it alone, the device's
        // shell expands it. If this ever becomes double-quoted, every `$` would be eaten locally.
        assertTrue(line.contains("'echo \"model=\$(getprop ro.product.model)\"'"))
    }

    @Test
    fun `exec-out is used for binary-clean output`() {
        assertEquals("adb -s 'serial-x' 'exec-out' 'screencap -p'", AdbCommandLine.execOut("serial-x", "screencap -p"))
    }

    @Test
    fun `pairing and connecting address the service, not a device`() {
        assertEquals("adb 'pair' '192.168.1.20:37123' '123456'", AdbCommandLine.pair("192.168.1.20", 37123, "123456"))
        assertEquals("adb 'connect' '192.168.1.20:37099'", AdbCommandLine.connect("192.168.1.20", 37099))
        assertEquals("adb 'disconnect' '192.168.1.20:37099'", AdbCommandLine.disconnect("192.168.1.20", 37099))
        assertEquals("adb devices -l", AdbCommandLine.devices())
    }

    @Test
    fun `an optional serial is only added when it is known`() {
        // The session commands (pair/connect) run before any serial exists; the rest never do.
        assertEquals("adb 'devices' '-l'", AdbCommandLine.adb(null, "devices", "-l"))
        assertTrue(AdbCommandLine.adb(null, "shell", "id").startsWith("adb 'shell'"))
        assertTrue(AdbCommandLine.adb("serial-x", "shell", "id").startsWith("adb -s 'serial-x' 'shell'"))
    }
}
