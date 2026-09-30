package com.mushrea.code.device.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AccessUnitSplitterTest {
    private fun startCode(fourByte: Boolean = true): ByteArray =
        if (fourByte) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)

    private fun nal(
        type: Int,
        payload: String = "x",
    ): ByteArray = byteArrayOf((type or 0x60).toByte()) + payload.toByteArray()

    private fun streamOf(vararg nals: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        nals.forEach { nal ->
            startCode().forEach { out.add(it) }
            nal.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    /** The split a full stream produces: feed everything, then flush the trailing unit. */
    private fun splitAll(stream: ByteArray): List<AccessUnit> {
        val splitter = H264AccessUnitSplitter()
        return splitter.feed(stream) + splitter.flush()
    }

    @Test
    fun `splits a config riding on an idr frame into a config unit then the frame`() {
        val units = splitAll(streamOf(nal(9, "aud"), nal(7, "sps"), nal(8, "pps"), nal(5, "idr")))
        assertEquals(2, units.size)
        assertTrue(units[0].isConfig)
        assertFalse(units[0].isKeyFrame)
        assertFalse(units[1].isConfig)
        assertTrue(units[1].isKeyFrame)
        assertTrue(units[1].data.decodeToString().contains("idr"))
    }

    @Test
    fun `each following slice opens a new access unit`() {
        val units = splitAll(streamOf(nal(9), nal(5, "idr"), nal(1, "p1"), nal(1, "p2")))
        assertEquals(3, units.size)
        assertTrue(units[0].isKeyFrame)
        assertFalse(units[1].isKeyFrame)
        assertFalse(units[2].isKeyFrame)
        assertTrue(units[2].data.decodeToString().contains("p2"))
    }

    @Test
    fun `a chunk cut in the middle of a start code still yields the same units`() {
        val stream = streamOf(nal(7, "sps"), nal(8, "pps"), nal(5, "idr"), nal(1, "p"))
        val cut = stream.size / 2
        val cutSplitter = H264AccessUnitSplitter()
        val units = cutSplitter.feed(stream.copyOfRange(0, cut)) + cutSplitter.feed(stream.copyOfRange(cut, stream.size)) + cutSplitter.flush()
        val whole = splitAll(stream)
        assertEquals(whole.map { it.data.toList() to it.isConfig }, units.map { it.data.toList() to it.isConfig })
    }

    @Test
    fun `three byte start codes are recognised`() {
        val out = ArrayList<Byte>()
        startCode(fourByte = false).forEach { out.add(it) }
        nal(7, "s").forEach { out.add(it) }
        startCode(fourByte = false).forEach { out.add(it) }
        nal(5, "i").forEach { out.add(it) }
        val splitter = H264AccessUnitSplitter()
        val units = splitter.feed(out.toByteArray()) + splitter.flush()
        assertEquals(2, units.size)
        assertTrue(units[0].isConfig)
        assertTrue(units[1].isKeyFrame)
    }

    @Test
    fun `flush emits the trailing access unit`() {
        val splitter = H264AccessUnitSplitter()
        val units = splitter.feed(streamOf(nal(9), nal(5, "idr"), nal(1, "p"))) + splitter.flush()
        assertEquals(2, units.size)
        assertTrue(units[1].data.decodeToString().contains("p"))
    }

    @Test
    fun `parameter sets after slices open a fresh unit`() {
        val units = splitAll(streamOf(nal(9), nal(1, "p"), nal(7, "sps2"), nal(8, "pps2"), nal(5, "idr2")))
        assertEquals(3, units.size)
        assertFalse(units[0].isKeyFrame)
        assertTrue(units[1].isConfig)
        assertTrue(units[1].data.decodeToString().contains("sps2"))
        assertFalse(units[2].isConfig)
        assertTrue(units[2].data.decodeToString().contains("idr2"))
    }
}
