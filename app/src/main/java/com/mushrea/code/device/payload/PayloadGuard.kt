package com.mushrea.code.device.payload

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * The pre-flash ROM guard: answers "does this file belong to *this* device?" with evidence
 * instead of a vibe.
 *
 * What it actually verifies:
 * - container integrity — for ZIP (recovery/OTA ROMs) it parses the end-of-central-directory
 *   record, checks that the central directory fits inside the file, and spot-checks local
 *   headers; for `.tgz` (fastboot ROMs) it walks the gzip/tar structure;
 * - the device codename the archive declares — the `pre-device=` line of
 *   `META-INF/com/android/metadata`, the `getprop("ro.product.device") == "…"` assertion of the
 *   updater-script, or the `sky_global_images_…` top-level folder of a fastboot ROM;
 * - the region hint carried by the Xiaomi build code (e.g. `OS2.0.201.0.VMWINXM` → code `VMW`,
 *   region `IN`).
 *
 * What it deliberately does **not** claim: that the file is genuine or untampered. There is no
 * signature check here, and the result says so. A mismatch, or an integrity failure, sets
 * [Result.blocking] so the future flash gate can refuse without re-deriving the rule.
 *
 * Everything is read-only: the guard never writes to, extracts over, or mutates the archive.
 */
class PayloadGuard {
    data class Check(
        val name: String,
        val ok: Boolean,
        val detail: String,
    )

    data class Result(
        val path: String,
        val sizeBytes: Long,
        val kind: String,
        val integrity: String,
        val romProduct: String?,
        val romProductSource: String?,
        val buildId: String?,
        val region: String?,
        val regionCode: String?,
        val deviceProduct: String?,
        val verdict: String,
        val blocking: Boolean,
        val checks: List<Check>,
        val notes: List<String>,
    )

    /** What one archive scan could establish; every kind fills in as much as it honestly can. */
    private data class Facts(
        val integrity: String,
        val detail: String,
        val product: String? = null,
        val productSource: String? = null,
        val buildId: String? = null,
        val checks: List<Check> = emptyList(),
    )

    /** One central-directory record, reduced to what the guard needs. */
    private data class ZipEntryRef(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val localHeaderOffset: Long,
    )

    private data class ZipIndex(
        val entries: List<ZipEntryRef>,
        val totalEntries: Long,
        val facts: Facts,
    )

    private data class Eocd(
        val entries: Long,
        val cdSize: Long,
        val cdOffset: Long,
    )

    fun inspect(
        file: File,
        deviceProduct: String?,
        verifyLocalHeaders: Int = LOCAL_HEADER_SAMPLE,
    ): Result {
        if (!file.isFile || !file.canRead()) {
            return missingResult(file, deviceProduct)
        }
        val kind = detectKind(file)
        val facts =
            when (kind) {
                KIND_ZIP -> inspectZip(file, verifyLocalHeaders)
                KIND_TGZ -> inspectTarGz(file)
                KIND_PAYLOAD -> inspectPayload(file)
                else -> Facts("not-applicable", "no container check applies to this file type")
            }
        val region = regionOf(facts.buildId)
        val verdict = verdictFor(deviceProduct, facts.product)
        return Result(
            path = file.path,
            sizeBytes = file.length(),
            kind = kind,
            integrity = facts.integrity,
            romProduct = facts.product,
            romProductSource = facts.productSource,
            buildId = facts.buildId,
            region = region?.first,
            regionCode = region?.second,
            deviceProduct = deviceProduct,
            verdict = verdict,
            blocking = verdict == "mismatch" || facts.integrity == "failed",
            checks =
                listOf(
                    Check("file", true, "${file.length()} bytes"),
                    Check("integrity", facts.integrity != "failed", facts.detail),
                ) + facts.checks,
            notes =
                listOf(
                    "compatibility and integrity check only — it cannot prove the file is genuine or untampered",
                    "the codename comparison is the hard rule: a ROM whose device code differs from the attached device is refused",
                ),
        )
    }

    private fun missingResult(
        file: File,
        deviceProduct: String?,
    ): Result =
        Result(
            path = file.path,
            sizeBytes = 0,
            kind = "missing",
            integrity = "unverified",
            romProduct = null,
            romProductSource = null,
            buildId = null,
            region = null,
            regionCode = null,
            deviceProduct = deviceProduct,
            verdict = "unverified",
            blocking = true,
            checks = listOf(Check("file", false, "not a readable file: ${file.path}")),
            notes = listOf("nothing to inspect: the file does not exist"),
        )

    // region kind detection

    private fun detectKind(file: File): String {
        val magic = ByteArray(4)
        val read = runCatching { FileInputStream(file).use { it.read(magic) } }.getOrDefault(-1)
        if (read >= 4) {
            if (String(magic, Charsets.US_ASCII) == "CrAU") return KIND_PAYLOAD
            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) return KIND_ZIP
            if (magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) return KIND_TGZ
        }
        val name = file.name.lowercase()
        return when {
            name.endsWith(".zip") -> KIND_ZIP
            name.endsWith(".tgz") || name.endsWith(".tar.gz") -> KIND_TGZ
            name.endsWith(".img") || name.endsWith(".bin") -> KIND_IMAGE
            else -> KIND_UNKNOWN
        }
    }

    // endregion

    // region zip

    private fun inspectZip(
        file: File,
        verifyLocalHeaders: Int,
    ): Facts {
        val index = indexZip(file)
        if (index.facts.integrity == "failed") return index.facts
        val headerCheck = verifyLocalHeaderSample(file, index.entries, verifyLocalHeaders)
        val evidence = readZipEvidence(file, index.entries)
        val product = evidence.first
        val source = evidence.second
        val buildId = evidence.third ?: buildIdFrom(file.name)
        val integrity = if (headerCheck.ok) "verified" else "failed"
        return Facts(
            integrity = integrity,
            detail = index.facts.detail + "; " + headerCheck.detail,
            product = product,
            productSource = source,
            buildId = buildId,
            checks = index.facts.checks + headerCheck,
        )
    }

    private fun indexZip(file: File): ZipIndex {
        val checks = mutableListOf<Check>()
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val eocd =
                findEocd(raf, size)
                    ?: return ZipIndex(
                        emptyList(),
                        0,
                        Facts(
                            "failed",
                            "no end-of-central-directory record in the last 64 KiB — the ZIP is truncated",
                            checks = listOf(Check("zip-eocd", false, "clean end-of-central-directory not found")),
                        ),
                    )
            val cdEnd = eocd.cdOffset + eocd.cdSize
            val fits = eocd.cdOffset >= 0 && cdEnd <= size
            checks += Check("zip-central-directory", fits, "directory at ${eocd.cdOffset} + ${eocd.cdSize} bytes vs file size $size")
            if (!fits) {
                return ZipIndex(emptyList(), eocd.entries, Facts("failed", "the central directory runs past the end of the file", checks = checks))
            }
            val entries = readCentralDirectory(raf, eocd)
            checks += Check("zip-entries", entries.isNotEmpty(), "${entries.size} of ${eocd.entries} entr(y/ies) read")
            return ZipIndex(
                entries,
                eocd.entries,
                Facts(
                    "verified",
                    "EOCD parsed; central directory fits; ${entries.size}/${eocd.entries} entries read",
                    checks = checks,
                ),
            )
        }
    }

    private fun findEocd(
        raf: RandomAccessFile,
        size: Long,
    ): Eocd? {
        val window = minOf(size, EOCD_WINDOW.toLong()).toInt()
        if (window < 22) return null
        val windowStart = size - window
        val buffer = ByteArray(window)
        raf.seek(windowStart)
        raf.readFully(buffer)
        var position = window - 22
        while (position >= 0) {
            if (isSignature(buffer, position, 0x05, 0x06)) {
                val commentLength = readU16(buffer, position + 20)
                if (position + 22 + commentLength == window) {
                    return resolveEocd(raf, buffer, position, windowStart)
                }
            }
            position -= 1
        }
        return null
    }

    private fun resolveEocd(
        raf: RandomAccessFile,
        buffer: ByteArray,
        at: Int,
        windowStart: Long,
    ): Eocd {
        val entries = readU16(buffer, at + 10).toLong()
        val cdSize = readU32(buffer, at + 12)
        val cdOffset = readU32(buffer, at + 16)
        val zip64 = entries == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL
        if (!zip64) return Eocd(entries, cdSize, cdOffset)
        val locatorAt = at - 20
        if (locatorAt < 0 || !isSignature(buffer, locatorAt, 0x06, 0x07)) {
            return Eocd(entries, cdSize, cdOffset)
        }
        val zip64Offset = readU64(buffer, locatorAt + 8)
        val header = ByteArray(56)
        return runCatching {
            raf.seek(zip64Offset)
            if (raf.read(header) != 56) return@runCatching null
            if (!isSignature(header, 0, 0x06, 0x06)) return@runCatching null
            Eocd(entries = readU64(header, 32), cdSize = readU64(header, 40), cdOffset = readU64(header, 48))
        }.getOrNull() ?: Eocd(entries, cdSize, cdOffset)
    }

    private fun readCentralDirectory(
        raf: RandomAccessFile,
        eocd: Eocd,
    ): List<ZipEntryRef> {
        val toRead = minOf(eocd.cdSize, MAX_CD_BYTES.toLong()).toInt()
        if (toRead <= 0) return emptyList()
        val buffer = ByteArray(toRead)
        raf.seek(eocd.cdOffset)
        raf.readFully(buffer)
        val entries = mutableListOf<ZipEntryRef>()
        var at = 0
        while (at + 46 <= buffer.size && entries.size < MAX_CD_ENTRIES) {
            if (!isSignature(buffer, at, 0x01, 0x02)) break
            val method = readU16(buffer, at + 10)
            var compressedSize = readU32(buffer, at + 20)
            val nameLength = readU16(buffer, at + 28)
            val extraLength = readU16(buffer, at + 30)
            val commentLength = readU16(buffer, at + 32)
            var localHeaderOffset = readU32(buffer, at + 42)
            val nameEnd = at + 46 + nameLength
            if (nameEnd > buffer.size) break
            val name = String(buffer, at + 46, nameLength, Charsets.UTF_8)
            if (compressedSize == 0xFFFFFFFFL || localHeaderOffset == 0xFFFFFFFFL) {
                val zip64 = parseZip64Extra(buffer, nameEnd, extraLength, name)
                compressedSize = zip64?.first ?: compressedSize
                localHeaderOffset = zip64?.second ?: localHeaderOffset
            }
            entries += ZipEntryRef(name, method, compressedSize, localHeaderOffset)
            at = nameEnd + extraLength + commentLength
        }
        return entries
    }

    /** Reads the ZIP64 extra field; returns (compressedSize, localHeaderOffset) when present. */
    private fun parseZip64Extra(
        buffer: ByteArray,
        at: Int,
        length: Int,
        name: String,
    ): Pair<Long, Long>? {
        var position = at
        val end = at + length
        while (position + 4 <= end) {
            val id = readU16(buffer, position)
            val size = readU16(buffer, position + 2)
            val body = position + 4
            if (id == 0x0001 && body + size <= end) {
                var cursor = body
                if (name.isNotEmpty() && cursor + 8 <= body + size) cursor += 8
                val compressed = if (cursor + 8 <= body + size) readU64(buffer, cursor) else 0L
                cursor += 8
                val offset = if (cursor + 8 <= body + size) readU64(buffer, cursor) else 0L
                return compressed to offset
            }
            position = body + size
        }
        return null
    }

    private fun verifyLocalHeaderSample(
        file: File,
        entries: List<ZipEntryRef>,
        sample: Int,
    ): Check {
        if (entries.isEmpty()) return Check("zip-local-headers", false, "no entries to check")
        RandomAccessFile(file, "r").use { raf ->
            var verified = 0
            var bad = 0
            for (entry in entries.take(sample)) {
                val header = ByteArray(4)
                val readable =
                    runCatching {
                        raf.seek(entry.localHeaderOffset)
                        raf.read(header) == 4
                    }.getOrDefault(false)
                verified += 1
                if (!readable || !(header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte())) {
                    bad += 1
                }
            }
            return Check("zip-local-headers", bad == 0, "$verified header(s) checked, $bad bad")
        }
    }

    /** Returns (product, source, buildId) as read from the archive's own metadata. */
    private fun readZipEvidence(
        file: File,
        entries: List<ZipEntryRef>,
    ): Triple<String?, String?, String?> {
        val metadata = entries.firstOrNull { it.name.endsWith(METADATA_NAME) }
        val parsedMetadata = parseMetadata(metadata?.let { readEntryText(file, it) }.orEmpty())
        if (parsedMetadata.first != null) return parsedMetadata
        val script = entries.firstOrNull { it.name.endsWith(UPDATER_SCRIPT_NAME) }
        val asserted = parseUpdaterScript(script?.let { readEntryText(file, it) }.orEmpty())
        if (asserted != null) {
            return Triple(asserted, "updater-script device assertion", parsedMetadata.third)
        }
        return parsedMetadata
    }

    private fun parseMetadata(text: String): Triple<String?, String?, String?> {
        val values =
            text.lineSequence()
                .mapNotNull { line ->
                    val at = line.indexOf('=')
                    if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
                }.toMap()
        val postBuild = values["post-build"].orEmpty()
        val buildId = buildIdFrom(postBuild)
        values["pre-device"]?.takeIf { it.isNotBlank() }?.let {
            return Triple(it, "META-INF/com/android/metadata pre-device", buildId)
        }
        postBuild.split('/').getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
            return Triple(it, "post-build fingerprint", buildId)
        }
        return Triple(null, null, buildId)
    }

    private fun parseUpdaterScript(text: String): String? =
        PRODUCT_ASSERTION.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun readEntryText(
        file: File,
        entry: ZipEntryRef,
    ): String? {
        if (entry.compressedSize <= 0 || entry.compressedSize > MAX_TEXT_COMPRESSED) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(entry.localHeaderOffset)
                val header = ByteArray(30)
                if (raf.read(header) != 30) return@use null
                val dataOffset = entry.localHeaderOffset + 30 + readU16(header, 26) + readU16(header, 28)
                if (dataOffset + entry.compressedSize > raf.length()) return@use null
                val payload = ByteArray(entry.compressedSize.toInt())
                raf.seek(dataOffset)
                raf.readFully(payload)
                inflate(entry.method, payload)
            }
        }.getOrNull()
    }

    private fun inflate(
        method: Int,
        payload: ByteArray,
    ): String? {
        val stream =
            when (method) {
                0 -> ByteArrayInputStream(payload)
                8 -> InflaterInputStream(ByteArrayInputStream(payload), Inflater(true), 8 * 1024)
                else -> return null
            }
        return stream.use { it.readAtMost(MAX_TEXT_UNCOMPRESSED) }
    }

    // endregion

    // region tar.gz

    private fun inspectTarGz(file: File): Facts {
        val checks = mutableListOf<Check>()
        val fromName = productFromFileName(file.name)
        var product = fromName
        var source = if (fromName != null) "archive file name" else null
        var entries = 0
        var integrity = "verified"
        var detail = "gzip/tar structure walked"
        try {
            GZIPInputStream(FileInputStream(file).buffered(BUFFER_BYTES)).use { gzip ->
                while (entries < MAX_TAR_ENTRIES) {
                    val header = ByteArray(TAR_BLOCK)
                    if (gzip.readFully(header) < TAR_BLOCK || header.all { it == 0.toByte() }) break
                    val name = tarName(header)
                    val size = tarSize(header)
                    if (size == null) {
                        integrity = "unverified"
                        detail = "tar size field is not plain octal — structure not verified"
                        break
                    }
                    if (product == null) {
                        productFromEntryName(name)?.let {
                            product = it
                            source = "top-level ROM folder"
                        }
                    }
                    // Bodies are skipped, never inflated: walking a 6 GB fastboot ROM must stay
                    // cheap, and the entry names already carry the evidence we need.
                    skipFully(gzip, size)
                    skipPadding(gzip, size)
                    entries += 1
                }
            }
        } catch (error: Exception) {
            integrity = "failed"
            detail = "reading the gzip/tar stream failed: ${error.message}"
        }
        checks += Check("tgz-structure", integrity != "failed", detail)
        checks += Check("tgz-entries", entries > 0, "$entries tar entr(y/ies) walked")
        return Facts(
            integrity = integrity,
            detail = "$detail ($entries entries)",
            product = product,
            productSource = source,
            buildId = buildIdFrom(file.name),
            checks = checks,
        )
    }

    private fun tarName(header: ByteArray): String {
        val end = header.indexOfFirst { it == 0.toByte() }.let { if (it < 0) 100 else it }
        return String(header, 0, minOf(end, 100), Charsets.UTF_8).trim()
    }

    private fun tarSize(header: ByteArray): Long? {
        val raw = String(header, 124, 12, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        if (raw.isEmpty()) return 0L
        if (raw[0].code and 0x80 != 0) return null
        return runCatching { raw.toLong(8) }.getOrNull()
    }

    private fun productFromEntryName(name: String): String? =
        ROM_FOLDER.find(name.substringBefore('/'))?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun productFromFileName(name: String): String? =
        ROM_FILE_NAME.find(name)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // endregion

    // region payload.bin

    private fun inspectPayload(file: File): Facts {
        val parsed = runCatching { PayloadArchive.readInfo(file) }
        return parsed.fold(
            onSuccess = { info ->
                Facts(
                    integrity = "structurally-valid",
                    detail = "CrAU v2 payload with ${info.partitions.size} partition(s)",
                    checks = listOf(Check("payload", true, "payload.bin parsed (CrAU v2, ${info.partitions.size} partitions)")),
                )
            },
            onFailure = { error ->
                Facts(
                    integrity = "failed",
                    detail = error.message ?: "payload parse failed",
                    checks = listOf(Check("payload", false, error.message ?: "payload.bin could not be parsed")),
                )
            },
        )
    }

    // endregion

    // region shared helpers

    /** Returns (region name, region code) parsed from a Xiaomi build id such as `OS2.0.201.0.VMWINXM`. */
    fun regionOf(buildId: String?): Pair<String, String>? {
        if (buildId.isNullOrBlank()) return null
        val code = buildId.substringAfterLast('.').uppercase()
        if (code.length < 6 || !code.endsWith("XM")) return null
        val regionCode = code.substring(code.length - 4, code.length - 2)
        val name = REGION_NAMES[regionCode] ?: "$regionCode (not recognized by this build)"
        return name to regionCode
    }

    /** Finds the Xiaomi build code inside a fingerprint or file name. */
    fun buildIdFrom(text: String): String? {
        if (text.isBlank()) return null
        return BUILD_CODE.find(text)?.value
    }

    private fun verdictFor(
        deviceProduct: String?,
        romProduct: String?,
    ): String {
        val device = deviceProduct?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val rom = romProduct?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return when {
            device == null || rom == null -> "unverified"
            device == rom -> "match"
            else -> "mismatch"
        }
    }

    private fun isSignature(
        buffer: ByteArray,
        at: Int,
        b2: Int,
        b3: Int,
    ): Boolean {
        if (at < 0 || at + 3 >= buffer.size) return false
        return buffer[at] == 'P'.code.toByte() && buffer[at + 1] == 'K'.code.toByte() &&
            buffer[at + 2] == b2.toByte() && buffer[at + 3] == b3.toByte()
    }

    private fun readU16(
        buffer: ByteArray,
        at: Int,
    ): Int {
        if (at + 2 > buffer.size) return 0
        return (buffer[at].toInt() and 0xFF) or ((buffer[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32(
        buffer: ByteArray,
        at: Int,
    ): Long {
        if (at + 4 > buffer.size) return 0
        var value = 0L
        for (index in 3 downTo 0) {
            value = (value shl 8) or (buffer[at + index].toInt() and 0xFF).toLong()
        }
        return value
    }

    private fun readU64(
        buffer: ByteArray,
        at: Int,
    ): Long {
        if (at + 8 > buffer.size) return 0
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (buffer[at + index].toInt() and 0xFF).toLong()
        }
        return value
    }

    private fun InputStream.readAtMost(max: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString("UTF-8")
    }

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun skipFully(
        stream: InputStream,
        bytes: Long,
    ) {
        var remaining = bytes
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val read = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    /** tar pads every entry body to a 512-byte boundary. */
    private fun skipPadding(
        stream: InputStream,
        size: Long,
    ) {
        val padding = (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK
        if (padding > 0) skipFully(stream, padding)
    }

    // endregion

    companion object {
        const val KIND_ZIP = "recovery-or-ota-zip"
        const val KIND_TGZ = "fastboot-tgz"
        const val KIND_PAYLOAD = "payload-bin"
        const val KIND_IMAGE = "raw-image"
        const val KIND_UNKNOWN = "unknown"

        private const val EOCD_WINDOW = 65_557
        private const val MAX_CD_BYTES = 8 * 1024 * 1024
        private const val MAX_CD_ENTRIES = 4_000
        private const val LOCAL_HEADER_SAMPLE = 64
        private const val MAX_TEXT_COMPRESSED = 4 * 1024 * 1024
        private const val MAX_TEXT_UNCOMPRESSED = 4 * 1024 * 1024
        private const val MAX_TAR_ENTRIES = 400
        private const val TAR_BLOCK = 512

        /**
         * tar bodies such as `payload.bin` are skipped, never inflated: walking a 6 GB fastboot
         * ROM must not cost minutes of decompression.
         */
        private const val BUFFER_BYTES = 64 * 1024

        const val METADATA_NAME = "META-INF/com/android/metadata"
        const val UPDATER_SCRIPT_NAME = "META-INF/com/google/android/updater-script"

        /** Region suffixes Xiaomi's build codes use; anything else is reported verbatim. */
        private val REGION_NAMES =
            mapOf(
                "MI" to "Global",
                "IN" to "India",
                "CN" to "China",
            )

        /** `OS2.0.201.0.VMWINXM`, `V14.0.5.0.TMWEUXM`, … */
        private val BUILD_CODE = Regex("[A-Z]{1,3}[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+\\.[A-Z0-9]{4,8}")

        /** `sky_global_images_…` / `flourite_eea_images_…` */
        private val ROM_FOLDER =
            Regex("^([a-z][a-z0-9_]{1,20})_(global|eea|in|india|cn|china|ru|tr|id|tw|jp|vn|th|my|sg|ph|latam)_images_")

        /** `sky_global_images_OS2.0.201.0.VMWINXM_…tgz` */
        private val ROM_FILE_NAME =
            Regex("^([a-z][a-z0-9_]{1,20})_(global|eea|in|india|cn|china|ru|tr|id|tw|jp|vn|th|my|sg|ph|latam)_images_")

        private val PRODUCT_ASSERTION = Regex("getprop\\(\"ro\\.product\\.device\"\\)\\s*==\\s*\"([^\"]+)\"")
    }
}
