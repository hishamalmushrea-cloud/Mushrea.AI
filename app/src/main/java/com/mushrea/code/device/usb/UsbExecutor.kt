package com.mushrea.code.device.usb

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The agent surface for the other-phone-over-USB feature: list attached ADB phones and browse,
 * pull and push their files (adb sync), run confirmed shell commands, and the "moved to a new
 * phone" media transfer. Pulls land in this phone's Download/mushrea-usb folder; every failure
 * is reported honestly instead of claiming a transfer that did not happen.
 */
class UsbExecutor(private val context: Context) {
    private val agent by lazy { UsbDeviceAgent(context) }

    fun executeDevices(): JSONObject.() -> Unit {
        val devices = agent.adbDevices()
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject()
                    .put("name", device.deviceName)
                    .put("product", device.productName ?: JSONObject.NULL)
                    .put("vendor_id", device.vendorId)
                    .put("product_id", device.productId)
                    .put("has_permission", agent.hasPermission(device)),
            )
        }
        return {
            put("devices", array)
            put(
                "summary",
                if (devices.isEmpty()) {
                    "no ADB phone attached — connect one with an OTG cable and enable USB debugging on it"
                } else {
                    "${devices.size} ADB phone(s) attached"
                },
            )
        }
    }

    suspend fun executeShell(params: JSONObject): JSONObject.() -> Unit {
        val command = params.optString("command").trim()
        if (command.isEmpty()) throw AdbException("command is required")
        val output = agent.shell(command)
        val firstLine = output.lineSequence().firstOrNull()?.take(120).orEmpty().ifEmpty { "(no output)" }
        return {
            put("command", command)
            put("output", if (output.length > 8000) output.takeLast(8000) else output)
            put("summary", "ran on the other phone — first line: $firstLine")
        }
    }

    /** Browses one directory on the other phone (AUTO read). */
    suspend fun executeList(params: JSONObject): JSONObject.() -> Unit {
        val path = params.optString("remote_path").ifBlank { "/sdcard" }
        val entries = agent.listRemote(path)
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("name", entry.path)
                    .put("directory", entry.isDirectory)
                    .put("size", entry.size),
            )
        }
        return {
            put("path", path)
            put("entries", array)
            put("summary", "${entries.size} item(s) in $path on the other phone")
        }
    }

    /** Pulls a file or a whole folder from the other phone (CONFIRM). */
    suspend fun executePull(params: JSONObject): JSONObject.() -> Unit {
        val remotePath = params.optString("remote_path").ifBlank { throw AdbException("remote_path is required") }
        val cleanRemote = remotePath.trimEnd('/')
        val name = cleanRemote.substringAfterLast('/').ifBlank { "pulled" }
        val localDir = File(usbRoot(), "pull-" + System.currentTimeMillis() + "-" + name)
        val stats = PullStats()
        agent.withSync { sync ->
            val probe = sync.stat(cleanRemote)
            if (probe == null) throw AdbException("$cleanRemote not found on the other phone")
            if (probe.isDirectory) {
                downloadTree(sync, cleanRemote, localDir, stats, DEFAULT_BUDGET_BYTES)
            } else {
                localDir.mkdirs()
                FileOutputStream(File(localDir, name)).use { output -> sync.pull(cleanRemote, output) }
                stats.files += 1
                stats.bytes += probe.size
            }
        }
        return pullResult(localDir, stats)
    }

    /** Pushes one local file to the other phone (CONFIRM). */
    suspend fun executePush(params: JSONObject): JSONObject.() -> Unit {
        val localPath = params.optString("local_path").ifBlank { throw AdbException("local_path is required") }
        val file = File(localPath)
        if (!file.isFile) throw AdbException("no local file at $localPath")
        val remoteDir = params.optString("remote_dir").ifBlank { "/sdcard/Download/" }
        val remotePath = remoteDir.trimEnd('/') + "/" + file.name
        agent.withSync { sync ->
            file.inputStream().use { input -> sync.push(remotePath, input) }
        }
        return {
            put("remote_path", remotePath)
            put("bytes", file.length())
            put("summary", "pushed ${file.name} (${file.length()} bytes) to $remotePath on the other phone")
        }
    }

    /** The "moved to a new phone" helper: bulk-copy photos and videos from the old phone. */
    suspend fun executeTransferMedia(params: JSONObject): JSONObject.() -> Unit {
        val budgetBytes = params.optInt("max_megabytes", 200).coerceIn(1, 2000).toLong() * 1024L * 1024L
        val sources = listOf("/sdcard/DCIM", "/sdcard/Pictures")
        val localRoot = File(usbRoot(), "media-" + System.currentTimeMillis())
        val stats = PullStats()
        agent.withSync { sync ->
            sources.forEach { source ->
                runCatching { downloadTree(sync, source, File(localRoot, source.substringAfterLast('/')), stats, budgetBytes) }
                    .onFailure { stats.failures += "$source: ${it.message}" }
            }
        }
        return pullResult(localRoot, stats)
    }

    private suspend fun downloadTree(
        sync: AdbSync,
        remotePath: String,
        localDir: File,
        stats: PullStats,
        budgetBytes: Long,
    ) {
        localDir.mkdirs()
        sync.list(remotePath).forEach { entry ->
            if (entry.path.startsWith(".")) return@forEach
            val childRemote = remotePath.trimEnd('/') + "/" + entry.path
            val childLocal = File(localDir, entry.path)
            if (entry.isDirectory) {
                downloadTree(sync, childRemote, childLocal, stats, budgetBytes)
            } else if (stats.bytes + entry.size <= budgetBytes) {
                runCatching {
                    FileOutputStream(childLocal).use { output -> sync.pull(childRemote, output) }
                    stats.files += 1
                    stats.bytes += entry.size
                }.onFailure { stats.failures += "$childRemote: ${it.message}" }
            } else {
                stats.failures += "$childRemote: skipped (size budget reached)"
            }
        }
    }

    private fun pullResult(
        localDir: File,
        stats: PullStats,
    ): JSONObject.() -> Unit =
        {
            put("local_dir", localDir.absolutePath)
            put("files", stats.files)
            put("bytes", stats.bytes)
            if (stats.failures.isNotEmpty()) put("failures", JSONArray(stats.failures))
            put(
                "summary",
                "transferred ${stats.files} file(s) (${stats.bytes / 1024} KiB) into ${localDir.absolutePath}" +
                    if (stats.failures.isEmpty()) "" else "; ${stats.failures.size} note(s) listed in failures",
            )
        }

    private fun usbRoot(): File {
        val root = File(Environment.getExternalStorageDirectory(), "Download/mushrea-usb")
        if (!root.isDirectory && !root.mkdirs()) {
            throw AdbException("cannot create ${root.absolutePath} — grant Mushrea Code all-files access")
        }
        return root
    }

    private class PullStats {
        var files = 0
        var bytes = 0L
        val failures = mutableListOf<String>()
    }

    private companion object {
        const val DEFAULT_BUDGET_BYTES = 200L * 1024 * 1024
    }
}
