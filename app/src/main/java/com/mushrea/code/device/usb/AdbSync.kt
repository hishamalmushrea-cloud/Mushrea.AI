package com.mushrea.code.device.usb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The adb sync protocol spoken on the "sync:" service: directory listing, stat, file pull and
 * push against the other phone's shared storage. Frames are little-endian ("LIST"/"STAT"/
 * "RECV"/"SEND"/"DENT"/"DATA"/"DONE"/"OKAY"/"FAIL") carried over [AdbClient.AdbStream].
 */
class AdbSync(
    private val stream: AdbClient.AdbStream,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    data class Entry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    private var leftover: ByteArray? = null
    private var leftoverOffset = 0

    /** Lists one directory; entries are bare names (join with the parent yourself). */
    suspend fun list(path: String): List<Entry> =
        withContext(Dispatchers.IO) {
            request("LIST", path.toByteArray(Charsets.UTF_8))
            val entries = ArrayList<Entry>()
            while (true) {
                when (readKind()) {
                    "FAIL" -> throw fail()
                    "DENT" -> {
                        val fields = readExactly(16)
                        val mode = leInt(fields, 0)
                        val size = leInt(fields, 4).toLong() and 0xFFFFFFFFL
                        val nameLength = leInt(fields, 12)
                        if (nameLength == 0) return@withContext entries
                        val name = String(readExactly(nameLength), Charsets.UTF_8)
                        if (name != "." && name != "..") entries += Entry(name, (mode and S_IFMT) == S_IFDIR, size)
                    }
                    else -> throw AdbException("unexpected sync frame while listing $path")
                }
            }
            error("unreachable")
        }

    /** Stats one path; null when the other phone reports it missing. */
    suspend fun stat(path: String): Entry? =
        withContext(Dispatchers.IO) {
            request("STAT", path.toByteArray(Charsets.UTF_8))
            when (readKind()) {
                "STAT" -> {
                    val fields = readExactly(16)
                    val mode = leInt(fields, 0)
                    val size = leInt(fields, 4).toLong() and 0xFFFFFFFFL
                    if (mode == 0) null else Entry(path, (mode and S_IFMT) == S_IFDIR, size)
                }
                "FAIL" -> throw fail()
                else -> throw AdbException("unexpected sync reply for stat $path")
            }
        }

    /** Pulls one file into [output]; returns the byte count the other phone sent. */
    suspend fun pull(
        remotePath: String,
        output: OutputStream,
    ): Long =
        withContext(Dispatchers.IO) {
            request("RECV", remotePath.toByteArray(Charsets.UTF_8))
            var total = 0L
            while (true) {
                when (readKind()) {
                    "FAIL" -> throw fail()
                    "DATA" -> {
                        val length = leInt(readExactly(4), 0)
                        output.write(readExactly(length))
                        total += length
                    }
                    "DONE" -> return@withContext total
                    else -> throw AdbException("unexpected sync frame while pulling $remotePath")
                }
            }
            error("unreachable")
        }

    /** Pushes [input] to [remotePath] with 0644 modes; the other phone validates the write. */
    suspend fun push(
        remotePath: String,
        input: InputStream,
    ): Unit =
        withContext(Dispatchers.IO) {
            request("SEND", (remotePath + ",0644").toByteArray(Charsets.UTF_8))
            val buffer = ByteArray(chunkSize)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                val frame = ByteArrayOutputStream(8 + count)
                frame.write("DATA".toByteArray(Charsets.US_ASCII))
                writeLeInt(frame, count)
                frame.write(buffer, 0, count)
                stream.sendPayload(frame.toByteArray())
            }
            val done = ByteArrayOutputStream(8)
            done.write("DONE".toByteArray(Charsets.US_ASCII))
            writeLeInt(done, (System.currentTimeMillis() / 1000).toInt())
            stream.sendPayload(done.toByteArray())
            when (readKind()) {
                "OKAY" -> return@withContext
                "FAIL" -> throw fail()
                else -> throw AdbException("unexpected sync reply pushing $remotePath")
            }
        }

    private suspend fun request(
        command: String,
        argument: ByteArray,
    ) {
        val frame = ByteArrayOutputStream(8 + argument.size)
        frame.write(command.toByteArray(Charsets.US_ASCII))
        writeLeInt(frame, argument.size)
        frame.write(argument)
        stream.sendPayload(frame.toByteArray())
    }

    private suspend fun readKind(): String = String(readExactly(4), Charsets.US_ASCII)

    private suspend fun fail(): AdbException {
        val length = leInt(readExactly(4), 0)
        return AdbException("the other phone: " + String(readExactly(length), Charsets.UTF_8))
    }

    private suspend fun readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            var source = leftover
            if (source == null || leftoverOffset >= source.size) {
                source =
                    stream.receive(System.currentTimeMillis() + SYNC_TIMEOUT_MILLIS)
                        ?: throw AdbException("the other phone closed the sync stream mid-frame")
                leftover = source
                leftoverOffset = 0
            }
            val take = minOf(source.size - leftoverOffset, count - filled)
            System.arraycopy(source, leftoverOffset, out, filled, take)
            leftoverOffset += take
            filled += take
        }
        return out
    }

    private fun writeLeInt(
        target: ByteArrayOutputStream,
        value: Int,
    ) {
        target.write(value and 0xFF)
        target.write((value shr 8) and 0xFF)
        target.write((value shr 16) and 0xFF)
        target.write((value shr 24) and 0xFF)
    }

    private fun leInt(
        source: ByteArray,
        offset: Int,
    ): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 4096
        const val SYNC_TIMEOUT_MILLIS = 120_000
        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
    }
}
