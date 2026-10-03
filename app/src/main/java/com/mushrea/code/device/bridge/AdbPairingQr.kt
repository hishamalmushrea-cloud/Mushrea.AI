package com.mushrea.code.device.bridge

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a pairing payload as a QR image.
 *
 * The project already depends on zxing for the *scanner* (`journeyapps:zxing-android-embedded` pulls
 * `com.google.zxing:core`, whose encoder is in that same artifact), so generating the code adds no
 * dependency at all - and no NDK, no network, no service.
 *
 * Only the drawing is here; the payload itself lives in [PeerAdbPairingPayload], where it is unit
 * tested. That split is deliberate: the part that must be exactly right (the AOSP field format that
 * the other phone's camera handler parses) never needs a bitmap to be checked.
 */
object AdbPairingQr {
    /** Error correction M survives a phone camera at an angle without inflating the grid too much. */
    private val ERROR_CORRECTION = ErrorCorrectionLevel.M
    private const val QUIET_ZONE_MODULES = 1

    /**
     * Draws [payload] at [sizePx] × [sizePx].
     *
     * A white quiet zone and a black grid, no colour: the other phone's camera handler is part of
     * Android's system UI, and a decorative QR that fails to scan costs the user the whole flow.
     */
    fun render(
        payload: PeerAdbPairingPayload,
        sizePx: Int,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(MIN_SIZE_PX)
        val matrix =
            QRCodeWriter().encode(
                payload.encode(),
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ERROR_CORRECTION,
                    EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private const val MIN_SIZE_PX = 256
}
