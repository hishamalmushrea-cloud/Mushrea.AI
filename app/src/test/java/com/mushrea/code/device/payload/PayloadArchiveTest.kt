package com.mushrea.code.device.payload

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZOutputStream

class PayloadArchiveTest {
    @Test
    fun `parses a synthetic payload and reconstructs the partition image`() {
        val payload = syntheticPayload()
        val file = File.createTempFile("payload", ".bin").apply { writeBytes(payload) }

        val info = PayloadArchive.readInfo(file)
        assertEquals(listOf("boot"), info.partitions.map { it.name })
        val ops = info.partitions.single().operations
        assertEquals(2, ops.size)

        val output = File.createTempFile("boot", ".img")
        val result = PayloadArchive.extractPartition(file, info, "boot", output)
        assertEquals(2, result.appliedOps)
        assertEquals(0, result.gaps)
        assertEquals("hello world", output.readText())
        output.delete()
        file.delete()
    }

    @Test
    fun `unknown partitions and bad magic fail honestly`() {
        val good = File.createTempFile("payload", ".bin").apply { writeBytes(syntheticPayload()) }
        val info = PayloadArchive.readInfo(good)
        val out = File.createTempFile("tmpx", ".img")
        try {
            PayloadArchive.extractPartition(good, info, "system", out)
            org.junit.Assert.fail("unknown partition must fail")
        } catch (expected: PayloadArchive.PayloadFormatException) {
            assertTrue(expected.message!!.contains("no partition"))
        }
        val bad = File.createTempFile("bad", ".bin").apply { writeBytes("XXXX-nope".toByteArray()) }
        try {
            PayloadArchive.readInfo(bad)
            org.junit.Assert.fail("bad magic must fail")
        } catch (expected: PayloadArchive.PayloadFormatException) {
            assertTrue(expected.message!!.contains("CrAU"))
        }
        out.delete()
        good.delete()
        bad.delete()
    }

    /** Builds a tiny v2 payload by hand: manifest + one RAW op ("hello") + one XZ op (" world"). */
    private fun syntheticPayload(): ByteArray {
        val xzBytes =
            java.io.ByteArrayOutputStream().let { stream ->
                XZOutputStream(stream, org.tukaani.xz.LZMA2Options()).use { it.write(" world".toByteArray()) }
                stream.toByteArray()
            }
        val rawOp =
            operation(
                type = 0,
                dataOffset = 0,
                dataLength = 5,
                dstOffset = 0,
                dstLength = 5,
            )
        val xzOp =
            operation(
                type = 8,
                dataOffset = 5,
                dataLength = xzBytes.size.toLong(),
                dstOffset = 5,
                dstLength = 6,
            )
        val partition =
            embedded(field = 2, content = "boot".toByteArray()) +
                embedded(field = 3, content = rawOp) +
                embedded(field = 3, content = xzOp)
        val manifest = embedded(field = 2, content = partition)
        val blob = "hello".toByteArray() + xzBytes
        val header =
            "CrAU".toByteArray(Charsets.US_ASCII) +
                longBe(2) +
                longBe(manifest.size.toLong()) +
                intBe(0)
        return header + manifest + blob
    }

    private fun operation(
        type: Int,
        dataOffset: Long,
        dataLength: Long,
        dstOffset: Long,
        dstLength: Long,
    ): ByteArray =
        varintField(1, type.toLong()) +
            varintField(2, dataOffset) +
            varintField(3, dataLength) +
            varintField(6, dstOffset) +
            varintField(7, dstLength)

    private fun varintField(
        number: Int,
        value: Long,
    ): ByteArray {
        val tag = varint((number.toLong() shl 3))
        return tag + varint(value)
    }

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val bytes = ArrayList<Byte>()
        while (true) {
            var byte = (remaining and 0x7F).toInt()
            remaining = remaining shr 7
            if (remaining != 0L) byte = byte or 0x80
            bytes.add(byte.toByte())
            if (remaining == 0L) return bytes.toByteArray()
        }
    }

    private fun embedded(
        field: Int,
        content: ByteArray,
    ): ByteArray = varint((field.toLong() shl 3) or 2) + varint(content.size.toLong()) + content

    private fun longBe(value: Long): ByteArray =
        byteArrayOf(
            (value shr 56).toByte(),
            (value shr 48).toByte(),
            (value shr 40).toByte(),
            (value shr 32).toByte(),
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )

    private fun intBe(value: Int): ByteArray =
        byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )

}
