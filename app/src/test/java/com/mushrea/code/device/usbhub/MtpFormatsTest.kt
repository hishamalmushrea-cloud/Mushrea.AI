package com.mushrea.code.device.usbhub

import org.junit.Assert.assertEquals
import org.junit.Test

class MtpFormatsTest {
    @Test
    fun `common image extensions map to ptp still formats`() {
        assertEquals(MtpFormats.EXIF_JPEG, MtpFormats.ofFileName("shot.JPG"))
        assertEquals(MtpFormats.PNG, MtpFormats.ofFileName("icon.png"))
        assertEquals(MtpFormats.GIF, MtpFormats.ofFileName("a.gif"))
    }

    @Test
    fun `unknown extensions stay undefined rather than a guessed image type`() {
        assertEquals(MtpFormats.UNDEFINED, MtpFormats.ofFileName("firmware.bin"))
        assertEquals(MtpFormats.TEXT, MtpFormats.ofFileName("notes.md"))
    }

    @Test
    fun `safeFileName strips path separators and control characters`() {
        assertEquals("photo.jpg", MtpFormats.safeFileName("/sdcard/DCIM/photo.jpg", "fallback"))
        assertEquals("win.txt", MtpFormats.safeFileName("C:\\\\tmp\\\\win.txt", "fallback"))
        assertEquals("fallback", MtpFormats.safeFileName("   ", "fallback"))
        assertEquals("a_b", MtpFormats.safeFileName("a\u0000b", "fallback"))
    }
}
