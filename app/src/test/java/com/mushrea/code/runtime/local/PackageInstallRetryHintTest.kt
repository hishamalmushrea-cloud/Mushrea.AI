package com.mushrea.code.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageInstallRetryHintTest {
    @Test
    fun `a missing libtalloc is not described as a network timeout`() {
        val log =
            """
            CANNOT LINK EXECUTABLE "/data/app/app/lib/arm64/libopencode_android_proot.so": library "libtalloc.so" not found: needed by main executable
            """.trimIndent()
        assertTrue(isEmbeddedLinkerFailure(log))
        assertEquals(PACKAGE_INSTALL_LINKER_HINT, packageInstallHintForLog(log))
        val message = packageInstallFailureMessage("Unable to install runtime packages.", log)
        assertTrue(message.contains(PACKAGE_INSTALL_LINKER_HINT))
        assertFalse(message.contains(PACKAGE_INSTALL_RETRY_HINT))
        assertTrue(message.contains("libtalloc.so"))
    }

    @Test
    fun `an apk size summary still gets the network retry hint`() {
        val log = "1 error; 1435.7 MiB in 257 packages"
        assertFalse(isEmbeddedLinkerFailure(log))
        assertEquals(PACKAGE_INSTALL_RETRY_HINT, packageInstallHintForLog(log))
    }
}
