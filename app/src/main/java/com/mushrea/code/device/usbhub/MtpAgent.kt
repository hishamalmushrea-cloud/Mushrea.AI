package com.mushrea.code.device.usbhub

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.mtp.MtpStorageInfo
import android.os.ParcelFileDescriptor
import com.mushrea.code.device.usb.AdbException
import com.mushrea.code.device.usb.UsbDeviceAgent
import java.io.File
import java.io.OutputStream

/** One file or folder inside an MTP/PTP device's object tree. */
data class MtpEntry(
    val handle: Int,
    val name: String,
    val isFolder: Boolean,
    val formatCode: Int,
    val sizeBytes: Long,
    val parent: Int,
    val dateCreated: Long?,
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
 * Reads are served as whole byte arrays, so one object is capped at 2 GiB by the platform's
 * own int-sized API - larger objects are reported honestly instead of silently truncated.
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
            if (!mtp.open(connection)) {
                throw AdbException("the MTP device refused to open (switch the phone to File Transfer mode and try again)")
            }
            return block(mtp)
        } finally {
            runCatching { mtp.close() }
            runCatching { connection.close() }
        }
    }

    fun storages(mtp: MtpDevice): List<MtpVolume> {
        val ids = mtp.storageIds ?: IntArray(0)
        return ids
            .toList()
            .mapNotNull { id ->
                val info: MtpStorageInfo? = runCatching { mtp.getStorageInfo(id) }.getOrNull()
                info?.let { MtpVolume(storageId = id, description = it.description, maxCapacityBytes = it.maxCapacity) }
            }
    }

    /** Lists the children of [parent] on [storageId] (MTP root parent handle is 0). */
    fun listChildren(
        mtp: MtpDevice,
        storageId: Int,
        parent: Int,
    ): List<MtpEntry> {
        val handles = runCatching { mtp.getObjectHandles(storageId, 0, parent) }.getOrNull() ?: IntArray(0)
        return handles
            .toList()
            .mapNotNull { handle ->
                val info: MtpObjectInfo? = runCatching { mtp.getObjectInfo(handle) }.getOrNull()
                info?.toEntry()
            }
            .sortedWith(compareByDescending<MtpEntry> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    /**
     * Creates a new object on [storageId] under [parent] and sends [file]'s bytes. Returns the
     * handle the device assigned. The platform's sendObject path is int-sized, so objects above
     * 2 GiB are refused instead of truncated.
     */
    fun upload(
        mtp: MtpDevice,
        storageId: Int,
        parent: Int,
        file: File,
        name: String,
    ): Int {
        if (!file.isFile) throw AdbException("no local file at ${file.absolutePath}")
        if (file.length() > Int.MAX_VALUE.toLong()) {
            throw AdbException("this file is larger than the platform's 2 GiB MTP write cap")
        }
        val info =
            MtpObjectInfo.Builder()
                .setStorageId(storageId)
                .setParent(parent)
                .setFormat(MtpFormats.ofFileName(name))
                .setName(name)
                .setCompressedSize(file.length())
                .build()
        val created =
            runCatching { mtp.sendObjectInfo(info) }.getOrNull()
                ?: throw AdbException("the MTP device refused to create $name")
        val handle = created.objectHandle
        val sent =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                runCatching { mtp.sendObject(handle, file.length(), pfd) }.getOrDefault(false)
            }
        if (!sent) throw AdbException("the MTP device refused the data for $name")
        return handle
    }

    /** Copies one object into [output]; honest failure when the device refuses. */
    fun download(
        mtp: MtpDevice,
        handle: Int,
        output: OutputStream,
    ) {
        val info: MtpObjectInfo =
            runCatching { mtp.getObjectInfo(handle) }.getOrNull()
                ?: throw AdbException("the MTP device no longer knows object $handle")
        if (info.compressedSize > Int.MAX_VALUE.toLong()) {
            throw AdbException("this object is larger than the platform's 2 GiB MTP read cap")
        }
        val bytes: ByteArray? = runCatching { mtp.getObject(handle, info.compressedSize.toInt()) }.getOrNull()
        if (bytes == null) throw AdbException("the MTP device refused the transfer of this object")
        output.write(bytes)
    }

    /** True when the object is a thumbnail-able still image (PTP format codes). */
    fun isImage(formatCode: Int): Boolean =
        formatCode == 0x3808 || // JPEG
            formatCode == 0x3809 || // PNG
            formatCode == 0x3801 || // GIF
            formatCode == 0x3804 // BMP

    private fun MtpObjectInfo.toEntry(): MtpEntry =
        MtpEntry(
            handle = objectHandle,
            name = name,
            isFolder = format == 0x3001, // PTP Association (folder)
            formatCode = format,
            sizeBytes = compressedSize.toLong(),
            parent = parent,
            dateCreated = runCatching { dateCreated }.getOrNull(),
        )
}
