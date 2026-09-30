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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.coroutines.resume

/**
 * USB-host plumbing for controlling another Android phone over a cable: lists ADB-capable
 * devices, asks the user for the per-device USB permission (system dialog), then runs shell
 * commands through [AdbClient] — where the other phone shows its own "Allow USB debugging?" RSA
 * prompt. No root, and no permission on either phone is bypassed.
 */
class UsbDeviceAgent(private val context: Context) {
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** ADB-capable phones currently attached (interface class 0xFF, subclass 0x42, protocol 1). */
    fun adbDevices(): List<UsbDevice> = runCatching { usbManager.deviceList.values.filter(::hasAdbInterface) }.getOrDefault(emptyList())

    fun hasPermission(device: UsbDevice): Boolean = runCatching { usbManager.hasPermission(device) }.getOrDefault(false)

    /** Asks the system to show the USB permission dialog for this phone; true once granted. */
    suspend fun ensurePermission(device: UsbDevice): Boolean = if (hasPermission(device)) true else requestPermission(device)

    /** Runs a shell command on the first attached phone: permission → open → handshake → shell. */
    suspend fun shell(
        command: String,
        timeoutMillis: Int = SHELL_TIMEOUT_MILLIS,
    ): String = withConnection { it.shell(command, timeoutMillis) }

    /** Lists one directory on the other phone (adb sync over the "sync:" service). */
    suspend fun listRemote(path: String): List<AdbSync.Entry> = withSync { it.list(path) }

    /** Stats one path on the other phone. */
    suspend fun statRemote(path: String): AdbSync.Entry? = withSync { it.stat(path) }

    /** One sync session for multi-file transfers — everything runs over a single connection. */
    suspend fun <T> withSync(block: suspend (AdbSync) -> T): T =
        withConnection { client -> block(AdbSync(client.openStream("sync:"), client.deviceMaxPayload)) }

    /**
     * Switches the attached phone's adbd to also listen on TCP (the adb tcpip flow) and returns
     * its Wi-Fi address read while the cable is still connected — adbd restarts right after.
     */
    suspend fun enableTcpip(): JSONObject =
        withContext(Dispatchers.IO) {
            withConnection { client ->
                val address = remoteAddress(client)
                val output = ByteArrayOutputStream()
                val stream =
                    runCatching { client.openStream("tcpip:5555") }.getOrNull()
                        ?: client.openStream("host:tcpip:5555")
                val deadline = System.currentTimeMillis() + TCPIP_TIMEOUT_MILLIS
                while (true) {
                    val data = stream.receive(deadline) ?: break
                    output.write(data)
                }
                stream.closeQuietly()
                JSONObject().put("address", address ?: JSONObject.NULL).put("output", output.toString("UTF-8").trim())
            }
        }

    private suspend fun remoteAddress(client: AdbClient): String? {
        val focused = runCatching { client.shell("ip -f inet addr show wlan0", timeoutMillis = 10_000) }.getOrDefault("")
        Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(focused)?.let { return it.groupValues[1] }
        val all = runCatching { client.shell("ip -f inet addr show", timeoutMillis = 10_000) }.getOrDefault("")
        return Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").findAll(all).map { it.groupValues[1] }.firstOrNull()
    }

    /** Runs a shell command over Wi-Fi on a phone whose adbd listens (see [enableTcpip]). */
    suspend fun tcpShell(
        host: String,
        port: Int,
        command: String,
        timeoutMillis: Int = SHELL_TIMEOUT_MILLIS,
    ): String =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(java.net.InetSocketAddress(host, port), TcpTransport.CONNECT_TIMEOUT_MILLIS)
                val keys = AdbKeys.loadOrCreate(context)
                val client =
                    AdbClient(
                        TcpTransport(socket),
                        adbSigner(keys),
                        AdbProtocol.encodePublicKey(keys.public as RSAPublicKey),
                    )
                client.connect(handshakeTimeoutMillis = 20_000)
                client.shell(command, timeoutMillis)
            } finally {
                runCatching { socket.close() }
            }
        }

    /** Grabs the other phone's screen as PNG bytes via the raw exec service (no PTY mangling). */
    suspend fun screenshot(): ByteArray =
        withConnection { client ->
            val stream = client.openStream("exec:screencap -p")
            val output = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MILLIS
            while (true) {
                val data = stream.receive(deadline) ?: break
                output.write(data)
            }
            stream.closeQuietly()
            output.toByteArray()
        }

    /** Installs a local APK on the other phone: push to /data/local/tmp, then pm install. */
    suspend fun install(
        localFile: File,
        timeoutMillis: Int = INSTALL_TIMEOUT_MILLIS,
    ): String =
        withContext(Dispatchers.IO) {
            val remotePath = "/data/local/tmp/mushrea-install-" + System.currentTimeMillis() + ".apk"
            withSync { sync -> localFile.inputStream().use { input -> sync.push(remotePath, input) } }
            val output = shell("pm install -r '$remotePath'", timeoutMillis)
            runCatching { shell("rm -f '$remotePath'") }
            output.trim()
        }

    /** Dumps the other phone's recent log lines (logcat -d -t N). */
    suspend fun logcat(lines: Int): String = shell("logcat -d -t $lines")

    /** Read-only identity/status facts about the other phone (props, battery, storage). */
    suspend fun deviceInfo(): JSONObject =
        withConnection { client ->
            JSONObject()
                .put("model", client.shell("getprop ro.product.model").trim())
                .put("brand", client.shell("getprop ro.product.brand").trim())
                .put("android_version", client.shell("getprop ro.build.version.release").trim())
                .put("sdk", client.shell("getprop ro.build.version.sdk").trim())
                .put(
                    "battery_raw",
                    client.shell("dumpsys battery").lineSequence().filter { it.contains("level") }.take(2).joinToString("\n").trim(),
                )
                .put("storage_raw", client.shell("df -k /sdcard | head -3").trim())
        }

    private suspend fun <T> withConnection(block: suspend (AdbClient) -> T): T =
        withContext(Dispatchers.IO) {
            val device =
                adbDevices().firstOrNull()
                    ?: throw AdbException("no ADB phone attached — connect one and enable USB debugging on it")
            if (!ensurePermission(device)) throw AdbException("USB permission was not granted for the other phone")
            val endpoints = findAdbEndpoints(device) ?: throw AdbException("no ADB interface on the attached device")
            val connection = usbManager.openDevice(device) ?: throw AdbException("cannot open the USB device (USB permission needed first)")
            try {
                connection.claimInterface(endpoints.usbInterface, true)
                val keys = AdbKeys.loadOrCreate(context)
                val client =
                    AdbClient(
                        UsbTransport(connection, endpoints.endpointIn, endpoints.endpointOut),
                        adbSigner(keys),
                        AdbProtocol.encodePublicKey(keys.public as RSAPublicKey),
                    )
                client.connect()
                block(client)
            } finally {
                runCatching { connection.close() }
            }
        }

    /**
     * Opens the ADB connection and hands it back without closing: the live mirror needs one
     * connection to survive many screenrecord takes. Call [PersistentAdbConnection.close] when
     * done - it is the caller's cleanup, not [withConnection]'s.
     */
    suspend fun openPersistentConnection(): PersistentAdbConnection =
        withContext(Dispatchers.IO) {
            val device =
                adbDevices().firstOrNull()
                    ?: throw AdbException("no ADB phone attached — connect one and enable USB debugging on it")
            if (!ensurePermission(device)) throw AdbException("USB permission was not granted for the other phone")
            val endpoints = findAdbEndpoints(device) ?: throw AdbException("no ADB interface on the attached device")
            val connection = usbManager.openDevice(device) ?: throw AdbException("cannot open the USB device (USB permission needed first)")
            try {
                connection.claimInterface(endpoints.usbInterface, true)
                val keys = AdbKeys.loadOrCreate(context)
                val client =
                    AdbClient(
                        UsbTransport(connection, endpoints.endpointIn, endpoints.endpointOut),
                        adbSigner(keys),
                        AdbProtocol.encodePublicKey(keys.public as RSAPublicKey),
                    )
                client.connect()
                PersistentAdbConnection(client, connection)
            } catch (t: Throwable) {
                runCatching { connection.close() }
                throw t
            }
        }

    /** Pushes in-memory bytes to the other phone (used to plant the scrcpy server binary). */
    suspend fun pushBytes(
        data: ByteArray,
        remotePath: String,
    ) {
        withSync { sync -> ByteArrayInputStream(data).use { input -> sync.push(remotePath, input) } }
    }

    /** An ADB connection the caller owns until it explicitly closes it. */
    class PersistentAdbConnection(
        val client: AdbClient,
        private val usbConnection: UsbDeviceConnection,
    ) {
        fun close() {
            runCatching { usbConnection.close() }
        }
    }

    private fun adbSigner(keys: KeyPair): (ByteArray) -> ByteArray =
        { token ->
            val signature = Signature.getInstance("SHA1withRSA")
            signature.initSign(keys.private)
            signature.update(token)
            signature.sign()
        }

    private fun hasAdbInterface(device: UsbDevice): Boolean = findAdbEndpoints(device) != null

    private fun findAdbEndpoints(device: UsbDevice): AdbEndpoints? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC ||
                usbInterface.interfaceSubclass != ADB_SUBCLASS ||
                usbInterface.interfaceProtocol != ADB_PROTOCOL
            ) {
                continue
            }
            var endpointIn: UsbEndpoint? = null
            var endpointOut: UsbEndpoint? = null
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) endpointIn = endpoint else endpointOut = endpoint
            }
            if (endpointIn != null && endpointOut != null) return AdbEndpoints(usbInterface, endpointIn, endpointOut)
        }
        return null
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean =
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

    private data class AdbEndpoints(
        val usbInterface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint,
    )

    companion object {
        private const val PERMISSION_ACTION_PREFIX = "com.mushrea.code.usb.PERMISSION_"
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
        const val SHELL_TIMEOUT_MILLIS = 20_000
        private const val EXEC_TIMEOUT_MILLIS = 30_000
        private const val TCPIP_TIMEOUT_MILLIS = 15_000
        private const val INSTALL_TIMEOUT_MILLIS = 180_000
    }
}
