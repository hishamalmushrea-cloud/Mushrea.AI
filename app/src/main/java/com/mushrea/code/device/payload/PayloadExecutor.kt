package com.mushrea.code.device.payload

import android.content.Context
import android.os.Environment
import com.mushrea.code.device.usb.AdbException
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * The agent surface for OTA analysis: read a payload.bin (or an OTA zip containing one), list
 * what it updates, and reconstruct partition images into Download/Mushrea-payload. Pure local
 * file work — this class never flashes anything and never talks to a bootloader.
 */
class PayloadExecutor(
    private val context: Context,
) {
    fun executeInfo(params: JSONObject): JSONObject.() -> Unit {
        val source = resolvePayload(params)
        val info = PayloadArchive.readInfo(source)
        val partitions = org.json.JSONArray()
        info.partitions.forEach { partition ->
            val byType = partition.operations.groupBy { PayloadArchive.typeName(it.type) }
            partitions.put(
                JSONObject()
                    .put("name", partition.name)
                    .put("operations", partition.operations.size)
                    .put(
                        "bytes",
                        partition.operations.sumOf { it.dstLength },
                    )
                    .put("compression", byType.keys.joinToString("+")),
            )
        }
        return {
            put("payload", source.absolutePath)
            put("partitions", partitions)
            put(
                "summary",
                "${info.partitions.size} partition(s) in the payload: " +
                    info.partitions.joinToString("، ") { "${it.name}(${it.operations.size} ops)" },
            )
        }
    }

    fun executeExtract(params: JSONObject): JSONObject.() -> Unit {
        val source = resolvePayload(params)
        val partitionName = params.optString("partition").ifBlank { throw AdbException("partition is required") }
        val info = PayloadArchive.readInfo(source)
        val output =
            File(
                File(Environment.getExternalStorageDirectory(), "Download/Mushrea-payload"),
                "$partitionName.img",
            )
        val result = PayloadArchive.extractPartition(source, info, partitionName, output)
        return {
            put("output", result.outputFile.absolutePath)
            put("bytes", result.bytesWritten)
            put("applied_operations", result.appliedOps)
            put("gaps", result.gaps)
            if (result.unsupportedCounts.isNotEmpty()) {
                put(
                    "unsupported",
                    JSONObject().apply { result.unsupportedCounts.forEach { (type, count) -> put(type, count) } },
                )
            }
            put(
                "summary",
                "${result.outputFile.name}: ${result.bytesWritten / (1024 * 1024)} MiB from ${result.appliedOps} operation(s)" +
                    (if (result.gaps > 0) ", ${result.gaps} gap(s) left as zeros" else "") +
                    (if (result.unsupportedCounts.isNotEmpty()) ", unsupported ops listed" else ""),
            )
        }
    }

    /**
     * Accepts a payload.bin directly or an OTA zip: the zip's payload.bin entry is spilled into
     * app-private cache first (the archive format needs random access to the blob).
     */
    private fun resolvePayload(params: JSONObject): File {
        val path = params.optString("file_path").ifBlank { throw AdbException("file_path is required") }
        val file = File(path)
        if (!file.isFile) throw AdbException("no file at $path")
        if (!path.endsWith(".zip", ignoreCase = true)) return file
        val cacheDir = File(context.filesDir, "payload-cache").apply { mkdirs() }
        val spilled = File(cacheDir, "payload-${System.currentTimeMillis()}.bin")
        ZipFile(file).use { archive ->
            val entry = archive.entries().asSequence().firstOrNull { it.name.endsWith("payload.bin") }
                ?: throw AdbException("the zip contains no payload.bin")
            archive.getInputStream(entry).use { input ->
                spilled.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return spilled
    }
}
