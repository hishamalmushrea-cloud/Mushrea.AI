package com.mushrea.code.device.payload

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import org.tukaani.xz.XZInputStream

/**
 * Read-only analyser for OTA `payload.bin` delta archives (version 2): parses the header and the
 * manifest with a small generic protobuf walker, lists the partitions each payload updates, and
 * can reconstruct one partition image from the RAW and XZ operations. Pure analysis — nothing
 * here touches a bootloader; flashing stays out of scope on purpose.
 */
object PayloadArchive {
    data class Operation(
        val type: Int,
        val dataOffset: Long,
        val dataLength: Long,
        val dstOffset: Long,
        val dstLength: Long,
    )

    data class Partition(
        val name: String,
        val operations: List<Operation>,
    )

    data class Info(
        val partitions: List<Partition>,
        val blobStart: Long,
        val payloadSize: Long,
    )

    data class ExtractResult(
        val outputFile: File,
        val bytesWritten: Long,
        val appliedOps: Int,
        val gaps: Int,
        val unsupportedCounts: Map<String, Int>,
    )

    const val TYPE_RAW = 0
    const val TYPE_XZ = 8

    private val TYPE_NAMES =
        mapOf(
            0 to "RAW",
            1 to "REPLACE_BZ",
            2 to "MOVE",
            3 to "BSDIFF",
            4 to "SOURCE_COPY",
            5 to "SOURCE_BSDIFF",
            8 to "XZ",
            9 to "PUFFDIFF",
            12 to "ZSTD",
        )

    fun typeName(type: Int): String = TYPE_NAMES[type] ?: "type_$type"

    class PayloadFormatException(message: String) : Exception(message)

    fun readInfo(file: File): Info =
        RandomAccessFile(file, "r").use { source ->
            val magic = ByteArray(4)
            source.readFully(magic)
            if (!magic.contentEquals("CrAU".toByteArray(Charsets.US_ASCII))) {
                throw PayloadFormatException("not a payload.bin file (bad CrAU magic)")
            }
            val version = source.readLong()
            if (version != 2L) {
                throw PayloadFormatException("payload version $version is not the supported v2")
            }
            val manifestSize = source.readLong()
            if (manifestSize <= 0 || manifestSize > 64L * 1024 * 1024) {
                throw PayloadFormatException("implausible manifest size $manifestSize")
            }
            val signatureSize = readUInt32(source)
            val manifest = ByteArray(manifestSize.toInt())
            source.readFully(manifest)
            val partitions =
                decodeFields(manifest)
                    .filter { it.second == WIRE_EMBEDDED }
                    .map { decodePartition(it.third as ByteArray) }
                    .filter { it.operations.isNotEmpty() }
            Info(partitions, 24L + manifestSize + signatureSize, file.length())
        }

    /** Reconstructs one partition image from its RAW and XZ operations; others are reported. */
    fun extractPartition(
        source: File,
        info: Info,
        partitionName: String,
        output: File,
    ): ExtractResult {
        val partition =
            info.partitions.firstOrNull { it.name == partitionName }
                ?: throw PayloadFormatException(
                    "no partition \"$partitionName\" — available: " + info.partitions.joinToString("، ") { it.name },
                )
        val unsupported =
            partition.operations
                .filter { it.type != TYPE_RAW && it.type != TYPE_XZ }
                .groupBy { typeName(it.type) }
                .mapValues { it.value.size }
        val applicable =
            partition.operations
                .filter { it.type == TYPE_RAW || it.type == TYPE_XZ }
                .sortedBy { it.dstOffset }
        output.parentFile?.mkdirs()
        RandomAccessFile(source, "r").use { reader ->
            RandomAccessFile(output, "rw").use { writer ->
                var applied = 0
                var written = 0L
                var lastEnd = 0L
                var gaps = 0
                for (operation in applicable) {
                    if (operation.dstOffset > lastEnd) gaps += 1
                    val data = ByteArray(operation.dataLength.toInt())
                    reader.seek(info.blobStart + operation.dataOffset)
                    reader.readFully(data)
                    val decoded =
                        when (operation.type) {
                            TYPE_RAW -> data
                            else ->
                                XZInputStream(ByteArrayInputStream(data)).use { stream ->
                                    stream.readBytes()
                                }
                        }
                    if (decoded.size.toLong() != operation.dstLength) {
                        throw PayloadFormatException(
                            "operation at ${operation.dstOffset} produced ${decoded.size} bytes but the manifest promises ${operation.dstLength}",
                        )
                    }
                    writer.seek(operation.dstOffset)
                    writer.write(decoded)
                    applied += 1
                    written += decoded.size
                    lastEnd = operation.dstOffset + decoded.size
                }
                if (lastEnd > 0) writer.setLength(lastEnd)
                if (unsupported.isNotEmpty() && applied == 0) {
                    throw PayloadFormatException(
                        "every operation uses unsupported compression: " +
                            unsupported.entries.joinToString("، ") { "${it.key} x${it.value}" },
                    )
                }
                return ExtractResult(output, written, applied, gaps, unsupported)
            }
        }
    }

    // --- generic protobuf wire-format walking (no codegen dependency) ------------------------

    private const val WIRE_VARINT = 0
    private const val WIRE_EMBEDDED = 2

    private data class Field(
        val number: Int,
        val wire: Int,
        val value: Any,
    )

    private fun decodeFields(bytes: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        var position = 0
        while (position < bytes.size) {
            val tag = readVarint(bytes, position)
            position = tag.second
            val number = (tag.first shr 3).toInt()
            val wire = (tag.first and 0x7).toInt()
            when (wire) {
                WIRE_VARINT -> {
                    val value = readVarint(bytes, position)
                    position = value.second
                    fields.add(Field(number, wire, value.first))
                }
                WIRE_EMBEDDED -> {
                    val length = readVarint(bytes, position)
                    position = length.second
                    val end = position + length.first.toInt()
                    if (end > bytes.size) throw PayloadFormatException("corrupt manifest (field runs past the end)")
                    fields.add(Field(number, wire, bytes.copyOfRange(position, end)))
                    position = end
                }
                else -> throw PayloadFormatException("corrupt manifest (unsupported wire type $wire)")
            }
        }
        return fields
    }

    /**
     * PartitionUpdate walked defensively: the first string is the partition name, and every
     * embedded message that decodes to sane install operations counts as one — real manifests
     * use stable numbers, this avoids betting the tool on one remembered field id.
     */
    private fun decodePartition(bytes: ByteArray): Partition {
        var name = ""
        val candidateOperations = ArrayList<Operation>()
        for (field in decodeFields(bytes)) {
            when (field.wire) {
                WIRE_EMBEDDED -> decodeOperation(field.third as ByteArray)?.let { candidateOperations.add(it) }
                WIRE_LENGTH_DELIMITED ->
                    if (name.isEmpty() && (field.third as ByteArray).isProbablyPartitionName()) {
                        name = String(field.third as ByteArray, Charsets.UTF_8)
                    }
            }
        }
        return Partition(name, candidateOperations)
    }

    /** Partition names are short ASCII tokens (boot, system, vendor_dlkm, …). */
    private fun ByteArray.isProbablyPartitionName(): Boolean =
        size in 1..64 &&
            all { byte ->
                val c = byte.toInt()
                c in 0x61..0x7A || c in 0x41..0x5A || c in 0x30..0x39 || c == 0x5F || c == 0x2D || c == 0x2E
            }

    /** Operation sanity bounds keep foreign embedded messages (e.g. metadata) out of the list. */
    private fun decodeOperation(bytes: ByteArray): Operation? {
        var type = -1
        var dataOffset = -1L
        var dataLength = -1L
        var dstOffset = -1L
        var dstLength = -1L
        for (field in decodeFields(bytes)) {
            if (field.wire != WIRE_VARINT) return null
            val value = field.third as Long
            when (field.number) {
                1 -> type = value.toInt()
                2 -> dataOffset = value
                3 -> dataLength = value
                6 -> dstOffset = value
                7 -> dstLength = value
            }
        }
        if (type !in 0..12) return null
        if (dataOffset < 0 || dataLength <= 0 || dataLength > 1L shl 32) return null
        if (dstOffset < 0 || dstLength <= 0 || dstLength > 8L * 1024 * 1024 * 1024) return null
        return Operation(type, dataOffset, dataLength, dstOffset, dstLength)
    }

    private fun readVarint(
        bytes: ByteArray,
        startPosition: Int,
    ): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var position = startPosition
        while (position < bytes.size) {
            val byte = bytes[position].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            position += 1
            if (byte and 0x80 == 0) return result to position
            shift += 7
            if (shift > 63) throw PayloadFormatException("corrupt manifest (varint too long)")
        }
        throw PayloadFormatException("corrupt manifest (varint runs past the end)")
    }

    private fun readUInt32(
        source: RandomAccessFile,
    ): Long {
        val value = source.readInt()
        return value.toLong() and 0xFFFFFFFFL
    }
}
