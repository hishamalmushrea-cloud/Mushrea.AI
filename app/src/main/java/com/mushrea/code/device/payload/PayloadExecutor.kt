package com.mushrea.code.device.payload

import android.content.Context
import android.os.Environment
import com.mushrea.code.device.usb.AdbException
import com.mushrea.code.device.usb.FastbootAgent
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

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
     * The pre-flash guard: does this ROM archive belong to the device it is meant for?
     *
     * `device_product` may be passed explicitly; when it is missing the guard asks an attached
     * bootloader (`fastboot getvar product`) so the comparison uses the real device rather than a
     * name somebody typed. The result is a checklist plus a verdict — never a bare "safe".
     */
    suspend fun executeGuard(params: JSONObject): JSONObject.() -> Unit {
        val path = params.optString("file_path").ifBlank { throw AdbException("file_path is required") }
        val file = File(path)
        if (!file.isFile) throw AdbException("no file at $path")
        var deviceProduct = params.optString("device_product").trim().takeIf { it.isNotBlank() }
        var productSource = if (deviceProduct != null) "device_product parameter" else null
        if (deviceProduct == null) {
            val agent = FastbootAgent(context)
            val attached = runCatching { agent.devices().firstOrNull() }.getOrNull()
            if (attached != null && agent.ensurePermission(attached)) {
                val (value, _) = agent.getvar(attached, "product")
                deviceProduct = value?.trim()?.takeIf { it.isNotBlank() }
                productSource = if (deviceProduct != null) "fastboot getvar product" else null
            }
        }
        val result = PayloadGuard().inspect(file, deviceProduct)
        val checks = org.json.JSONArray()
        result.checks.forEach { check ->
            checks.put(JSONObject().put("check", check.name).put("ok", check.ok).put("detail", check.detail))
        }
        return {
            put("file", result.path)
            put("bytes", result.sizeBytes)
            put("kind", result.kind)
            put("integrity", result.integrity)
            put("rom_product", result.romProduct ?: JSONObject.NULL)
            put("rom_product_source", result.romProductSource ?: JSONObject.NULL)
            put("build_id", result.buildId ?: JSONObject.NULL)
            put("region", result.region ?: JSONObject.NULL)
            put("region_code", result.regionCode ?: JSONObject.NULL)
            put("device_product", result.deviceProduct ?: JSONObject.NULL)
            put("device_product_source", productSource ?: JSONObject.NULL)
            put("verdict", result.verdict)
            put("blocking", result.blocking)
            put("checks", checks)
            put("notes", org.json.JSONArray(result.notes))
            put("summary", guardSummary(result, deviceProduct))
        }
    }

    private fun guardSummary(
        result: PayloadGuard.Result,
        deviceProduct: String?,
    ): String =
        when (result.verdict) {
            "match" ->
                "the archive declares \"${result.romProduct}\" and the device is \"$deviceProduct\" — " +
                    "codename matches; integrity: ${result.integrity}"
            "mismatch" ->
                "REFUSED: the archive is for \"${result.romProduct}\" but the device is \"$deviceProduct\" — " +
                    "flashing this file would target the wrong hardware"
            else ->
                "codename could not be confirmed (" +
                    (result.romProduct?.let { "archive says \"$it\"" } ?: "no codename in the archive") +
                    ", device " + (deviceProduct?.let { "\"$it\"" } ?: "unknown") +
                    ") — integrity: ${result.integrity}"
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
            val entry =
                archive.entries().asSequence().firstOrNull { it.name.endsWith("payload.bin") }
                    ?: throw AdbException("the zip contains no payload.bin")
            archive.getInputStream(entry).use { input ->
                spilled.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return spilled
    }
}
