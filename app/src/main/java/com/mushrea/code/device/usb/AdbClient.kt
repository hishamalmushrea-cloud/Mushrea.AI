package com.mushrea.code.device.usb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Byte pipe under the ADB protocol: USB bulk endpoints in production, a fake in unit tests. */
interface AdbTransport {
    /** Writes the whole buffer; false on timeout or failure. */
    fun write(
        data: ByteArray,
        timeoutMillis: Int,
    ): Boolean

    /** Reads up to [length] bytes into [data] at [offset]; returns the count or -1 on timeout. */
    fun read(
        data: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int

    fun close()
}

/** An ADB-level failure the agent core reports verbatim to the user. */
class AdbException(message: String) : Exception(message)

/**
 * Minimal host-side ADB client: the CNXN/AUTH handshake (signs the device token with our key and
 * offers the public key so the other phone shows its trust dialog) plus a shell stream that
 * collects output until the device closes it. Framing lives in [AdbProtocol]; sequencing here.
 */
class AdbClient(
    private val transport: AdbTransport,
    private val signer: (ByteArray) -> ByteArray,
    private val publicKey: ByteArray,
) {
    private var nextLocalId = 1
    private var connected = false

    /** The maximum payload the device accepts per WRTE (from its CNXN) — our send chunk cap. */
    var deviceMaxPayload: Int = AdbProtocol.CONNECT_MAX_PAYLOAD
        private set

    suspend fun connect(handshakeTimeoutMillis: Int = HANDSHAKE_TIMEOUT_MILLIS): Unit =
        withContext(Dispatchers.IO) {
            val deadline = deadline(handshakeTimeoutMillis)
            send(AdbProtocol.Message(AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAX_PAYLOAD, ByteArray(0)))
            var keyOffered = false
            while (true) {
                val message =
                    receive(deadline)
                        ?: throw AdbException("ADB connection timed out — accept the USB debugging prompt on the other phone")
                when (message.command) {
                    AdbProtocol.CMD_CNXN -> {
                        deviceMaxPayload = message.arg1.coerceAtLeast(1024)
                        connected = true
                        return@withContext
                    }
                    AdbProtocol.CMD_AUTH ->
                        when (message.arg0) {
                            AdbProtocol.AUTH_TOKEN ->
                                send(AdbProtocol.Message(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, signer(message.data)))
                            AdbProtocol.AUTH_RSAPUBLICKEY ->
                                if (keyOffered) {
                                    throw AdbException("the other phone rejected our key")
                                } else {
                                    keyOffered = true
                                    send(AdbProtocol.Message(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, publicKey))
                                }
                            else -> throw AdbException("unknown ADB auth type ${message.arg0}")
                        }
                    else -> throw AdbException("unexpected ${AdbProtocol.commandName(message.command)} during the ADB handshake")
                }
            }
        }

    /** Runs a shell command on the other phone and returns its full output (PTY newlines normalized). */
    suspend fun shell(
        command: String,
        timeoutMillis: Int = SHELL_TIMEOUT_MILLIS,
    ): String =
        withContext(Dispatchers.IO) {
            val stream = openStream("shell:$command")
            val output = ByteArrayOutputStream()
            val deadline = deadline(timeoutMillis)
            try {
                while (true) {
                    val data = stream.receive(deadline) ?: break
                    output.write(data)
                }
            } finally {
                stream.closeQuietly()
            }
            output.toString("UTF-8").replace("\r\n", "\n")
        }

    /** Opens a named service stream ("shell:…", "sync:", …) — one OPEN/OKAY exchange. */
    suspend fun openStream(service: String): AdbStream =
        withContext(Dispatchers.IO) {
            check(connected) { "not connected" }
            val streamId = nextLocalId++
            send(AdbProtocol.Message(AdbProtocol.CMD_OPEN, streamId, 0, service.toByteArray(Charsets.UTF_8)))
            val deadline = deadline(HANDSHAKE_TIMEOUT_MILLIS)
            while (true) {
                val message =
                    receive(deadline)
                        ?: throw AdbException("timed out opening the $service service")
                when (message.command) {
                    AdbProtocol.CMD_OKAY ->
                        if (message.arg1 == streamId) return@withContext AdbStream(this@AdbClient, streamId, message.arg0)
                    AdbProtocol.CMD_CLSE ->
                        if (message.arg1 == streamId) throw AdbException("the other phone refused the $service service")
                    else -> throw AdbException("unexpected ${AdbProtocol.commandName(message.command)} while opening $service")
                }
            }
            error("unreachable")
        }

    /** One ADB service stream: ordered payload frames with the protocol's per-frame flow control. */
    class AdbStream
            internal constructor(
                private val client: AdbClient,
                private val localId: Int,
                val remoteId: Int,
            ) {
            private val pending = ArrayDeque<ByteArray>()

            /** Sends one payload frame and waits for the device's acknowledgement. */
            suspend fun sendPayload(
                data: ByteArray,
                timeoutMillis: Int = SHELL_TIMEOUT_MILLIS,
            ): Unit =
                withContext(Dispatchers.IO) {
                    client.send(AdbProtocol.Message(AdbProtocol.CMD_WRTE, localId, remoteId, data))
                    val deadline = client.deadline(timeoutMillis)
                    while (true) {
                        val message =
                            client.receive(deadline)
                                ?: throw AdbException("ADB stream closed while waiting for the device acknowledgement")
                        when (message.command) {
                            AdbProtocol.CMD_OKAY -> if (message.arg1 == localId) return@withContext
                            AdbProtocol.CMD_WRTE -> {
                                pending.addLast(message.data)
                                client.send(AdbProtocol.Message(AdbProtocol.CMD_OKAY, localId, message.arg0, ByteArray(0)))
                            }
                            AdbProtocol.CMD_CLSE -> throw AdbException("the other phone closed the stream")
                            else -> throw AdbException("unexpected ${AdbProtocol.commandName(message.command)} on the stream")
                        }
                    }
                    error("unreachable")
                }

            /** Next data chunk from the device, or null once the device closed the stream. */
            suspend fun receive(deadline: Long): ByteArray? =
                withContext(Dispatchers.IO) {
                    pending.removeFirstOrNull()?.let { return@withContext it }
                    while (true) {
                        val message = client.receive(deadline) ?: return@withContext null
                        when (message.command) {
                            AdbProtocol.CMD_WRTE -> {
                                client.send(AdbProtocol.Message(AdbProtocol.CMD_OKAY, localId, message.arg0, ByteArray(0)))
                                return@withContext message.data
                            }
                            AdbProtocol.CMD_CLSE -> if (message.arg1 == localId) return@withContext null
                        }
                    }
                    error("unreachable")
                }

            fun closeQuietly() {
                runCatching { client.send(AdbProtocol.Message(AdbProtocol.CMD_CLSE, localId, remoteId, ByteArray(0))) }
            }
        }

    fun close() {
        runCatching { transport.close() }
    }

    private fun deadline(timeoutMillis: Int): Long = System.currentTimeMillis() + timeoutMillis

    private fun send(message: AdbProtocol.Message) {
        if (!transport.write(AdbProtocol.encode(message), WRITE_TIMEOUT_MILLIS)) throw AdbException("USB write failed")
    }

    private fun receive(deadline: Long): AdbProtocol.Message? {
        val header = ByteArray(AdbProtocol.HEADER_SIZE)
        if (readFully(header, deadline) != AdbProtocol.HEADER_SIZE) return null
        val length = AdbProtocol.headerLength(header)
        if (length < 0 || length > MAX_INCOMING_PAYLOAD) throw AdbException("corrupt ADB frame length $length")
        val data = ByteArray(length)
        if (readFully(data, deadline) != length) return null
        return runCatching { AdbProtocol.decode(header, data) }.getOrNull() ?: throw AdbException("corrupt ADB frame (checksum)")
    }

    private fun readFully(
        buffer: ByteArray,
        deadline: Long,
    ): Int {
        var offset = 0
        while (offset < buffer.size) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) return offset
            val count = transport.read(buffer, offset, buffer.size - offset, remaining)
            if (count <= 0) return offset
            offset += count
        }
        return offset
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MILLIS = 70_000
        const val SHELL_TIMEOUT_MILLIS = 15_000
        const val WRITE_TIMEOUT_MILLIS = 5_000
        const val MAX_INCOMING_PAYLOAD = 1 shl 20
    }
}
