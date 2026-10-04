package com.mushrea.code.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceFilePathTest {
    @Test
    fun `a sibling path that only shares a prefix is outside the root`() {
        val root = File("/storage/emulated/0")
        assertFalse(isUnderDirectory(File("/storage/emulated/0-evil/secrets"), root))
        assertFalse(isUnderDirectory(File("/storage/emulated/00"), root))
        assertFalse(isUnderDirectory(File("/storage/emulated"), root))
    }

    @Test
    fun `the root itself and files under it are inside`() {
        val root = File("/storage/emulated/0")
        assertTrue(isUnderDirectory(root, root))
        assertTrue(isUnderDirectory(File("/storage/emulated/0/Download/a.txt"), root))
    }
}
