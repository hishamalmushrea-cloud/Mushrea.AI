package com.mushrea.code.device.usbhub

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.mtp.MtpStorageInfo
import com.mushrea.code.device.usb.AdbException
import com.mushrea.code.device.usb.UsbDeviceAgent
import java.io.OutputStream

/** One file or folder inside an MTP/PTP device's object tree. */
data class MtpEntry(
    val handle: Int,
    val name: String,
    val isFolder: Boolean,
    val formatCode: Int,
    val sizeBytes: Long,
    val parent: Int,
    val dateCreated: String?,
)

/** One storage volume (internal memory, SD card…) of an MTP/PTP device. */
data class MtpVolume(
    val storageId: Int,
    val description: String?,
    val maxCapacityBytes: Long,
)

/**
 * MTP/PTP initiator over the public android.mtp API: phones in file-transfer mode, cameras and
 * media players that expose the still-image class. The phone is the USB host; no root, and the
 * same USB permission prompt every other hardware feature uses.
 *
 * Upload arrives in a later phase; this phase reads (list + download) so the agent can answer
 * "what is on the other phone" and copy files over honestly.
 */
class MtpAgent(private val context: Context) {
    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val deviceAgent by lazy { UsbDeviceAgent(context) }

    /** Every attached device the hub classifies as MTP/PTP. */
    fun mtpDevices(): List<UsbDevice> = runCatching { usbManager.deviceList.values.filter(::looksLikeMtp) }.getOrDefault(emptyList())

    private fun looksLikeMtp(device: UsbDevice): Boolean {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.interfaceClass == 0x06 && iface.interfaceSubclass == 0x01) return true
        }
        return false
    }

    /** Runs [block] against an opened MTP device, closing everything on the way out. */
    suspend fun <T> withMtp(
        deviceId: Int?,
        block: (MtpDevice) -> T,
    ): T {
        val device =
            mtpDevices().firstOrNull { deviceId == null || it.deviceId == deviceId }
                ?: throw AdbException("no MTP/PTP device attached — set the other phone to File Transfer (MTP) mode")
        if (!deviceAgent.ensurePermission(device)) throw AdbException("USB permission was not granted for the MTP device")
        val connection = usbManager.openDevice(device) ?: throw AdbException("cannot open the USB device (USB permission needed first)")
        val mtp = MtpDevice(device)
        try {
            if (!mtp.open(
                    connection,
                )
            ) {
                throw AdbException("the MTP device refused to open (switch the phone to File Transfer mode and try again)")
            }
            return block(mtp)
        } finally {
            runCatching { mtp.close() }
            runCatching { connection.close() }
        }
    }

    fun storages(mtp: MtpDevice): List<MtpVolume> =
        mtp.storageIds.mapNotNull { id ->
            val info: MtpStorageInfo? = runCatching { mtp.getStorageInfo(id) }.getOrNull()
            MtpVolume(
                storageId = id,
                description = info?.description,
                maxCapacityBytes = info?.maxCapacity ?: 0L,
            )
        }

    /** Lists the children of [parent] on [storageId] (MTP root parent handle is 0). */
    fun listChildren(
        mtp: MtpDevice,
        storageId: Int,
        parent: Int,
    ): List<MtpEntry> {
        val handles: LongArray = runCatching { mtp.getObjectHandles(storageId, 0, parent) }.getOrDefault(LongArray(0))
        return handles
            .mapNotNull { handle ->
                val info: MtpObjectInfo? = runCatching { mtp.getObjectInfo(handle.toInt()) }.getOrNull()
                info?.toEntry()
            }
            .sortedWith(compareByDescending<MtpEntry> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    /** Downloads one object into [output]; thumbnails are served directly for images. */
    fun download(
        mtp: MtpDevice,
        handle: Int,
        output: OutputStream,
    ): Boolean {
        val info: MtpObjectInfo =
            runCatching { mtp.getObjectInfo(handle) }.getOrNull()
                ?: throw AdbException("the MTP device no longer knows object $handle")
        val size = info.compressedSize
        return if (size > 0) {
            mtp.getObject(handle, size, output)
        } else {
            mtp.getObject(handle, output)
        }
    }

    /** True when the object is a thumbnail-able still image, so a small preview is cheap. */
    fun isImage(formatCode: Int): Boolean =
        formatCode == MtpConstants.FORMAT_JPEG ||
            formatCode == MtpConstants.FORMAT_PNG ||
            formatCode == MtpConstants.FORMAT_GIF ||
            formatCode == MtpConstants.FORMAT_BMP

    private fun MtpObjectInfo.toEntry(): MtpEntry =
        MtpEntry(
            handle = objectHandle,
            name = name,
            isFolder = format == MtpConstants.FORMAT_ASSOCIATION,
            formatCode = format,
            sizeBytes = compressedSize,
            parent = parent,
            dateCreated = runCatching { dateCreated }.getOrNull(),
        )
}
