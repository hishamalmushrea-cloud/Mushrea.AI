package com.mushrea.code.device.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/** [AdbTransport] over the ADB interface's bulk endpoints of an opened UsbDeviceConnection. */
class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) : AdbTransport {
    override fun write(
        data: ByteArray,
        timeoutMillis: Int,
    ): Boolean = connection.bulkTransfer(endpointOut, data, data.size, timeoutMillis) == data.size

    override fun read(
        data: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int {
        if (length == 0) return 0
        val chunk = ByteArray(length)
        val count = connection.bulkTransfer(endpointIn, chunk, length, timeoutMillis)
        if (count <= 0) return -1
        System.arraycopy(chunk, 0, data, offset, count)
        return count
    }

    override fun close() {
        runCatching { connection.close() }
    }
}
