package com.mushrea.code.device.usbhub

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.storage.StorageManager
import com.mushrea.code.device.usb.AdbException
import com.mushrea.code.device.usb.UsbDeviceAgent
import org.json.JSONArray
import org.json.JSONObject

/**
 * The hub's handlers for the device kinds that need nothing heavier than the platform APIs:
 * HID boot-protocol input (decoded keyboard/mouse plus raw hex), removable storage volumes, the
 * camera list including externally attached USB cameras, and a Camera2 JPEG still.
 */
class HubExecutor(private val context: Context) {
    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val deviceAgent by lazy { UsbDeviceAgent(context) }

    /**
     * Reads HID input reports for [seconds], forces boot protocol when the device accepts it, and
     * decodes keyboard/mouse boot reports. Undecodable reports stay hex.
     */
    suspend fun executeHidRead(params: JSONObject): JSONObject.() -> Unit {
        val deviceId = if (params.has("device_id") && !params.isNull("device_id")) params.getInt("device_id") else null
        val seconds = params.optInt("seconds", 3).coerceIn(1, 10)
        val hid =
            findHid(deviceId)
                ?: throw AdbException("no HID device attached — plug a keyboard, mouse or HID sensor in with an OTG cable")
        if (!deviceAgent.ensurePermission(hid)) throw AdbException("USB permission was not granted for the HID device")
        val connection = usbManager.openDevice(hid) ?: throw AdbException("cannot open the USB device (USB permission needed first)")
        val reports = ArrayList<String>()
        val events = ArrayList<JSONObject>()
        var receivedBytes = 0
        var protocol = HidDecoder.PROTOCOL_NONE
        var bootProtocol = false
        try {
            var endpoint: android.hardware.usb.UsbEndpoint? = null
            var claimed: android.hardware.usb.UsbInterface? = null
            outer@ for (index in 0 until hid.interfaceCount) {
                val iface = hid.getInterface(index)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                for (e in 0 until iface.endpointCount) {
                    val candidate = iface.getEndpoint(e)
                    if (candidate.direction == UsbConstants.USB_DIR_IN && candidate.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                        claimed = iface
                        endpoint = candidate
                        protocol = iface.interfaceProtocol
                        break@outer
                    }
                }
            }
            if (claimed == null || endpoint == null) {
                throw AdbException("this HID device exposes no interrupt input endpoint we can read")
            }
            connection.claimInterface(claimed, true)
            // HID SET_PROTOCOL (class, interface) wValue=0 is boot protocol. Failure is normal on
            // devices that only speak report protocol; decoding then uses length as a hint.
            val set =
                connection.controlTransfer(
                    0x21,
                    0x0B,
                    0,
                    claimed.id,
                    null,
                    0,
                    500,
                )
            bootProtocol = set >= 0
            val buffer = ByteArray(endpoint.maxPacketSize.coerceIn(8, 512))
            val deadline = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < deadline && receivedBytes < MAX_REPORT_BYTES) {
                val count = connection.bulkTransfer(endpoint, buffer, buffer.size, 200)
                if (count > 0) {
                    receivedBytes += count
                    val packet = buffer.copyOf(count)
                    val hex = HidDecoder.toHex(packet)
                    reports.add(hex)
                    val decoded = HidDecoder.decode(packet, protocol)
                    events.add(decoded.toJson())
                }
            }
            runCatching { connection.releaseInterface(claimed) }
        } finally {
            runCatching { connection.close() }
        }
        return {
            put("device_id", hid.deviceId)
            put("seconds", seconds)
            put("report_count", reports.size)
            put("bytes", receivedBytes)
            put("boot_protocol", bootProtocol)
            put("interface_protocol", protocol)
            put("reports_hex", JSONArray(reports.take(64)))
            put("events", JSONArray(events.take(64)))
            put(
                "summary",
                if (reports.isEmpty()) {
                    "no input arrived in $seconds s — move the mouse or press keys on the device, or it may not be an input HID"
                } else {
                    val keys = events.count { it.optString("kind") == "keyboard" }
                    val mouse = events.count { it.optString("kind") == "mouse" }
                    "captured ${reports.size} HID report(s) ($receivedBytes bytes): $keys keyboard, $mouse mouse, decoded as boot protocol"
                },
            )
        }
    }

    /** Removable and built-in storage volumes Android can see, with their mount states. */
    fun executeStorageVolumes(): JSONObject.() -> Unit {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volumes = manager.storageVolumes
        val array =
            JSONArray(
                volumes.map { volume ->
                    // getDirectory and getState are API 30; below that only name/removability exist.
                    val rPlus = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                    JSONObject()
                        .put("name", volume.getDescription(context))
                        .put("removable", volume.isRemovable)
                        .put("state", if (rPlus) volume.state ?: JSONObject.NULL else JSONObject.NULL)
                        .put("path", if (rPlus) volume.directory?.absolutePath ?: JSONObject.NULL else JSONObject.NULL)
                },
            )
        val removable = volumes.count { it.isRemovable }
        return {
            put("volumes", array)
            put(
                "summary",
                "${volumes.size} storage volume(s), $removable removable. File browsing inside a removable drive needs the system file picker's one-time grant — that flow arrives with the remote file manager.",
            )
        }
    }

    /** The camera list, flagging externally attached USB cameras where the platform exposes them. */
    fun executeCameraList(): JSONObject.() -> Unit {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val array = JSONArray()
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: -1
            val facingName =
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external (usb)"
                    else -> "unknown"
                }
            array.put(
                JSONObject()
                    .put("id", id)
                    .put("facing", facingName)
                    .put("external", facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                    .put(
                        "hardware_level",
                        characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)?.toString() ?: JSONObject.NULL,
                    ),
            )
        }
        return {
            put("cameras", array)
            put(
                "summary",
                "${array.length()} camera(s) visible to Android. USB cameras appear here when the platform supports them. camera_capture takes a JPEG still from one of these ids.",
            )
        }
    }

    /** One JPEG still from a Camera2-visible camera, including USB/external when Android exposes it. */
    fun executeCameraCapture(params: JSONObject): JSONObject.() -> Unit {
        val file = CameraStillCapture(context).capture(params)
        return {
            put("path", file.absolutePath)
            put("bytes", file.length())
            put("summary", "saved a JPEG still (${file.length()} bytes) to ${file.absolutePath}")
        }
    }

    private fun findHid(deviceId: Int?): UsbDevice? =
        usbManager.deviceList.values
            .firstOrNull { candidate ->
                (deviceId == null || candidate.deviceId == deviceId) &&
                    (0 until candidate.interfaceCount).any { index ->
                        candidate.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_HID
                    }
            }

    private fun HidDecoder.Event.toJson(): JSONObject =
        JSONObject()
            .put("kind", kind)
            .put("hex", hex)
            .put("modifiers", JSONArray(modifiers))
            .put("keys", JSONArray(keys))
            .put("text", text)
            .put("buttons", JSONArray(buttons))
            .put("dx", dx ?: JSONObject.NULL)
            .put("dy", dy ?: JSONObject.NULL)
            .put("wheel", wheel ?: JSONObject.NULL)
            .put("note", note ?: JSONObject.NULL)

    private companion object {
        /** A raw-report capture should never balloon; 256 KiB covers seconds of input. */
        const val MAX_REPORT_BYTES = 256 * 1024
    }
}
