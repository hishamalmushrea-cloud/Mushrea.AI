package com.mushrea.code.device.usb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AdbProtocolTest {
    @Test
    fun `crc32 matches the standard test vector`() {
        assertEquals(0xCBF43926L.toInt(), AdbProtocol.crc32("123456789".toByteArray()))
    }

    @Test
    fun `message encodes and decodes round trip`() {
        val message = AdbProtocol.Message(AdbProtocol.CMD_WRTE, 3, 4, "hi".toByteArray())
        val encoded = AdbProtocol.encode(message)
        assertEquals(AdbProtocol.HEADER_SIZE + 2, encoded.size)
        val header = encoded.copyOfRange(0, AdbProtocol.HEADER_SIZE)
        val payload = encoded.copyOfRange(AdbProtocol.HEADER_SIZE, encoded.size)
        val decoded = AdbProtocol.decode(header, payload)
        assertEquals(AdbProtocol.CMD_WRTE, decoded.command)
        assertEquals(3, decoded.arg0)
        assertEquals(4, decoded.arg1)
        assertArrayEquals("hi".toByteArray(), decoded.data)
    }

    @Test
    fun `corrupted checksum is rejected`() {
        val encoded = AdbProtocol.encode(AdbProtocol.Message(AdbProtocol.CMD_OPEN, 1, 0, "x".toByteArray()))
        encoded[16] = (encoded[16] + 1).toByte()
        val header = encoded.copyOfRange(0, AdbProtocol.HEADER_SIZE)
        val payload = encoded.copyOfRange(AdbProtocol.HEADER_SIZE, encoded.size)
        try {
            AdbProtocol.decode(header, payload)
            fail("corrupt frame must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected: the CRC no longer matches
        }
    }

    @Test
    fun `public key encoding follows the android format`() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val publicKey = generator.generateKeyPair().public as RSAPublicKey
        val encoded = AdbProtocol.encodePublicKey(publicKey)
        val text = String(encoded, Charsets.US_ASCII)
        val base64Part = text.substringBefore(' ')
        val struct = Base64.getDecoder().decode(base64Part)
        val buffer = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        val wordCount = buffer.int
        assertEquals(64, wordCount)
        val n0inv = buffer.int
        val modulusBytes = ByteArray(256)
        buffer.get(modulusBytes)
        assertEquals(publicKey.modulus, BigInteger(1, modulusBytes.reversedArray()))
        val exponent = buffer.int
        assertEquals(publicKey.publicExponent.intValue(), exponent)
        assertEquals(0, encoded[encoded.size - 1].toInt()) // trailing NUL after the suffix
        val two32 = BigInteger.ONE.shiftLeft(32)
        val product =
            publicKey.modulus.mod(two32).multiply(BigInteger.valueOf(n0inv.toLong() and 0xFFFFFFFFL))
        assertEquals(two32.subtract(BigInteger.ONE), product.mod(two32))
    }
}
