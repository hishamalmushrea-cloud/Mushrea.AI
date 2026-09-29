package com.mushrea.code.device.usb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class AdbClientTest {
    @Test
    fun `handshake and shell round trip against a fake device`() =
        runBlocking {
            val transport = FakeTransport()
            val device = FakeAdbDevice(transport)
            var signed = false
            val client =
                AdbClient(transport, { token ->
                    signed = true
                    token
                }, "pubkey".toByteArray())
            device.start()
            client.connect(handshakeTimeoutMillis = 10_000)
            val output = client.shell("echo hi", timeoutMillis = 10_000)
            client.close()
            assertTrue("the device token must be signed during the handshake", signed)
            assertEquals("hello\n", output)
        }

    @Test
    fun `shell without connect fails fast`() =
        runBlocking {
            val client = AdbClient(FakeTransport(), { it }, "pubkey".toByteArray())
            try {
                client.shell("echo hi", timeoutMillis = 1_000)
                org.junit.Assert.fail("shell before connect must throw")
            } catch (expected: IllegalStateException) {
                // expected: not connected
            }
        }
}

private class FakeTransport : AdbTransport {
    val toDevice = ArrayBlockingQueue<ByteArray>(128)
    val toHost = ArrayBlockingQueue<ByteArray>(128)
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

private class FakeAdbDevice(private val transport: FakeTransport) {
    private val thread = Thread { serve() }

    fun start() {
        thread.start()
    }

    private fun serve() {
        try {
            val cnxn = read()
            check(cnxn.command == AdbProtocol.CMD_CNXN)
            send(AdbProtocol.Message(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, ByteArray(20)))
            val signature = read()
            check(signature.command == AdbProtocol.CMD_AUTH && signature.arg0 == AdbProtocol.AUTH_SIGNATURE)
            send(AdbProtocol.Message(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, ByteArray(1)))
            val key = read()
            check(key.command == AdbProtocol.CMD_AUTH && key.arg0 == AdbProtocol.AUTH_RSAPUBLICKEY)
            send(AdbProtocol.Message(AdbProtocol.CMD_CNXN, 1, 4096, ByteArray(0)))
            val open = read()
            check(open.command == AdbProtocol.CMD_OPEN)
            send(AdbProtocol.Message(AdbProtocol.CMD_OKAY, 99, open.arg0, ByteArray(0)))
            send(AdbProtocol.Message(AdbProtocol.CMD_WRTE, 99, open.arg0, "hello\n".toByteArray()))
            val okay = read()
            check(okay.command == AdbProtocol.CMD_OKAY)
            send(AdbProtocol.Message(AdbProtocol.CMD_CLSE, 99, open.arg0, ByteArray(0)))
        } catch (error: Throwable) {
            // the client-side test fails on its own timeout when the fake device cannot proceed
        }
    }

    private fun read(): AdbProtocol.Message {
        val full = transport.toDevice.poll(5, TimeUnit.SECONDS) ?: throw IllegalStateException("device read timeout")
        val header = full.copyOfRange(0, AdbProtocol.HEADER_SIZE)
        val payload = full.copyOfRange(AdbProtocol.HEADER_SIZE, full.size)
        return AdbProtocol.decode(header, payload)
    }

    private fun send(message: AdbProtocol.Message) {
        transport.toHost.offer(AdbProtocol.encode(message))
    }
}
