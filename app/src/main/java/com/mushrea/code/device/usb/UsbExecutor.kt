package com.mushrea.code.device.usb

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.mushrea.code.device.mirror.MirrorActivity
import com.mushrea.code.device.mirror.RemoteControlActivity
import com.mushrea.code.device.mirror.ScrcpySession
import com.mushrea.code.device.mirror.ScreenMirrorSession
import com.mushrea.code.device.usbhub.MtpAgent
import com.mushrea.code.device.usbhub.UsbHub
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

    /** Captures the other phone's screen into our Download folder (privacy-sensitive read). */
    suspend fun executeScreenshot(): JSONObject.() -> Unit {
        val bytes = agent.screenshot()
        val isPng = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        if (!isPng) throw AdbException("screencap returned no PNG image (the other phone may not support the exec service)")
        val file = File(usbRoot(), "screenshot-" + System.currentTimeMillis() + ".png")
        file.writeBytes(bytes)
        return {
            put("path", file.absolutePath)
            put("bytes", bytes.size)
            put("summary", "saved the other phone's screenshot to " + file.absolutePath)
        }
    }

    /** Installs a local APK on the other phone (push + pm install). */
    suspend fun executeInstall(params: JSONObject): JSONObject.() -> Unit {
        val localPath = params.optString("local_path").ifBlank { throw AdbException("local_path is required") }
        val file = File(localPath)
        if (!file.isFile) throw AdbException("no local file at $localPath")
        if (!file.name.endsWith(".apk", ignoreCase = true)) throw AdbException("only .apk files can be installed")
        val output = agent.install(file)
        if (!output.contains("Success", ignoreCase = true)) throw AdbException("install failed: " + output.take(300))
        return {
            put("package_file", file.absolutePath)
            put("summary", "installed ${file.name} on the other phone (pm reported Success)")
        }
    }

    /** Recent log lines from the other phone (capped). */
    suspend fun executeLogcat(params: JSONObject): JSONObject.() -> Unit {
        val lines = params.optInt("lines", 200).coerceIn(20, 500)
        val output = agent.logcat(lines)
        return {
            put("lines", lines)
            put("output", if (output.length > 8000) output.takeLast(8000) else output)
            put("summary", "pulled the last $lines log lines from the other phone")
        }
    }

    /** Identity and status facts about the other phone (AUTO read). */
    suspend fun executeInfo(): JSONObject.() -> Unit {
        val info = agent.deviceInfo()
        return {
            put("model", info.optString("model"))
            put("brand", info.optString("brand"))
            put("android_version", info.optString("android_version"))
            put("sdk", info.optString("sdk"))
            put("battery", info.optString("battery_raw"))
            put("storage", info.optString("storage_raw"))
            put(
                "summary",
                "the other phone: " + info.optString("brand") + " " + info.optString("model") +
                    ", Android " + info.optString("android_version"),
            )
        }
    }

    /** Starts the live view-only mirror of the other phone's screen and opens its display. */
    fun executeMirrorStart(): JSONObject.() -> Unit {
        if (agent.adbDevices().isEmpty()) {
            throw AdbException("no ADB phone attached — connect one with an OTG cable and enable USB debugging on it")
        }
        ScreenMirrorSession.start(context, agent)
        var displayOpened = true
        try {
            context.startActivity(
                Intent(context, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            displayOpened = false
        }
        return {
            put("view_only", true)
            put("take_limit_seconds", 170)
            put("display_opened", displayOpened)
            put(
                "summary",
                if (displayOpened) {
                    "live view-only mirror started — its screen is open; the system caps each take near 3 minutes and it restarts itself"
                } else {
                    "live view-only mirror started but its screen could not open in the background — open Mushrea Code to see it"
                },
            )
        }
    }

    /** One classified entry per attached USB device - the hub's single discovery surface. */
    fun executeHubList(): JSONObject.() -> Unit {
        val manager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val devices = manager.deviceList.values.toList()
        val array = JSONArray()
        devices.forEach { device ->
            val interfaces =
                (0 until device.interfaceCount).map { index ->
                    val iface = device.getInterface(index)
                    UsbHub.InterfaceTriple(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol)
                }
            val verdict = UsbHub.classify(device.vendorId, device.productId, interfaces, device.productName)
            array.put(
                JSONObject()
                    .put("device_id", device.deviceId)
                    .put("name", device.productName ?: device.deviceName)
                    .put("vid", "0x%04x".format(device.vendorId))
                    .put("pid", "0x%04x".format(device.productId))
                    .put("kind", verdict.kind.name)
                    .put("driver", verdict.driver)
                    .put("has_permission", agent.hasPermission(device)),
            )
        }
        return {
            put("devices", array)
            put(
                "summary",
                if (devices.isEmpty()) {
                    "no USB device attached — plug one in with an OTG cable"
                } else {
                    devices.size.toString() + " USB device(s): " +
                        devices.joinToString(", ") { device ->
                            val interfaces =
                                (0 until device.interfaceCount).map { index ->
                                    val iface = device.getInterface(index)
                                    UsbHub.InterfaceTriple(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol)
                                }
                            UsbHub.classify(device.vendorId, device.productId, interfaces, device.productName).kind.name
                        }
                },
            )
        }
    }

    /** Lists MTP/PTP volumes, or the children of one folder in its object tree. */
    suspend fun executeMtpList(params: JSONObject): JSONObject.() -> Unit {
        val mtpAgent = MtpAgent(context)
        val deviceId = if (params.has("device_id") && !params.isNull("device_id")) params.getInt("device_id") else null
        val storageId = if (params.has("storage_id") && !params.isNull("storage_id")) params.getInt("storage_id") else null
        val parent = if (params.has("parent") && !params.isNull("parent")) params.getInt("parent") else 0
        val result =
            mtpAgent.withMtp(deviceId) { mtp ->
                if (storageId == null && parent == 0) {
                    val volumes = mtpAgent.storages(mtp)
                    JSONObject()
                        .put(
                            "volumes",
                            JSONArray(
                                volumes.map { volume ->
                                    JSONObject()
                                        .put("storage_id", volume.storageId)
                                        .put("description", volume.description ?: JSONObject.NULL)
                                        .put("max_capacity_bytes", volume.maxCapacityBytes)
                                },
                            ),
                        )
                        .put(
                            "summary",
                            (if (volumes.isEmpty()) "no storage volumes reported" else volumes.size.toString() + " storage volume(s)") +
                                " — pass storage_id and list the folder tree",
                        )
                } else {
                    val id =
                        storageId ?: mtpAgent.storages(mtp).firstOrNull()?.storageId
                            ?: throw AdbException("the device reports no storage volume")
                    val entries = mtpAgent.listChildren(mtp, id, parent)
                    JSONObject()
                        .put("storage_id", id)
                        .put("parent", parent)
                        .put(
                            "entries",
                            JSONArray(
                                entries.take(500).map { entry ->
                                    JSONObject()
                                        .put("handle", entry.handle)
                                        .put("name", entry.name)
                                        .put("is_folder", entry.isFolder)
                                        .put("bytes", entry.sizeBytes)
                                        .put("format", entry.formatCode)
                                },
                            ),
                        )
                        .put("summary", entries.size.toString() + " item(s) in this folder — mtp_download copies one by handle")
                }
            }
        return { result.keys().forEach { key -> put(key, result.opt(key)) } }
    }

    /** Downloads one file from an MTP/PTP device into Download/Mushrea-mtp. */
    suspend fun executeMtpDownload(params: JSONObject): JSONObject.() -> Unit {
        val mtpAgent = MtpAgent(context)
        val deviceId = if (params.has("device_id") && !params.isNull("device_id")) params.getInt("device_id") else null
        val handle = if (params.has("handle") && !params.isNull("handle")) params.getInt("handle") else -1
        if (handle <= 0) throw AdbException("handle is required (from mtp_list)")
        val requestedName = params.optString("name").ifBlank { "mtp-object-$handle" }
        val safeName = requestedName.replace('/', '_').replace('\\', '_').ifBlank { "mtp-object-$handle" }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mushrea-mtp")
        if (!dir.exists()) dir.mkdirs()
        val destination = File(dir, safeName)
        val bytes =
            mtpAgent.withMtp(deviceId) { mtp ->
                destination.outputStream().use { output -> mtpAgent.download(mtp, handle, output) }
                destination.length()
            }
        return {
            put("path", destination.absolutePath)
            put("bytes", bytes)
            put("summary", "copied $safeName from the other device to " + destination.absolutePath)
        }
    }

    /** Starts the full scrcpy session: live screen plus real touch/key control. */
    suspend fun executeScrcpyStart(): JSONObject.() -> Unit {
        if (agent.adbDevices().isEmpty()) {
            throw AdbException("no ADB phone attached — connect one with an OTG cable and enable USB debugging on it")
        }
        val serverBytes = context.assets.open("scrcpy/scrcpy-server-4.0").use { it.readBytes() }
        ScrcpySession.start(context, agent, serverBytes)
        var displayOpened = true
        try {
            context.startActivity(
                Intent(context, RemoteControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            displayOpened = false
        }
        return {
            put("view_only", false)
            put("server_version", "4.0")
            put("display_opened", displayOpened)
            put(
                "summary",
                if (displayOpened) {
                    "scrcpy control session started — its screen is open: touches, scrolls and the control bar act on the other phone"
                } else {
                    "scrcpy control session started but its screen could not open in the background — open Mushrea Code to use it"
                },
            )
        }
    }

    /** Stops the scrcpy session. */
    fun executeScrcpyStop(): JSONObject.() -> Unit {
        val wasActive = ScrcpySession.isActiveSession
        ScrcpySession.stop()
        return {
            put("stopped", wasActive)
            put("summary", if (wasActive) "scrcpy session stopped" else "no scrcpy session was running")
        }
    }

    /** Stops the live mirror session. */
    fun executeMirrorStop(): JSONObject.() -> Unit {
        val wasActive = ScreenMirrorSession.isActiveSession
        ScreenMirrorSession.stop()
        return {
            put("stopped", wasActive)
            put("summary", if (wasActive) "live mirror stopped" else "no live mirror was running")
        }
    }

    /** Read-only fastboot identity: common getvar values from a phone in bootloader mode. */
    suspend fun executeFastbootGetvar(): JSONObject.() -> Unit {
        val agent = FastbootAgent(context)
        val device =
            agent.devices().firstOrNull()
                ?: throw AdbException("no phone in fastboot/bootloader mode — power + volume-down usually boots it")
        val wanted =
            listOf(
                "product",
                "serialno",
                "version-bootloader",
                "current-slot",
                "unlocked",
                "secure",
                "battery-soc-ok",
            )
        val vars = org.json.JSONObject()
        val failed = org.json.JSONArray()
        wanted.forEach { variable ->
            try {
                val (value, reason) = agent.getvar(device, variable)
                if (value != null) vars.put(variable, value) else failed.put("$variable: $reason")
            } catch (error: Exception) {
                failed.put("$variable: ${error.message}")
            }
        }
        return {
            put("device", device.deviceName)
            put("vars", vars)
            if (failed.length() > 0) put("failed", failed)
            put(
                "summary",
                "fastboot ${device.deviceName}: " +
                    (if (vars.length() > 0) vars.toString().take(160) else "no variables answered"),
            )
        }
    }

    /** Enables wireless debugging on the attached phone and reports its address. */
    suspend fun executeTcpipEnable(): JSONObject.() -> Unit {
        val result = agent.enableTcpip()
        val address = result.optString("address")
        return {
            put("address", address)
            put("output", result.optString("output"))
            put(
                "summary",
                if (address.isNotBlank()) {
                    "wireless debugging enabled — the phone is reachable at $address:5555; unplug the cable and use tcp_shell"
                } else {
                    "wireless debugging enabled — read the phone's address from its Wi-Fi settings, then use tcp_shell"
                },
            )
        }
    }

    /** Shell over Wi-Fi to a phone whose adbd listens (run usb_tcpip_enable while cabled first). */
    suspend fun executeTcpShell(params: JSONObject): JSONObject.() -> Unit {
        val host = params.optString("host").ifBlank { throw AdbException("host is required") }
        val port = params.optInt("port", 5555).coerceIn(1024, 65535)
        val command = params.optString("command").trim().ifBlank { throw AdbException("command is required") }
        val output = agent.tcpShell(host, port, command)
        val firstLine = output.lineSequence().firstOrNull()?.take(120).orEmpty().ifEmpty { "(no output)" }
        return {
            put("host", host)
            put("port", port)
            put("output", if (output.length > 8000) output.takeLast(8000) else output)
            put("summary", "ran on $host:$port — first line: $firstLine")
        }
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
