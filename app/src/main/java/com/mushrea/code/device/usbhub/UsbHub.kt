package com.mushrea.code.device.usbhub

/**
 * The single classifier every attached USB device passes through: one place that decides what a
 * device is and which connection path handles it. Pure data in, verdict out, so the routing
 * table is unit-testable without an Android device.
 *
 * The app then routes by kind: ADB/fastboot to their existing agents, SERIAL to the
 * usb-serial-for-android stack (which already covers CH340, CP210x, FTDI, PL2303 and CDC-ACM -
 * that is every common Arduino and ESP32 setup), MTP/PTP to the android.mtp stack, HID and
 * mass storage to their own handlers, and anything else is reported honestly as OTHER.
 */
object UsbHub {
    enum class Kind {
        ADB,
        FASTBOOT,
        SERIAL,
        MTP,
        HID,
        STORAGE,
        OTHER,
    }

    data class Verdict(
        val kind: Kind,
        val driver: String,
        val hint: String,
    )

    /** One USB interface descriptor triple, as [android.hardware.usb.UsbInterface] reports it. */
    data class InterfaceTriple(
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
    )

    private val SERIAL_VENDORS =
        mapOf(
            0x1A86 to "ch340/ch341",
            0x10C4 to "cp210x",
            0x0403 to "ftdi",
            0x067B to "pl2303",
            0x2341 to "arduino (cdc-acm)",
            0x303A to "esp32 (usb-jtag/cdc)",
            0x2E8A to "raspberry-pi pico (cdc)",
        )

    private val CLASS_NAMES =
        mapOf(
            0x01 to "audio",
            0x02 to "cdc-control",
            0x03 to "hid",
            0x07 to "printer",
            0x08 to "mass-storage",
            0x09 to "hub",
            0x0A to "cdc-data",
            0x0E to "video",
            0x10 to "audio-video",
            0xE0 to "wireless-controller",
            0xFF to "vendor-specific",
        )

    /** The routing decision for one attached device. */
    fun classify(
        vendorId: Int,
        productId: Int,
        interfaces: List<InterfaceTriple>,
        productName: String?,
    ): Verdict {
        val lowerName = productName?.lowercase().orEmpty()
        if ("fastboot" in lowerName) return Verdict(Kind.FASTBOOT, "fastboot", "fastboot_getvar reads its identity")
        val byInterface =
            { wanted: (InterfaceTriple) -> Boolean -> interfaces.any(wanted) }
        if (byInterface { it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01 }) {
            return Verdict(Kind.ADB, "adb", "the usb phone tools (shell, files, scrcpy) work with it")
        }
        if (byInterface { it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x03 }) {
            return Verdict(Kind.FASTBOOT, "fastboot", "fastboot_getvar reads its identity")
        }
        SERIAL_VENDORS[vendorId]?.let { driver ->
            return Verdict(Kind.SERIAL, driver, "usb_serial_send / usb_serial_read talk to it")
        }
        if (byInterface {
                it.interfaceClass == 0x02
            } && byInterface { it.interfaceClass == 0x0A || (it.interfaceClass == 0x02 && it.interfaceSubclass == 0x02) }
        ) {
            return Verdict(Kind.SERIAL, "cdc-acm", "usb_serial_send / usb_serial_read talk to it")
        }
        if (byInterface { it.interfaceClass == 0x02 && it.interfaceSubclass == 0x02 && it.interfaceProtocol == 0x01 }) {
            return Verdict(Kind.SERIAL, "cdc-acm", "usb_serial_send / usb_serial_read talk to it")
        }
        if (byInterface { it.interfaceClass == 0x06 && it.interfaceSubclass == 0x01 }) {
            return Verdict(Kind.MTP, "mtp/ptp", "mtp_list / mtp_download browse and copy its files")
        }
        if (byInterface { it.interfaceClass == 0x03 }) {
            return Verdict(Kind.HID, "hid-raw", "a keyboard, mouse or other input device")
        }
        if (byInterface { it.interfaceClass == 0x08 }) {
            return Verdict(Kind.STORAGE, "mass-storage", "a USB flash drive or card reader")
        }
        val label = interfaces.firstNotNullOfOrNull { CLASS_NAMES[it.interfaceClass] } ?: "unclassified"
        return Verdict(Kind.OTHER, label, "detected; no dedicated handler for this class yet")
    }
}
