package com.mushrea.code.device.mirror

import java.io.ByteArrayOutputStream

/**
 * Binary encoder for the scrcpy 4.0 control protocol (client → device).
 *
 * Layouts are pinned by scrcpy's own serialization tests: touch is 32 bytes, keycode 14,
 * scroll 21, back 2, and the no-argument messages 1. Coordinates travel inside a
 * [Position] = x(i32) + y(i32) + screenWidth(u16) + screenHeight(u16), in the device's
 * frame pixel space; pressure is u16 fixed point over [0,1] and scroll values i16 fixed
 * point over [-1,1] (the device multiplies by 16).
 */
object ScrcpyControlMessages {
    /** Motion actions, matching Android's MotionEvent and scrcpy's wire values. */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_SCROLL = 8

    /** Control message types (scrcpy 4.0 ControlMessage). */
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_INJECT_SCROLL_EVENT = 3
    const val TYPE_BACK_OR_SCREEN_ON = 4
    const val TYPE_COLLAPSE_PANELS = 7
    const val TYPE_ROTATE_DEVICE = 11

    /** A few keycodes the control bar needs. */
    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_APP_SWITCH = 187

    /** [type 1][action 1][keycode 4][repeat 4][metaState 4] = 14 bytes. */
    fun injectKeycode(
        action: Int,
        keycode: Int,
        repeat: Int = 0,
        metaState: Int = 0,
    ): ByteArray =
        ByteArrayOutputStream(14).apply {
            write(TYPE_INJECT_KEYCODE)
            write(action)
            writeI32(keycode)
            writeI32(repeat)
            writeI32(metaState)
        }.toByteArray()

    /**
     * [type 1][action 1][pointerId 8][x 4][y 4][w 2][h 2][pressure 2][actionButton 4][buttons 4]
     * = 32 bytes. [x]/[y] are frame pixels inside the [frameWidth]x[frameHeight] space.
     */
    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        frameWidth: Int,
        frameHeight: Int,
        pressure: Float,
        actionButton: Int = 0,
        buttons: Int = 0,
    ): ByteArray =
        ByteArrayOutputStream(32).apply {
            write(TYPE_INJECT_TOUCH_EVENT)
            write(action)
            writeI64(pointerId)
            writeI32(x)
            writeI32(y)
            writeI16(frameWidth)
            writeI16(frameHeight)
            writeI16(u16FixedPoint(pressure))
            writeI32(actionButton)
            writeI32(buttons)
        }.toByteArray()

    /**
     * [type 1][x 4][y 4][w 2][h 2][hScroll 2][vScroll 2][buttons 4] = 21 bytes. Scroll values
     * are in wheel notches; the device decodes them over [-16, 16].
     */
    fun injectScroll(
        x: Int,
        y: Int,
        frameWidth: Int,
        frameHeight: Int,
        hScroll: Float,
        vScroll: Float,
        buttons: Int = 0,
    ): ByteArray =
        ByteArrayOutputStream(21).apply {
            write(TYPE_INJECT_SCROLL_EVENT)
            writeI32(x)
            writeI32(y)
            writeI16(frameWidth)
            writeI16(frameHeight)
            writeI16(i16FixedPoint(hScroll))
            writeI16(i16FixedPoint(vScroll))
            writeI32(buttons)
        }.toByteArray()

    /** [type 1][action 1] = 2 bytes: a back press, or waking the screen when it is off. */
    fun backOrScreenOn(action: Int): ByteArray =
        ByteArrayOutputStream(2).apply {
            write(TYPE_BACK_OR_SCREEN_ON)
            write(action)
        }.toByteArray()

    fun collapsePanels(): ByteArray = byteArrayOf(TYPE_COLLAPSE_PANELS.toByte())

    fun rotateDevice(): ByteArray = byteArrayOf(TYPE_ROTATE_DEVICE.toByte())

    /** One full key press as the device sees it: a down followed by an up. */
    fun tapKey(keycode: Int): List<ByteArray> =
        listOf(injectKeycode(ACTION_DOWN, keycode), injectKeycode(ACTION_UP, keycode))

    /** [0, 1] → [0, 65535], matching scrcpy's u16 fixed point. */
    internal fun u16FixedPoint(value: Float): Int = (value.coerceIn(0f, 1f) * 0xFFFF).toInt()

    /** [-1, 1] → [-32768, 32767], matching scrcpy's i16 fixed point. */
    internal fun i16FixedPoint(value: Float): Int = (value.coerceIn(-1f, 1f) * 0x8000).toInt()

    private fun ByteArrayOutputStream.writeI32(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.writeI16(value: Int) {
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeI64(value: Long) {
        for (shift in 56 downTo 0 step 8) {
            write((value ushr shift and 0xFF).toInt())
        }
    }
}
