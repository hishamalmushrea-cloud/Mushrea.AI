package com.mushrea.code.device.usbhub

/**
 * PTP/MTP object format codes and the file-name rules the uploader uses.
 *
 * Kept pure so the mapping is unit-testable without an MTP device. Unknown extensions fall back
 * to "undefined" (0x3000) rather than a guessed image type — a wrong format is how some phones
 * refuse the object after accepting sendObjectInfo.
 */
object MtpFormats {
    const val UNDEFINED = 0x3000
    const val ASSOCIATION = 0x3001
    const val TEXT = 0x3004
    const val HTML = 0x3005
    const val WAV = 0x3008
    const val MP3 = 0x3009
    const val EXIF_JPEG = 0x3801
    const val BMP = 0x3804
    const val GIF = 0x3807
    const val PNG = 0x380B
    const val MP4 = 0xB982

    fun ofFileName(name: String): Int =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> EXIF_JPEG
            "png" -> PNG
            "gif" -> GIF
            "bmp" -> BMP
            "txt", "log", "md", "csv" -> TEXT
            "html", "htm" -> HTML
            "mp3" -> MP3
            "wav" -> WAV
            "mp4", "m4v" -> MP4
            else -> UNDEFINED
        }

    /** A single path segment the other device can store: no separators, not blank, ≤ 255 chars. */
    fun safeFileName(
        requested: String,
        fallback: String,
    ): String {
        val trimmed = requested.trim().ifBlank { fallback }
        val last = trimmed.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = last.replace(Regex("[\\x00-\\x1f]"), "_").trim().ifBlank { fallback }
        return cleaned.take(255)
    }
}
