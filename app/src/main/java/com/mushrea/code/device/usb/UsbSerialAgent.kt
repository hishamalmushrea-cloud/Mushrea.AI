package com.mushrea.code.device.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * USB-serial half of the hardware feature (Arduino, ESP32, CH340/FTDI/CP210x adapters) on top of
 * usb-serial-for-android: lists attached ports, asks for the system USB permission (the same
 * dialog pattern as the ADB agent), then opens a configured port for send/read.
 */
class UsbSerialAgent(private val context: Context) {
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun ports(): List<UsbSerialDriver> =
        runCatching { UsbSerialProber.getDefaultProber().findAllDrivers(usbManager) }.getOrDefault(emptyList())

    fun hasPermission(driver: UsbSerialDriver): Boolean = runCatching { usbManager.hasPermission(driver.device) }.getOrDefault(false)

    /** Runs [block] with an open port at [baudrate] 8N1, DTR/RTS asserted, closing it after. */
    suspend fun <T> withPort(
        driver: UsbSerialDriver,
        baudrate: Int,
        block: suspend (UsbSerialPort) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            if (!ensurePermission(driver)) throw AdbException("USB permission was not granted for the serial device")
            val port = driver.ports.firstOrNull() ?: throw AdbException("the attached device exposes no serial port")
            val connection =
                usbManager.openDevice(
                    driver.device,
                ) ?: throw AdbException("cannot open the serial device (USB permission needed first)")
            try {
                port.open(connection)
                port.setParameters(baudrate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                runCatching { port.setDTR(true) }
                runCatching { port.setRTS(true) }
                block(port)
            } finally {
                runCatching { port.close() }
            }
        }

    suspend fun ensurePermission(driver: UsbSerialDriver): Boolean = if (hasPermission(driver)) true else requestPermission(driver.device)

    private suspend fun requestPermission(device: android.hardware.usb.UsbDevice): Boolean =
        suspendCancellableCoroutine { continuation ->
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
                val pendingIntent =
                    PendingIntent.getBroadcast(context, device.deviceId, Intent(action).setPackage(context.packageName), flags)
                usbManager.requestPermission(device, pendingIntent)
            }.onFailure {
                runCatching { context.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(false)
            }
        }

    private companion object {
        const val PERMISSION_ACTION_PREFIX = "com.mushrea.code.usb.SERIAL_PERMISSION_"
    }
}
