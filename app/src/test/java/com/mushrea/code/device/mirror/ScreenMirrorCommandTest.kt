package com.mushrea.code.device.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorCommandTest {
    @Test
    fun `the command streams h264 to stdout with a size, a bit rate and a take cap`() {
        val command =
            ScreenMirrorSession.screenrecordCommand(
                width = 854,
                height = 480,
                bitRate = 4_000_000,
                takeSeconds = 170,
            )
        assertEquals(
            "screenrecord --output-format=h264 --size 854x480 --bit-rate 4000000 --time-limit 170 -",
            command,
        )
    }

    @Test
    fun `odd dimensions are rejected before reaching the device`() {
        var rejected = false
        try {
            ScreenMirrorSession.screenrecordCommand(855, 480, 4_000_000, 170)
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `a take longer than the system cap is rejected`() {
        var rejected = false
        try {
            ScreenMirrorSession.screenrecordCommand(854, 480, 4_000_000, 600)
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
