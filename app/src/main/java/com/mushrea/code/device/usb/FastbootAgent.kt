package com.mushrea.code.device.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Minimal host-side fastboot client, deliberately read-only: the bootloader protocol over the
 * USB fastboot interface (class 0xFF, subclass 0x42, protocol 0x03) limited to `getvar:` queries.
 * Flash/erase/oem commands are not wired — this phase is identification and diagnosis only.
 */
class FastbootAgent(private val context: Context) {
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun devices(): List<UsbDevice> =
        runCatching {
            usbManager.deviceList.values.filter { device ->
                (0 until device.interfaceCount).any { index ->
                    val iface = device.getInterface(index)
                    iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                        iface.interfaceSubclass == FASTBOOT_SUBCLASS &&
                        iface.interfaceProtocol == FASTBOOT_PROTOCOL
                }
            }
        }.getOrDefault(emptyList())

    /** Runs one `getvar:<variable>` query; (value, null) on success, (null, reason) on FAIL. */
    suspend fun getvar(
        device: UsbDevice,
        variable: String,
    ): Pair<String?, String?> {
        if (!ensurePermission(device)) throw AdbException("USB permission was not granted for the fastboot device")
        val endpoints = findEndpoints(device) ?: throw AdbException("no fastboot interface on the attached device")
        val connection =
            usbManager.openDevice(device)
                ?: throw AdbException("cannot open the fastboot device (USB permission needed first)")
        try {
            connection.claimInterface(endpoints.usbInterface, true)
            send(connection, endpoints.endpointOut, ("getvar:$variable\u0000").toByteArray(Charsets.US_ASCII))
            var status: Pair<String?, String?>? = null
            while (status == null) {
                val packet = receive(connection, endpoints.endpointIn)
                val prefix = String(packet.copyOfRange(0, 4), Charsets.US_ASCII)
                val body = String(packet.copyOfRange(4, packet.size), Charsets.US_ASCII)
                status =
                    when (prefix) {
                        "OKAY" -> body to null
                        "FAIL" -> null to body.ifBlank { "the bootloader refused the query" }
                        "INFO" -> null // informational lines before the verdict; keep waiting
                        else -> null to "unexpected bootloader response '$prefix'"
                    }
            }
            return status
        } finally {
            runCatching { connection.close() }
        }
    }

    private fun findEndpoints(device: UsbDevice): Endpoints? {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC ||
                iface.interfaceSubclass != FASTBOOT_SUBCLASS ||
                iface.interfaceProtocol != FASTBOOT_PROTOCOL
            ) {
                continue
            }
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (endpointIndex in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            if (input != null && output != null) return Endpoints(iface, input, output)
        }
        return null
    }

    private fun send(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        data: ByteArray,
    ) {
        val sent = connection.bulkTransfer(endpoint, data, data.size, SEND_TIMEOUT_MILLIS)
        if (sent != data.size) throw AdbException("the bootloader stopped answering (USB write failed)")
    }

    private fun receive(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
    ): ByteArray {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        val count = connection.bulkTransfer(endpoint, buffer, buffer.size, RECEIVE_TIMEOUT_MILLIS)
        if (count < 4) throw AdbException("the bootloader sent no usable response (is it still in fastboot mode?)")
        return buffer.copyOf(count)
    }

    suspend fun ensurePermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { continuation ->
            if (runCatching { usbManager.hasPermission(device) }.getOrDefault(false)) {
                continuation.resume(true)
                return@suspendCancellableCoroutine
            }
            val action = PERMISSION_ACTION_PREFIX + device.deviceId
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context,
                        intent: Intent,
                    ) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        runCatching { receiverContext.unregisterReceiver(this) }
                        if (continuation.isActive) continuation.resume(granted)
                    }
                }
            runCatching {
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                usbManager.requestPermission(
                    device,
                    PendingIntent.getBroadcast(context, device.deviceId, Intent(action).setPackage(context.packageName), flags),
                )
            }.onFailure {
                runCatching { context.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(false)
            }
        }

    private data class Endpoints(
        val usbInterface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint,
    )

    private companion object {
        const val FASTBOOT_SUBCLASS = 0x42
        const val FASTBOOT_PROTOCOL = 0x03
        const val SEND_TIMEOUT_MILLIS = 3_000
        const val RECEIVE_TIMEOUT_MILLIS = 5_000
        const val PERMISSION_ACTION_PREFIX = "com.mushrea.code.usb.FASTBOOT_PERMISSION_"
    }
}
