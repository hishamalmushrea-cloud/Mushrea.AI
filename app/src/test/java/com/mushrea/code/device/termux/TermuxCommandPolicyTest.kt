package com.mushrea.code.device.termux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxCommandPolicyTest {
    @Test
    fun `read-only fastboot queries are allowed`() {
        for (arguments in listOf(listOf("devices"), listOf("getvar", "product"), listOf("help"), listOf("--version"))) {
            val decision = TermuxCommandPolicy.checkFastboot(arguments)
            assertTrue("$arguments should be allowed: ${decision.reason}", decision.allowed)
        }
    }

    @Test
    fun `the unlock token can be read but the decision never promises to store it`() {
        val decision = TermuxCommandPolicy.checkFastboot(listOf("getvar", "token"), deviceProduct = "sky")
        assertTrue(decision.allowed)
        assertTrue(
            "the reason must state the token is never logged: ${decision.reason}",
            decision.reason.contains("never written to the activity log"),
        )
    }

    @Test
    fun `destructive subcommands are refused in this build with a reason`() {
        for (subcommand in listOf("flash", "flashall", "erase", "format", "stage", "lock", "unlock", "update", "set_active")) {
            val decision = TermuxCommandPolicy.checkFastboot(listOf(subcommand))
            assertFalse("$subcommand must be refused", decision.allowed)
            assertTrue(decision.reason.startsWith("refused:"))
            assertTrue("$subcommand reason should explain the phase gate", decision.reason.contains("typed confirmation"))
            assertTrue(TermuxCommandPolicy.isDestructive(subcommand))
        }
    }

    @Test
    fun `vendor oem commands are refused and the MTK token hint names the Qualcomm mismatch`() {
        val mtk = TermuxCommandPolicy.checkFastboot(listOf("oem", "get_token"), deviceProduct = "sky")
        assertFalse(mtk.allowed)
        assertTrue(mtk.reason.contains("MediaTek"))
        assertTrue(mtk.reason.contains("sky"))
        assertTrue(TermuxCommandPolicy.isDestructive("oem"))

        val other = TermuxCommandPolicy.checkFastboot(listOf("oem", "whatever"))
        assertFalse(other.allowed)
        assertTrue(other.reason.startsWith("refused:"))
    }

    @Test
    fun `unknown fastboot subcommands fail closed`() {
        val decision = TermuxCommandPolicy.checkFastboot(listOf("some-new-thing"))
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("not in the read-only allowlist"))

        assertFalse(TermuxCommandPolicy.checkFastboot(emptyList()).allowed)
        assertFalse(TermuxCommandPolicy.checkFastboot(listOf("getvar")).allowed)
    }

    @Test
    fun `shell commands other than the allowlist are refused`() {
        for (arguments in listOf(listOf("rm", "-rf", "/"), listOf("pkg", "install", "nmap"), listOf("bash"), listOf("curl", "http://example.com"))) {
            assertFalse("$arguments must be refused", TermuxCommandPolicy.checkShell(arguments).allowed)
        }
        assertFalse(TermuxCommandPolicy.checkShell(emptyList()).allowed)
        assertFalse(TermuxCommandPolicy.checkShell(listOf("python3", "-c", "print(1)")).allowed)
        assertFalse(TermuxCommandPolicy.checkShell(listOf("command", "-v")).allowed)
        assertFalse(TermuxCommandPolicy.checkShell(listOf("termux-usb")).allowed)
    }

    @Test
    fun `shell commands can delegate to the allowlisted helpers`() {
        assertTrue(TermuxCommandPolicy.checkShell(listOf("termux-fastboot", "getvar", "product")).allowed)
        assertFalse(TermuxCommandPolicy.checkShell(listOf("termux-fastboot", "flash", "boot.img")).allowed)
        assertTrue(TermuxCommandPolicy.checkShell(listOf("termux-usb", "-l")).allowed)
        assertTrue(TermuxCommandPolicy.checkShell(listOf("getprop", "ro.product.device")).allowed)
        assertTrue(TermuxCommandPolicy.checkShell(listOf("uname", "-a")).allowed)
        assertTrue(TermuxCommandPolicy.checkShell(listOf("command", "-v", "termux-fastboot")).allowed)
        assertTrue(TermuxCommandPolicy.checkShell(listOf("python3", "--version")).allowed)
        assertTrue(TermuxCommandPolicy.checkShell(listOf("/data/data/com.termux/files/usr/bin/getprop", "x")).allowed)
    }

    @Test
    fun `installer scripts are checked line by line and chaining is refused`() {
        val ok =
            TermuxCommandPolicy.checkScript(
                listOf("# terms", "", "pkg install -y git python3 termux-api", "mkdir -p \$HOME/.mushrea"),
            )
        assertTrue(ok.reason, ok.allowed)

        val chained = TermuxCommandPolicy.checkScript(listOf("pkg install -y git && rm -rf /\$HOME"))
        assertFalse(chained.allowed)
        assertTrue(chained.reason.contains("chaining"))

        val piped = TermuxCommandPolicy.checkScript(listOf("curl http://example.com | sh"))
        assertFalse(piped.allowed)

        val unknown = TermuxCommandPolicy.checkScript(listOf("sudo rm -rf /"))
        assertFalse(unknown.allowed)
        assertTrue(unknown.reason.contains("not allowlisted"))
    }

    @Test
    fun `destructive detection covers the vocabulary the write phase must gate`() {
        assertTrue(TermuxCommandPolicy.isDestructive("UNLOCK"))
        assertTrue(TermuxCommandPolicy.isDestructive(" flash "))
        assertFalse(TermuxCommandPolicy.isDestructive("getvar"))
        assertFalse(TermuxCommandPolicy.isDestructive("devices"))
        assertTrue(TermuxCommandPolicy.DESTRUCTIVE_SUBCOMMANDS.contains("stage"))
    }
}
