package com.mushrea.code.device.bluetooth

/**
 * Human-readable Bluetooth adapter / device labels. The numeric values are the public Android
 * constants (`BluetoothAdapter.STATE_*`, `BluetoothDevice.DEVICE_TYPE_*`, `BOND_*`, major class
 * codes, `ScanCallback.SCAN_FAILED_*`) so this file stays unit-testable without a radio.
 */
object BluetoothLabels {
    fun stateName(state: Int): String =
        when (state) {
            12 -> "on" // BluetoothAdapter.STATE_ON
            10 -> "off" // STATE_OFF
            11 -> "turning-on"
            13 -> "turning-off"
            15 -> "le-only" // hidden STATE_BLE_ON
            else -> "unknown"
        }

    fun typeName(type: Int): String =
        when (type) {
            1 -> "classic" // BluetoothDevice.DEVICE_TYPE_CLASSIC
            2 -> "le"
            3 -> "dual"
            else -> "unknown"
        }

    fun bondName(bond: Int): String =
        when (bond) {
            12 -> "bonded" // BluetoothDevice.BOND_BONDED
            11 -> "bonding"
            else -> "none"
        }

    fun majorClassName(major: Int): String =
        when (major) {
            0x0100 -> "computer"
            0x0200 -> "phone"
            0x0400 -> "audio-video"
            0x0700 -> "wearable"
            0x0800 -> "toy"
            0x0900 -> "health"
            0x0600 -> "imaging"
            else -> "other"
        }

    fun scanErrorName(errorCode: Int): String =
        when (errorCode) {
            1 -> "already started"
            6 -> "scanning too frequently - wait a few seconds"
            4 -> "le scanning unsupported"
            3 -> "internal error"
            5 -> "out of hardware resources"
            else -> "scan failed ($errorCode)"
        }
}
