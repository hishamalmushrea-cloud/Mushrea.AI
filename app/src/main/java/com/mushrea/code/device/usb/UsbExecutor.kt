package com.mushrea.code.device.usb

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Environment
import com.mushrea.code.device.XiaomiUnlock
import com.mushrea.code.device.mirror.MirrorActivity
import com.mushrea.code.device.mirror.RemoteControlActivity
import com.mushrea.code.device.mirror.ScrcpySession
import com.mushrea.code.device.mirror.ScreenMirrorSession
import com.mushrea.code.device.tool.OutcomeVerification
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
        // Non-null means the phone reported this as a single file: its size is then the yardstick
        // the local copy has to match, including a legitimate zero-byte file (which is why this is
        // a flag and not a zero-valued sentinel).
        var singleFile: Pair<String, Long>? = null
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
                singleFile = name to probe.size
            }
        }
        val pulled = singleFile
        if (pulled != null) {
            val (fileName, expected) = pulled
            val written = File(localDir, fileName).length()
            if (written != expected) {
                throw AdbException("pulled $cleanRemote but the local copy is $written of $expected bytes")
            }
        }
        val verification =
            if (pulled != null) {
                OutcomeVerification.passed("the local file has the phone's own ${pulled.second} bytes")
            } else {
                batchVerification(stats)
            }
        return pullResult(localDir, stats, verification)
    }

    /** Pushes one local file to the other phone (CONFIRM). */
    suspend fun executePush(params: JSONObject): JSONObject.() -> Unit {
        val localPath = params.optString("local_path").ifBlank { throw AdbException("local_path is required") }
        val file = File(localPath)
        if (!file.isFile) throw AdbException("no local file at $localPath")
        val remoteDir = params.optString("remote_dir").ifBlank { "/sdcard/Download/" }
        val remotePath = remoteDir.trimEnd('/') + "/" + file.name
        val remoteBytes =
            agent.withSync { sync ->
                file.inputStream().use { input -> sync.push(remotePath, input) }
                sync.stat(remotePath)?.size ?: -1L
            }
        if (remoteBytes != file.length()) {
            throw AdbException("pushed ${file.name} but the phone has $remoteBytes of ${file.length()} bytes")
        }
        return {
            put("remote_path", remotePath)
            put("bytes", file.length())
            put("summary", "pushed ${file.name} (${file.length()} bytes) to $remotePath on the other phone")
            put("verified", true)
            put("verification", "the phone reports the same ${file.length()} bytes that were sent")
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
        return pullResult(localRoot, stats, batchVerification(stats))
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

    /**
     * Every attached USB device with its classified kind — the "what is plugged in and what mode
     * is it in" view the diagnostics page and the agent both need. [UsbHub] stays the single
     * classifier, so this adds location/permission facts rather than a second opinion.
     */
    fun usbDevicesSnapshot(): List<JSONObject> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.map { device ->
            val interfaces =
                (0 until device.interfaceCount).map { index ->
                    val iface = device.getInterface(index)
                    UsbHub.InterfaceTriple(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol)
                }
            val verdict = UsbHub.classify(device.vendorId, device.productId, interfaces, device.productName)
            JSONObject()
                .put("device_id", device.deviceId)
                .put("name", device.productName ?: device.deviceName)
                .put("vid", formatId(device.vendorId))
                .put("pid", formatId(device.productId))
                .put("kind", verdict.kind.name)
                .put("driver", verdict.driver)
                .put("hint", verdict.hint)
                .put("has_permission", agent.hasPermission(device))
        }
    }

    /** Read-only: which device is attached, and in which mode (fastboot / ADB / MTP / …). */
    fun executeUsbMode(): JSONObject.() -> Unit {
        val devices = usbDevicesSnapshot()
        val modes = devices.map { it.optString("kind") }.distinct()
        val fastbootAttached = devices.any { it.optString("kind") == UsbHub.Kind.FASTBOOT.name }
        return {
            put("devices", JSONArray(devices))
            put("modes", JSONArray(modes))
            put("fastboot_attached", fastbootAttached)
            put(
                "summary",
                if (devices.isEmpty()) {
                    "no USB device attached — plug the phone in with an OTG cable"
                } else {
                    "${devices.size} device(s) attached: ${modes.joinToString(", ")}" +
                        if (fastbootAttached) " — a bootloader is reachable, fastboot_getvar_full reads it" else ""
                },
            )
        }
    }

    /**
     * The full fastboot identity, including the unlock `token`.
     *
     * The token is device state the user is allowed to see (it is what the official Mi unlock flow
     * needs), so this is a *read* — but it is treated as a secret: it is masked unless the caller
     * explicitly asks for it, it never appears in the summary, and nothing here writes it to the
     * activity log, the audit log or any file.
     */
    suspend fun executeFastbootGetvarFull(params: JSONObject): JSONObject.() -> Unit {
        val reveal = params.optBoolean("reveal_token", false)
        val agent = FastbootAgent(context)
        val device =
            agent.devices().firstOrNull()
                ?: throw AdbException("no phone in fastboot/bootloader mode — power + volume-down usually boots it")
        if (!agent.ensurePermission(device)) {
            throw AdbException("USB permission for the bootloader was not granted")
        }
        val vars = JSONObject()
        val failed = JSONArray()
        var tokenMasked: String? = null
        var tokenAvailable = false
        FULL_GETVAR_VARIABLES.forEach { variable ->
            try {
                val (value, reason) = agent.getvar(device, variable)
                if (value == null) {
                    failed.put("$variable: $reason")
                } else if (variable == TOKEN_VARIABLE) {
                    tokenAvailable = true
                    tokenMasked = mask(value)
                    if (reveal) vars.put(variable, value)
                } else {
                    vars.put(variable, value)
                }
            } catch (error: Exception) {
                failed.put("$variable: ${error.message}")
            }
        }
        val product = vars.optString("product").ifBlank { null }
        return {
            put("device", device.deviceName)
            put("vars", vars)
            if (tokenAvailable) {
                put("token_available", true)
                put("token_masked", tokenMasked)
                put("token_revealed", reveal)
            }
            if (failed.length() > 0) put("failed", failed)
            put(
                "notes",
                JSONArray()
                    .put("the token is shown here only; it is never written to the activity log or the audit log")
                    .put("MTK's \"oem get_token\" is not used: vendor commands are refused by this build"),
            )
            put(
                "summary",
                buildString {
                    append("fastboot ").append(device.deviceName).append(": ")
                    append("product=").append(product ?: "?")
                    append(", unlocked=").append(vars.optString("unlocked").ifBlank { "?" })
                    append(", secure=").append(vars.optString("secure").ifBlank { "?" })
                    if (tokenAvailable) {
                        append(" — unlock token ").append(if (reveal) "included" else "available (masked)")
                    }
                },
            )
        }
    }

    /**
     * The single diagnostics view: what is plugged in, what mode it is in, and — when a bootloader
     * is reachable — the identity an unlock/flash decision depends on: codename, slot, lock state
     * and charge. It is read-only by construction (no write command is reachable from here), so it
     * is allowed to run in Read-Only mode and needs no confirmation.
     */
    suspend fun executeDiagnostics(): JSONObject.() -> Unit {
        val devices = usbDevicesSnapshot()
        val modes = devices.map { it.optString("kind") }.distinct()
        val fastboot = mutableMapOf<String, String>()
        val notes = mutableListOf<String>()
        val attached = runCatching { FastbootAgent(context).devices().firstOrNull() }.getOrNull()
        if (attached != null) {
            val agent = FastbootAgent(context)
            if (agent.ensurePermission(attached)) {
                DIAGNOSTIC_GETVARS.forEach { variable ->
                    val (value, _) = agent.getvar(attached, variable)
                    if (value != null) fastboot[variable] = value
                }
            } else {
                notes += "USB permission for the bootloader was not granted"
            }
        }
        val product = fastboot["product"]
        val unlocked = fastboot["unlocked"]
        when {
            product == null -> notes += "no bootloader answering getvar — connect the phone in bootloader mode to read codename and lock state"
            unlocked?.trim()?.lowercase() == "no" || unlocked?.trim() == "0" ->
                notes += XiaomiUnlock.lockedNotice(product)
            unlocked != null -> notes += "the bootloader reports unlocked=$unlocked — flashing still needs the full preflight and confirmations"
        }
        return {
            put("devices", JSONArray(devices))
            put("modes", JSONArray(modes))
            put("fastboot", JSONObject(fastboot as Map<*, *>))
            put("unlock_url", XiaomiUnlock.OFFICIAL_URL)
            put("notes", JSONArray(notes))
            put(
                "summary",
                if (devices.isEmpty()) {
                    "nothing attached — connect the phone (OTG for the other device) and try again"
                } else {
                    "devices: ${modes.joinToString(", ")}" +
                        (product?.let { ", codename: $it" } ?: "") +
                        (unlocked?.let { ", unlocked: $it" } ?: "")
                },
            )
        }
    }

    private fun mask(value: String): String =
        if (value.length <= 12) "…" else value.take(6) + "…" + value.takeLast(4)

    private fun formatId(value: Int): String = "0x%04x".format(value)

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
                    // Verify every file in the batch against the size the phone listed for it; a
                    // short transfer shows up here instead of in a byte counter nobody compares.
                    val written = childLocal.length()
                    if (written != entry.size) {
                        stats.failures += "$childRemote: $written of ${entry.size} bytes arrived"
                    }
                    stats.files += 1
                    stats.bytes += entry.size
                }.onFailure { stats.failures += "$childRemote: ${it.message}" }
            } else {
                stats.failures += "$childRemote: skipped (size budget reached)"
            }
        }
    }

    /**
     * A batch is verified per file inside [downloadTree] (each file's size against the size the
     * phone listed), and a note - a short transfer or a file skipped by the size budget - is what
     * turns that into an honest "not fully verified" rather than a silent success.
     */
    private fun batchVerification(stats: PullStats): OutcomeVerification =
        if (stats.failures.isEmpty()) {
            OutcomeVerification.passed("every one of the ${stats.files} file(s) has the size the phone listed for it")
        } else {
            OutcomeVerification.unverified("${stats.failures.size} of ${stats.files} file(s) need attention: ${stats.failures.first()}")
        }

    private fun pullResult(
        localDir: File,
        stats: PullStats,
        verification: OutcomeVerification,
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
            OutcomeVerification.apply(this, verification)
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

        /** The full fastboot identity: what the guard and the diagnostics page need, plus `token`. */
        val FULL_GETVAR_VARIABLES =
            listOf(
                "product",
                "serialno",
                "version-bootloader",
                "version-baseband",
                "current-slot",
                "slot-count",
                "max-download-size",
                "unlocked",
                "secure",
                "battery-soc-ok",
                "battery-voltage",
                "off-mode-charge",
                "token",
            )

        const val TOKEN_VARIABLE = "token"

        /** The read-only identity the diagnostics view shows; `token` is deliberately absent. */
        val DIAGNOSTIC_GETVARS =
            listOf("product", "serialno", "current-slot", "unlocked", "secure", "battery-soc-ok", "version-bootloader")
    }
}
