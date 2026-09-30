package com.mushrea.code.device.usb

import java.net.InetSocketAddress
import java.net.Socket

/**
 * [AdbTransport] over a TCP socket: the exact same ADB framing, no cable. The phone's adbd must
 * be listening (usb_tcpip_enable or an already-paired wireless-debugging device) and our RSA key
 * must be trusted on it — the same key we use over USB, so a phone that authorized us once stays
 * authorized on the network.
 */
class TcpTransport(private val socket: Socket) : AdbTransport {
    override fun write(
        data: ByteArray,
        timeoutMillis: Int,
    ): Boolean =
        runCatching {
            socket.getOutputStream().apply {
                write(data)
                flush()
            }
            true
        }.getOrDefault(false)

    override fun read(
        data: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int =
        runCatching {
            socket.soTimeout = timeoutMillis.coerceAtLeast(1)
            socket.getInputStream().read(data, offset, length)
        }.getOrDefault(-1)

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000

        /** Opens a connected socket to [host]:[port] or throws with the reason. */
        fun connect(
            host: String,
            port: Int,
            connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
        ): Socket =
            Socket().apply {
                val failure =
                    runCatching { connect(InetSocketAddress(host, port), connectTimeoutMillis) }
                        .exceptionOrNull()
                if (failure != null) {
                    close()
                    throw AdbException("cannot reach $host:$port — is the phone on the same network with wireless debugging on?")
                }
            }
    }
}
