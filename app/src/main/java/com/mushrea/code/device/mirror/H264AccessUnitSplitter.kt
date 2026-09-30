package com.mushrea.code.device.mirror

import java.io.ByteArrayOutputStream

/** One decodable H.264 access unit, already delimited and classified. */
class AccessUnit(
    val data: ByteArray,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
)

/**
 * Splits the raw Annex-B byte stream that `screenrecord --output-format=h264` writes on stdout
 * into access units a MediaCodec decoder can consume one buffer at a time.
 *
 * A start code (00 00 01, optionally preceded by an extra zero) opens each NAL unit; an access
 * unit ends when the next VCL NAL (slice, types 1-5) follows one, or at an AUD (type 9). Parameter
 * sets (SPS/PPS) are peeled off into a config unit, because MediaCodec wants them flagged as codec
 * data rather than fed as a normal frame. Everything else travels with the current unit.
 *
 * Bytes stay buffered until a boundary is found, so NAL units split across stream chunks are
 * handled without special cases; a hard cap drops the buffer if a boundary never comes.
 */
class H264AccessUnitSplitter {
    private var data = ByteArray(0)

    /** Index of the next start-code search inside [data]. */
    private var scan = 0

    /** Where the access unit currently being assembled begins, or -1 for none. */
    private var auStart = -1
    private var auHasVcl = false

    /**
     * Consumes one stream chunk and returns every access unit that became complete with it.
     * The returned list is fresh; the splitter keeps only bytes still being assembled.
     */
    fun feed(chunk: ByteArray): List<AccessUnit> {
        val merged = ByteArray(data.size + chunk.size)
        data.copyInto(merged)
        chunk.copyInto(merged, data.size)
        data = merged
        val results = ArrayList<AccessUnit>()
        while (true) {
            val code = findStartCode(scan) ?: break
            val nalStart = code.first + code.second
            val header = data.getOrNull(nalStart) ?: break
            val type = header.toInt() and 0x1F
            when {
                type == AUD -> {
                    if (auStart >= 0) emit(auStart, code.first, results)
                    auStart = code.first
                    auHasVcl = false
                }
                type in VCL_MIN..VCL_MAX -> {
                    if (auStart < 0) {
                        auStart = code.first
                    } else if (auHasVcl) {
                        emit(auStart, code.first, results)
                        auStart = code.first
                    }
                    auHasVcl = true
                }
                type == SPS || type == PPS -> {
                    when {
                        // A parameter set at the head of a stream opens its own unit.
                        auStart < 0 -> auStart = code.first
                        auHasVcl -> {
                            emit(auStart, code.first, results)
                            auStart = code.first
                            auHasVcl = false
                        }
                        // Still assembling the parameter header - keep collecting.
                        else -> Unit
                    }
                }
            }
            scan = nalStart
        }
        trim()
        if (data.size > MAX_PENDING_BYTES) {
            // A boundary should have come long ago; treat the stream as garbled and restart clean.
            data = ByteArray(0)
            scan = 0
            auStart = -1
            auHasVcl = false
        }
        return results
    }

    /** Emits whatever is still being assembled; called when the stream ends. */
    fun flush(): List<AccessUnit> {
        val results = ArrayList<AccessUnit>()
        if (auStart >= 0) emit(auStart, data.size, results)
        data = ByteArray(0)
        scan = 0
        auStart = -1
        auHasVcl = false
        return results
    }

    /** First start code at or after [from]: (index of its first 0x00, length of the code). */
    private fun findStartCode(from: Int): Pair<Int, Int>? {
        var i = from
        while (i <= data.size - 3) {
            if (data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ONE) {
                val start = if (i > from && data[i - 1] == ZERO) i - 1 else i
                return start to (i + 3 - start)
            }
            i++
        }
        return null
    }

    /** Classifies [from, to) and appends one or two access units. */
    private fun emit(
        from: Int,
        to: Int,
        results: MutableList<AccessUnit>,
    ) {
        if (to <= from) return
        val ranges = nalRanges(from, to)
        val paramRanges = ranges.filter { (data[it.first].toInt() and 0x1F) == SPS || (data[it.first].toInt() and 0x1F) == PPS }
        val hasVcl = ranges.any { (data[it.first].toInt() and 0x1F) in VCL_MIN..VCL_MAX }
        val isKey = ranges.any { (data[it.first].toInt() and 0x1F) == IDR }
        if (paramRanges.isEmpty()) {
            results.add(AccessUnit(data.copyOfRange(from, to), isConfig = false, isKeyFrame = isKey))
        } else if (!hasVcl) {
            results.add(AccessUnit(data.copyOfRange(from, to), isConfig = true, isKeyFrame = false))
        } else {
            // Parameters riding on a frame: peel them into a config unit ahead of the frame.
            val config = ByteArrayOutputStream()
            paramRanges.forEach { config.write(data, it.first, it.second - it.first) }
            results.add(AccessUnit(config.toByteArray(), isConfig = true, isKeyFrame = false))
            val frame = ByteArrayOutputStream()
            var cursor = from
            paramRanges.forEach { range ->
                if (range.first > cursor) frame.write(data, cursor, range.first - cursor)
                cursor = range.second
            }
            if (cursor < to) frame.write(data, cursor, to - cursor)
            results.add(AccessUnit(frame.toByteArray(), isConfig = false, isKeyFrame = isKey))
        }
    }

    /** Start-code-indexed ranges of every NAL inside [from, to). */
    private fun nalRanges(
        from: Int,
        to: Int,
    ): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        var i = from
        while (i < to) {
            val code = findStartCode(i) ?: break
            if (code.first >= to) break
            val nalStart = code.first + code.second
            val next = findStartCode(nalStart)
            // No following start code means this is the last NAL: it runs to `to`, not nothing.
            val end =
                when {
                    next == null || next.first > to -> to
                    else -> next.first
                }
            if (end > nalStart) ranges.add(nalStart to end)
            if (next == null) break
            i = nalStart
        }
        return ranges
    }

    private fun trim() {
        val keep =
            when {
                auStart >= 0 -> auStart
                else -> scan
            }.coerceAtMost(data.size)
        if (keep <= 0) return
        data = data.copyOfRange(keep, data.size)
        scan = (scan - keep).coerceAtLeast(0)
        if (auStart >= 0) auStart = (auStart - keep).coerceAtLeast(0)
    }

    private companion object {
        const val ZERO: Byte = 0
        const val ONE: Byte = 1

        /** NAL unit types the splitter cares about. */
        const val VCL_MIN = 1
        const val VCL_MAX = 5
        const val IDR = 5
        const val SEI = 6
        const val SPS = 7
        const val PPS = 8
        const val AUD = 9

        /** Safety valve for a stream that stopped producing boundaries. */
        const val MAX_PENDING_BYTES = 8 shl 20
    }
}
