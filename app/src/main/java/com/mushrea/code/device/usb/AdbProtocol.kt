package com.mushrea.code.device.usb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.zip.CRC32

/**
 * Pure ADB wire protocol (the transport adb speaks over USB): 24-byte little-endian messages with
 * a CRC32 payload checksum, the CNXN/AUTH handshake constants, and Android's RSA public-key
 * encoding for the AUTH step. No Android imports — fully unit-testable in CI.
 */
object AdbProtocol {
    const val CMD_CNXN = 0x434E584E
    const val CMD_AUTH = 0x41555448
    const val CMD_OPEN = 0x4F50454E
    const val CMD_OKAY = 0x4F4B4159
    const val CMD_CLSE = 0x434C5345
    const val CMD_WRTE = 0x57525445

    const val CONNECT_VERSION = 0x01000001
    const val CONNECT_MAX_PAYLOAD = 4096

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val HEADER_SIZE = 24

    /** One ADB message: header fields plus payload ([data] may be empty). */
    data class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray,
    )

    fun encode(message: Message): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + message.data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(message.command)
        buffer.putInt(message.arg0)
        buffer.putInt(message.arg1)
        buffer.putInt(message.data.size)
        buffer.putInt(crc32(message.data))
        buffer.putInt(message.command.inv())
        buffer.put(message.data)
        return buffer.array()
    }

    /** Decodes a header + payload pair, verifying the magic and checksum; throws when corrupt. */
    fun decode(
        header: ByteArray,
        data: ByteArray,
    ): Message {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        require(command == magic.inv()) { "adb magic mismatch" }
        require(length == data.size) { "adb payload length mismatch" }
        require(checksum == crc32(data)) { "adb crc mismatch" }
        return Message(command, arg0, arg1, data)
    }

    fun headerLength(header: ByteArray): Int = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)

    fun crc32(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    fun commandName(command: Int): String =
        when (command) {
            CMD_CNXN -> "CNXN"
            CMD_AUTH -> "AUTH"
            CMD_OPEN -> "OPEN"
            CMD_OKAY -> "OKAY"
            CMD_CLSE -> "CLSE"
            CMD_WRTE -> "WRTE"
            else -> "0x" + command.toUInt().toString(16)
        }

    /**
     * Encodes our RSA public key in Android's RSAPublicKey wire format (len/n0inv/n/e, all
     * little-endian) — base64 of the struct, then the " owner" suffix and NUL, exactly what the
     * AUTH(RSAPUBLICKEY) step expects and what the other phone shows in its allow dialog.
     */
    fun encodePublicKey(
        key: RSAPublicKey,
        suffix: String = DEFAULT_KEY_SUFFIX,
    ): ByteArray {
        val rawModulus = key.modulus.toByteArray()
        val modulusBytes =
            if (rawModulus.size > 1 && rawModulus[0] == 0.toByte()) {
                rawModulus.copyOfRange(
                    1,
                    rawModulus.size,
                )
            } else {
                rawModulus
            }
        val two32 = BigInteger.ONE.shiftLeft(32)
        val inverse = key.modulus.mod(two32).modInverse(two32)
        val n0inv = two32.subtract(inverse).mod(two32).toInt()
        val struct = ByteArray(8 + modulusBytes.size + 4)
        writeLeInt(struct, 0, modulusBytes.size / 4)
        writeLeInt(struct, 4, n0inv)
        for (index in modulusBytes.indices) struct[8 + index] = modulusBytes[modulusBytes.size - 1 - index]
        writeLeInt(struct, 8 + modulusBytes.size, key.publicExponent.toInt())
        val encoded = Base64.getEncoder().encode(struct)
        return encoded + (suffix + "\u0000").toByteArray(Charsets.US_ASCII)
    }

    private fun writeLeInt(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private const val DEFAULT_KEY_SUFFIX = " mushrea@code"
}
