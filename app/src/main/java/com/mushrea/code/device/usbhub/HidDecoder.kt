package com.mushrea.code.device.usbhub

/**
 * Boot-protocol HID decoder for USB keyboards and mice.
 *
 * Full report-descriptor parsing is per-device work and is not claimed here. What this class does
 * is the documented HID boot protocol (usage page 0x01, protocol 1 = keyboard, protocol 2 = mouse):
 * an 8-byte keyboard report becomes modifiers + keys + optional text, a 3- or 4-byte mouse report
 * becomes buttons and motion. Anything else is returned as raw hex so the caller never invents
 * input that did not arrive.
 *
 * [protocol] is [android.hardware.usb.UsbInterface.getInterfaceProtocol]: 1 keyboard, 2 mouse, 0
 * unspecified. When it is 0 the report length is used as a hint.
 */
object HidDecoder {
    const val PROTOCOL_NONE = 0
    const val PROTOCOL_KEYBOARD = 1
    const val PROTOCOL_MOUSE = 2

    data class Event(
        val kind: String,
        val hex: String,
        val modifiers: List<String> = emptyList(),
        val keys: List<String> = emptyList(),
        val text: String = "",
        val buttons: List<String> = emptyList(),
        val dx: Int? = null,
        val dy: Int? = null,
        val wheel: Int? = null,
        val note: String? = null,
    )

    fun decode(
        report: ByteArray,
        protocol: Int = PROTOCOL_NONE,
    ): Event {
        val hex = toHex(report)
        if (report.isEmpty()) {
            return Event(kind = "empty", hex = hex, note = "the device sent a zero-length report")
        }
        val guessed = if (protocol == PROTOCOL_NONE) guessProtocol(report.size) else protocol
        return when (guessed) {
            PROTOCOL_KEYBOARD -> decodeKeyboard(report, hex)
            PROTOCOL_MOUSE -> decodeMouse(report, hex)
            else -> Event(kind = "raw", hex = hex, note = "not a boot-protocol keyboard or mouse report")
        }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun guessProtocol(size: Int): Int =
        when (size) {
            8 -> PROTOCOL_KEYBOARD
            3, 4 -> PROTOCOL_MOUSE
            else -> PROTOCOL_NONE
        }

    private fun decodeKeyboard(
        report: ByteArray,
        hex: String,
    ): Event {
        if (report.size < 3) {
            return Event(kind = "raw", hex = hex, note = "keyboard boot reports are 8 bytes")
        }
        val modifiers = modifiersOf(report[0].toInt() and 0xFF)
        val keyBytes = report.copyOfRange(2, minOf(report.size, 8))
        if (keyBytes.all { it == 0x01.toByte() }) {
            return Event(
                kind = "keyboard",
                hex = hex,
                modifiers = modifiers,
                note = "phantom key rollover — too many keys held at once",
            )
        }
        val keys = keyBytes.map { it.toInt() and 0xFF }.filter { it != 0 }.map { keyName(it) }
        val shifted = modifiers.any { it.endsWith("shift") }
        val text =
            keyBytes
                .map { it.toInt() and 0xFF }
                .mapNotNull { keyText(it, shifted) }
                .joinToString("")
        return Event(
            kind = "keyboard",
            hex = hex,
            modifiers = modifiers,
            keys = keys,
            text = text,
            note = if (keys.isEmpty() && modifiers.isEmpty()) "all keys released" else null,
        )
    }

    private fun decodeMouse(
        report: ByteArray,
        hex: String,
    ): Event {
        if (report.isEmpty()) return Event(kind = "raw", hex = hex)
        val buttons = buttonsOf(report[0].toInt() and 0xFF)
        val dx = if (report.size > 1) report[1].toInt() else 0
        val dy = if (report.size > 2) report[2].toInt() else 0
        val wheel = if (report.size > 3) report[3].toInt() else null
        return Event(
            kind = "mouse",
            hex = hex,
            buttons = buttons,
            dx = dx,
            dy = dy,
            wheel = wheel,
        )
    }

    private fun modifiersOf(bits: Int): List<String> {
        val names =
            listOf(
                0x01 to "left-ctrl",
                0x02 to "left-shift",
                0x04 to "left-alt",
                0x08 to "left-gui",
                0x10 to "right-ctrl",
                0x20 to "right-shift",
                0x40 to "right-alt",
                0x80 to "right-gui",
            )
        return names.mapNotNull { (mask, name) -> name.takeIf { bits and mask != 0 } }
    }

    private fun buttonsOf(bits: Int): List<String> {
        val names = listOf(0x01 to "left", 0x02 to "right", 0x04 to "middle", 0x08 to "back", 0x10 to "forward")
        return names.mapNotNull { (mask, name) -> name.takeIf { bits and mask != 0 } }
    }

    private fun keyName(usage: Int): String = KEY_NAMES[usage] ?: "usage-0x%02x".format(usage)

    private fun keyText(
        usage: Int,
        shifted: Boolean,
    ): String? {
        val pair = KEY_TEXT[usage] ?: return null
        return if (shifted) pair.second else pair.first
    }

    private val KEY_NAMES: Map<Int, String> =
        mapOf(
            0x04 to "a",
            0x05 to "b",
            0x06 to "c",
            0x07 to "d",
            0x08 to "e",
            0x09 to "f",
            0x0A to "g",
            0x0B to "h",
            0x0C to "i",
            0x0D to "j",
            0x0E to "k",
            0x0F to "l",
            0x10 to "m",
            0x11 to "n",
            0x12 to "o",
            0x13 to "p",
            0x14 to "q",
            0x15 to "r",
            0x16 to "s",
            0x17 to "t",
            0x18 to "u",
            0x19 to "v",
            0x1A to "w",
            0x1B to "x",
            0x1C to "y",
            0x1D to "z",
            0x1E to "1",
            0x1F to "2",
            0x20 to "3",
            0x21 to "4",
            0x22 to "5",
            0x23 to "6",
            0x24 to "7",
            0x25 to "8",
            0x26 to "9",
            0x27 to "0",
            0x28 to "enter",
            0x29 to "escape",
            0x2A to "backspace",
            0x2B to "tab",
            0x2C to "space",
            0x2D to "minus",
            0x2E to "equal",
            0x2F to "left-bracket",
            0x30 to "right-bracket",
            0x31 to "backslash",
            0x33 to "semicolon",
            0x34 to "apostrophe",
            0x35 to "grave",
            0x36 to "comma",
            0x37 to "dot",
            0x38 to "slash",
            0x39 to "caps-lock",
            0x3A to "f1",
            0x3B to "f2",
            0x3C to "f3",
            0x3D to "f4",
            0x3E to "f5",
            0x3F to "f6",
            0x40 to "f7",
            0x41 to "f8",
            0x42 to "f9",
            0x43 to "f10",
            0x44 to "f11",
            0x45 to "f12",
            0x46 to "print-screen",
            0x47 to "scroll-lock",
            0x48 to "pause",
            0x49 to "insert",
            0x4A to "home",
            0x4B to "page-up",
            0x4C to "delete",
            0x4D to "end",
            0x4E to "page-down",
            0x4F to "right",
            0x50 to "left",
            0x51 to "down",
            0x52 to "up",
        )

    /** Unshifted then shifted printable forms for the boot-keyboard usage page. */
    private val KEY_TEXT: Map<Int, Pair<String, String>> =
        mapOf(
            0x04 to ("a" to "A"),
            0x05 to ("b" to "B"),
            0x06 to ("c" to "C"),
            0x07 to ("d" to "D"),
            0x08 to ("e" to "E"),
            0x09 to ("f" to "F"),
            0x0A to ("g" to "G"),
            0x0B to ("h" to "H"),
            0x0C to ("i" to "I"),
            0x0D to ("j" to "J"),
            0x0E to ("k" to "K"),
            0x0F to ("l" to "L"),
            0x10 to ("m" to "M"),
            0x11 to ("n" to "N"),
            0x12 to ("o" to "O"),
            0x13 to ("p" to "P"),
            0x14 to ("q" to "Q"),
            0x15 to ("r" to "R"),
            0x16 to ("s" to "S"),
            0x17 to ("t" to "T"),
            0x18 to ("u" to "U"),
            0x19 to ("v" to "V"),
            0x1A to ("w" to "W"),
            0x1B to ("x" to "X"),
            0x1C to ("y" to "Y"),
            0x1D to ("z" to "Z"),
            0x1E to ("1" to "!"),
            0x1F to ("2" to "@"),
            0x20 to ("3" to "#"),
            0x21 to ("4" to "$"),
            0x22 to ("5" to "%"),
            0x23 to ("6" to "^"),
            0x24 to ("7" to "&"),
            0x25 to ("8" to "*"),
            0x26 to ("9" to "("),
            0x27 to ("0" to ")"),
            0x2C to (" " to " "),
            0x2D to ("-" to "_"),
            0x2E to ("=" to "+"),
            0x2F to ("[" to "{"),
            0x30 to ("]" to "}"),
            0x31 to ("\\" to "|"),
            0x33 to (";" to ":"),
            0x34 to ("'" to "\""),
            0x35 to ("`" to "~"),
            0x36 to ("," to "<"),
            0x37 to ("." to ">"),
            0x38 to ("/" to "?"),
        )
}
