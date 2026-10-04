package com.mushrea.code.device.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothLabelsTest {
    @Test
    fun `adapter states match the public android constants`() {
        assertEquals("on", BluetoothLabels.stateName(12))
        assertEquals("off", BluetoothLabels.stateName(10))
        assertEquals("turning-on", BluetoothLabels.stateName(11))
        assertEquals("turning-off", BluetoothLabels.stateName(13))
        assertEquals("le-only", BluetoothLabels.stateName(15))
        assertEquals("unknown", BluetoothLabels.stateName(99))
    }

    @Test
    fun `device type and bond labels`() {
        assertEquals("classic", BluetoothLabels.typeName(1))
        assertEquals("le", BluetoothLabels.typeName(2))
        assertEquals("dual", BluetoothLabels.typeName(3))
        assertEquals("bonded", BluetoothLabels.bondName(12))
        assertEquals("bonding", BluetoothLabels.bondName(11))
        assertEquals("none", BluetoothLabels.bondName(10))
    }

    @Test
    fun `major class and scan errors`() {
        assertEquals("computer", BluetoothLabels.majorClassName(0x0100))
        assertEquals("phone", BluetoothLabels.majorClassName(0x0200))
        assertEquals("audio-video", BluetoothLabels.majorClassName(0x0400))
        assertEquals("other", BluetoothLabels.majorClassName(0x1F00))
        assertEquals("already started", BluetoothLabels.scanErrorName(1))
        assertEquals("scanning too frequently - wait a few seconds", BluetoothLabels.scanErrorName(6))
        assertEquals("scan failed (9)", BluetoothLabels.scanErrorName(9))
    }
}
