package com.mushrea.code.device.usbhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidDecoderTest {
    @Test
    fun `an unshifted a is decoded from a boot keyboard report`() {
        val event = HidDecoder.decode(byteArrayOf(0, 0, 0x04, 0, 0, 0, 0, 0), HidDecoder.PROTOCOL_KEYBOARD)
        assertEquals("keyboard", event.kind)
        assertEquals(listOf("a"), event.keys)
        assertEquals("a", event.text)
        assertEquals("0000040000000000", event.hex)
    }

    @Test
    fun `left shift turns a into A`() {
        val event = HidDecoder.decode(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), HidDecoder.PROTOCOL_KEYBOARD)
        assertEquals(listOf("left-shift"), event.modifiers)
        assertEquals("A", event.text)
    }

    @Test
    fun `enter is named and produces no text`() {
        val event = HidDecoder.decode(byteArrayOf(0, 0, 0x28, 0, 0, 0, 0, 0), HidDecoder.PROTOCOL_KEYBOARD)
        assertEquals(listOf("enter"), event.keys)
        assertEquals("", event.text)
    }

    @Test
    fun `all zeros is a key-release`() {
        val event = HidDecoder.decode(ByteArray(8), HidDecoder.PROTOCOL_KEYBOARD)
        assertEquals("keyboard", event.kind)
        assertTrue(event.keys.isEmpty())
        assertEquals("all keys released", event.note)
    }

    @Test
    fun `six 0x01 bytes are phantom key rollover`() {
        val event = HidDecoder.decode(byteArrayOf(0, 0, 1, 1, 1, 1, 1, 1), HidDecoder.PROTOCOL_KEYBOARD)
        assertTrue(event.note!!.contains("rollover"))
        assertTrue(event.keys.isEmpty())
    }

    @Test
    fun `a three-byte mouse report decodes buttons and motion`() {
        val event = HidDecoder.decode(byteArrayOf(0x01, 5, (-3).toByte()), HidDecoder.PROTOCOL_MOUSE)
        assertEquals("mouse", event.kind)
        assertEquals(listOf("left"), event.buttons)
        assertEquals(5, event.dx)
        assertEquals(-3, event.dy)
    }

    @Test
    fun `an eight-byte report without a protocol is treated as a keyboard`() {
        val event = HidDecoder.decode(byteArrayOf(0, 0, 0x2C, 0, 0, 0, 0, 0))
        assertEquals("keyboard", event.kind)
        assertEquals(" ", event.text)
    }

    @Test
    fun `an unknown length stays raw`() {
        val event = HidDecoder.decode(byteArrayOf(0x11, 0x22))
        assertEquals("raw", event.kind)
        assertEquals("1122", event.hex)
    }
}
