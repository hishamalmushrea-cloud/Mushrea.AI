package com.mushrea.code.device.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrcpyControlMessagesTest {
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `touch serialization matches scrcpy's own reference vector`() {
        // Pinned by scrcpy app/tests/test_control_msg_serialize.c:
        // DOWN, pointerId 0x1234567887654321, point (100, 200) in 1080x1920,
        // pressure 1.0, action button PRIMARY, buttons PRIMARY.
        val message =
            ScrcpyControlMessages.injectTouch(
                action = ScrcpyControlMessages.ACTION_DOWN,
                pointerId = 0x1234567887654321L,
                x = 100,
                y = 200,
                frameWidth = 1080,
                frameHeight = 1920,
                pressure = 1.0f,
                actionButton = 1,
                buttons = 1,
            )
        assertEquals(32, message.size)
        assertEquals(
            "0200123456788765432100000064000000c804380780ffff0000000100000001",
            hex(message),
        )
    }

    @Test
    fun `keycode press is two 14-byte messages`() {
        val press = ScrcpyControlMessages.tapKey(ScrcpyControlMessages.KEYCODE_BACK)
        assertEquals(2, press.size)
        assertEquals(14, press[0].size)
        assertEquals("0000000000040000000000000000", hex(press[0]))
        assertEquals(14, press[1].size)
        assertEquals("0001000000040000000000000000", hex(press[1]))
    }

    @Test
    fun `scroll encodes notches as i16 fixed point`() {
        val message =
            ScrcpyControlMessages.injectScroll(
                x = 10,
                y = 20,
                frameWidth = 1080,
                frameHeight = 1920,
                hScroll = 0f,
                vScroll = -1f,
            )
        assertEquals(21, message.size)
        assertEquals("030000000a00000014043807800000800000000000", hex(message))
    }

    @Test
    fun `fixed point helpers clamp to the wire ranges`() {
        assertEquals(0xFFFF, ScrcpyControlMessages.u16FixedPoint(2f))
        assertEquals(0, ScrcpyControlMessages.u16FixedPoint(-1f))
        assertEquals(0x7FFF, ScrcpyControlMessages.i16FixedPoint(0.99999f))
        assertEquals(-0x8000, ScrcpyControlMessages.i16FixedPoint(-16f))
        assertEquals(0, ScrcpyControlMessages.i16FixedPoint(0f))
    }

    @Test
    fun `single byte messages and back are shaped per protocol`() {
        assertEquals("07", hex(ScrcpyControlMessages.collapsePanels()))
        assertEquals("0b", hex(ScrcpyControlMessages.rotateDevice()))
        assertEquals("0400", hex(ScrcpyControlMessages.backOrScreenOn(0)))
        assertEquals("0401", hex(ScrcpyControlMessages.backOrScreenOn(1)))
    }
}
