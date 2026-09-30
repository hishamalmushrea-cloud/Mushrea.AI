package com.mushrea.code.device.usb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class AdbSyncTest {
    @Test
    fun `list reads dent entries until the terminator`() =
        runBlocking {
            val transport = SyncFakeTransport()
            val device = FakeSyncDevice(transport)
            device.start()
            val client = AdbClient(transport, { it }, "pubkey".toByteArray())
            client.connect(handshakeTimeoutMillis = 10_000)
            val entries = AdbSync(client.openStream("sync:")).list("/sdcard/Pictures")
            client.close()
            assertEquals(listOf(AdbSync.Entry("a.jpg", isDirectory = false, size = 3)), entries)
        }

    @Test
    fun `stat and pull read the file bytes`() =
        runBlocking {
            val transport = SyncFakeTransport()
            val device = FakeSyncDevice(transport)
            device.start()
            val client = AdbClient(transport, { it }, "pubkey".toByteArray())
            client.connect(handshakeTimeoutMillis = 10_000)
            val sync = AdbSync(client.openStream("sync:"))
            val stat = sync.stat("/sdcard/Pictures/a.jpg")
            val output = ByteArrayOutputStream()
            val total = sync.pull("/sdcard/Pictures/a.jpg", output)
            client.close()
            assertEquals(AdbSync.Entry("/sdcard/Pictures/a.jpg", isDirectory = false, size = 3), stat)
            assertEquals(3L, total)
            assertEquals("abc", output.toString("UTF-8"))
        }

    @Test
    fun `push sends data frames and accepts the okay`() =
        runBlocking {
            val transport = SyncFakeTransport()
            val device = FakeSyncDevice(transport)
            device.start()
            val client = AdbClient(transport, { it }, "pubkey".toByteArray())
            client.connect(handshakeTimeoutMillis = 10_000)
            val sync = AdbSync(client.openStream("sync:"))
            ByteArrayInputStream("abc".toByteArray()).use { input ->
                sync.push("/sdcard/Download/x.txt", input)
            }
            client.close()
            assertArrayEquals("abc".toByteArray(), device.pushedBytes)
        }
}

private class SyncFakeTransport : AdbTransport {
    val toDevice = ArrayBlockingQueue<ByteArray>(256)
    val toHost = ArrayBlockingQueue<ByteArray>(256)
    private var leftover: ByteArray? = null
    private var leftoverOffset = 0

    override fun write(
        data: ByteArray,
        timeoutMillis: Int,
    ): Boolean = toDevice.offer(data)

    override fun read(
        data: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int {
        var chunk = leftover
        if (chunk == null) {
            chunk = toHost.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) ?: return -1
            leftoverOffset = 0
        }
        val count = minOf(chunk.size - leftoverOffset, length)
        System.arraycopy(chunk, leftoverOffset, data, offset, count)
        leftoverOffset += count
        leftover = if (leftoverOffset < chunk.size) chunk else null
        return count
    }

    override fun close() {
        toDevice.clear()
        toHost.clear()
    }
}

/** Serves the sync protocol the way the other phone would: DENT listings, DATA pulls, OKAY pushes. */
private class FakeSyncDevice(private val transport: SyncFakeTransport) {
    private val thread = Thread { serve() }
    private var buffer = ByteArray(0)

    @Volatile
    var pushedBytes = ByteArray(0)
        private set

    fun start() {
        thread.start()
    }

    private fun serve() {
        try {
            val cnxn = transport.toDevice.poll(5, TimeUnit.SECONDS) ?: return
            val header = cnxn.copyOfRange(0, AdbProtocol.HEADER_SIZE)
            val payload = cnxn.copyOfRange(AdbProtocol.HEADER_SIZE, cnxn.size)
            check(AdbProtocol.decode(header, payload).command == AdbProtocol.CMD_CNXN)
            send(AdbProtocol.Message(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, ByteArray(20)))
            check(read().let { it.command == AdbProtocol.CMD_AUTH && it.arg0 == AdbProtocol.AUTH_SIGNATURE })
            send(AdbProtocol.Message(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, ByteArray(1)))
            check(read().arg0 == AdbProtocol.AUTH_RSAPUBLICKEY)
            send(AdbProtocol.Message(AdbProtocol.CMD_CNXN, 1, 4096, ByteArray(0)))
            val open = read()
            check(open.command == AdbProtocol.CMD_OPEN && String(open.data, Charsets.UTF_8).startsWith("sync:"))
            send(AdbProtocol.Message(AdbProtocol.CMD_OKAY, 99, open.arg0, ByteArray(0)))
            while (true) {
                val message = read()
                if (message.command == AdbProtocol.CMD_CLSE) return
                if (message.command == AdbProtocol.CMD_WRTE) {
                    buffer += message.data
                    send(AdbProtocol.Message(AdbProtocol.CMD_OKAY, 99, message.arg0, ByteArray(0)))
                    while (handleRequest()) {
                        // drain every complete request already in the buffer
                    }
                }
            }
        } catch (error: Throwable) {
            // the client-side test fails on its own timeout when the fake cannot proceed
        }
    }

    /** Handles one complete sync request from the buffer; false while the buffer holds less than one. */
    private fun handleRequest(): Boolean {
        if (buffer.size < 8) return false
        val kind = String(buffer, 0, 4, Charsets.US_ASCII)
        if (kind == "DONE") {
            buffer = buffer.copyOfRange(8, buffer.size)
            send(AdbProtocol.Message(AdbProtocol.CMD_WRTE, 99, 1, "OKAY".toByteArray(Charsets.US_ASCII)))
            return true
        }
        val length = leInt(buffer, 4)
        if (buffer.size < 8 + length) return false
        val body = buffer.copyOfRange(8, 8 + length)
        buffer = buffer.copyOfRange(8 + length, buffer.size)
        when (kind) {
            "LIST" -> {
                send(AdbProtocol.Message(AdbProtocol.CMD_WRTE, 99, 1, dent("a.jpg", 33188, 3)))
                send(AdbProtocol.Message(AdbProtocol.CMD_WRTE, 99, 1, terminator()))
            }
            "STAT" -> {
                val frame = ByteArrayOutputStream(20)
                frame.write("STAT".toByteArray(Charsets.US_ASCII))
                writeInt(frame, 33188)
                writeInt(frame, 3)
                writeInt(frame, 0)
                writeInt(frame, 0)
                send(AdbProtocol.Message(AdbProtocol.CMD_WRTE, 99, 1, frame.toByteArray()))
            }
            "RECV" -> {
                val data = ByteArrayOutputStream(8 + 3)
                data.write("DATA".toByteArray(Charsets.US_ASCII))
                writeInt(data, 3)
                data.write("abc".toByteArray(Charsets.UTF_8))
                send(AdbProtocol.Message(AdbProtocol.CMD_WRTE, 99, 1, data.toByteArray()))
                val done = ByteArrayOutputStream(8)
                done.write("DONE".toByteArray(Charsets.US_ASCII))
                writeInt(done, 0)
                send(AdbProtocol.Message(AdbProtocol.CMD_WRTE, 99, 1, done.toByteArray()))
            }
            "SEND" -> pushedBytes = ByteArray(0)
            "DATA" -> pushedBytes += body
        }
        return true
    }

    private fun dent(
        name: String,
        mode: Int,
        size: Int,
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val frame = ByteArrayOutputStream(20 + nameBytes.size)
        frame.write("DENT".toByteArray(Charsets.US_ASCII))
        writeInt(frame, mode)
        writeInt(frame, size)
        writeInt(frame, 0)
        writeInt(frame, nameBytes.size)
        frame.write(nameBytes)
        return frame.toByteArray()
    }

    private fun terminator(): ByteArray {
        val frame = ByteArrayOutputStream(20)
        frame.write("DENT".toByteArray(Charsets.US_ASCII))
        repeat(4) { writeInt(frame, 0) }
        return frame.toByteArray()
    }

    private fun leInt(
        source: ByteArray,
        offset: Int,
    ): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeInt(
        target: ByteArrayOutputStream,
        value: Int,
    ) {
        target.write(value and 0xFF)
        target.write((value shr 8) and 0xFF)
        target.write((value shr 16) and 0xFF)
        target.write((value shr 24) and 0xFF)
    }

    private fun send(message: AdbProtocol.Message) {
        transport.toHost.offer(AdbProtocol.encode(message))
    }

    private fun read(): AdbProtocol.Message {
        val full = transport.toDevice.poll(5, TimeUnit.SECONDS) ?: throw IllegalStateException("device read timeout")
        val header = full.copyOfRange(0, AdbProtocol.HEADER_SIZE)
        val payload = full.copyOfRange(AdbProtocol.HEADER_SIZE, full.size)
        return AdbProtocol.decode(header, payload)
    }
}
