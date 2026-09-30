package com.mushrea.code.device.usbhub

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbHubClassifierTest {
    private fun iface(
        c: Int,
        s: Int,
        p: Int,
    ) = UsbHub.InterfaceTriple(c, s, p)

    @Test
    fun `an adb phone is recognised by its vendor interface`() {
        val verdict =
            UsbHub.classify(
                0x18D1,
                0x4EE7,
                listOf(iface(0xFF, 0x42, 0x01)),
                "Pixel",
            )
        assertEquals(UsbHub.Kind.ADB, verdict.kind)
    }

    @Test
    fun `a phone in bootloader mode reads as fastboot`() {
        val verdict =
            UsbHub.classify(
                0x18D1,
                0x4EE0,
                listOf(iface(0xFF, 0x42, 0x03)),
                null,
            )
        assertEquals(UsbHub.Kind.FASTBOOT, verdict.kind)
    }

    @Test
    fun `the word fastboot in the product name wins`() {
        val verdict =
            UsbHub.classify(
                0x1234,
                0x5678,
                listOf(iface(0x08, 0x06, 0x50)),
                "Android Bootloader Interface",
            )
        assertEquals(UsbHub.Kind.FASTBOOT, verdict.kind)
    }

    @Test
    fun `a ch340 arduino clone routes to the ch340 serial driver`() {
        val verdict =
            UsbHub.classify(
                0x1A86,
                0x7523,
                listOf(iface(0xFF, 0xFF, 0x00)),
                "USB2.0-Serial",
            )
        assertEquals(UsbHub.Kind.SERIAL, verdict.kind)
        assertEquals("ch340/ch341", verdict.driver)
    }

    @Test
    fun `an official arduino uno with its cdc interface is serial`() {
        val verdict =
            UsbHub.classify(
                0x2341,
                0x0043,
                listOf(iface(0x02, 0x02, 0x01), iface(0x0A, 0x00, 0x00)),
                "Arduino Uno",
            )
        assertEquals(UsbHub.Kind.SERIAL, verdict.kind)
        assertEquals("arduino (cdc-acm)", verdict.driver)
    }

    @Test
    fun `an esp32 with the espressif vendor id is serial`() {
        val verdict =
            UsbHub.classify(
                0x303A,
                0x1001,
                listOf(iface(0xFF, 0x01, 0x00)),
                "ESP32-S3",
            )
        assertEquals(UsbHub.Kind.SERIAL, verdict.kind)
        assertEquals("esp32 (usb-jtag/cdc)", verdict.driver)
    }

    @Test
    fun `a generic cdc-acm adapter with unknown ids is serial`() {
        val verdict =
            UsbHub.classify(
                0x1234,
                0x5678,
                listOf(iface(0x02, 0x02, 0x01), iface(0x0A, 0x00, 0x00)),
                null,
            )
        assertEquals(UsbHub.Kind.SERIAL, verdict.kind)
        assertEquals("cdc-acm", verdict.driver)
    }

    @Test
    fun `a phone in file transfer mode classifies as mtp`() {
        val verdict =
            UsbHub.classify(
                0x2717,
                0xFF48,
                listOf(iface(0x06, 0x01, 0x01)),
                "Redmi Note",
            )
        assertEquals(UsbHub.Kind.MTP, verdict.kind)
        assertEquals("mtp/ptp", verdict.driver)
    }

    @Test
    fun `a ptp camera classifies under the same mtp stack`() {
        val verdict =
            UsbHub.classify(
                0x04A9,
                0x3229,
                listOf(iface(0x06, 0x01, 0x01)),
                "Canon Camera",
            )
        assertEquals(UsbHub.Kind.MTP, verdict.kind)
    }

    @Test
    fun `a mouse classifies as hid`() {
        val verdict =
            UsbHub.classify(
                0x046D,
                0xC077,
                listOf(iface(0x03, 0x01, 0x02)),
                "USB Mouse",
            )
        assertEquals(UsbHub.Kind.HID, verdict.kind)
    }

    @Test
    fun `a flash drive classifies as mass storage`() {
        val verdict =
            UsbHub.classify(
                0x0951,
                0x1666,
                listOf(iface(0x08, 0x06, 0x50)),
                "DataTraveler",
            )
        assertEquals(UsbHub.Kind.STORAGE, verdict.kind)
    }

    @Test
    fun `an unknown audio gadget stays honest as other`() {
        val verdict =
            UsbHub.classify(
                0x0D8C,
                0x013C,
                listOf(iface(0x01, 0x01, 0x00), iface(0x01, 0x02, 0x00)),
                "USB Audio",
            )
        assertEquals(UsbHub.Kind.OTHER, verdict.kind)
        assertEquals("audio", verdict.driver)
    }
}
