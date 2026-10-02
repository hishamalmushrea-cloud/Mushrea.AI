package com.mushrea.code.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mushrea.code.R
import com.mushrea.code.core.permission.PermissionActor
import com.mushrea.code.core.permission.PermissionDecision
import com.mushrea.code.device.bluetooth.BluetoothExecutor
import com.mushrea.code.device.call.CallAgentExecutor
import com.mushrea.code.device.network.NetworkExecutor
import com.mushrea.code.device.payload.PayloadExecutor
import com.mushrea.code.device.permission.ToolPermissionPolicy
import com.mushrea.code.device.remote.RemoteExecutor
import com.mushrea.code.device.ssh.SshExecutor
import com.mushrea.code.device.termux.TermuxExecutor
import com.mushrea.code.device.tool.DeviceAvailability
import com.mushrea.code.device.tool.DeviceToolCatalog
import com.mushrea.code.device.tool.OutcomeVerification
import com.mushrea.code.device.usb.UsbExecutor
import com.mushrea.code.device.usb.UsbSerialExecutor
import com.mushrea.code.device.usbhub.HubExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Executes device commands dropped by the agent's device MCP server into the active workspace
 * (`.mushrea-code/device-command.json`), following the same file-channel pattern as the guest
 * browser MCP.
 *
 * Every command runs through the pipeline the product spec demands (spec sections 24–26, 35, 37):
 *
 * ```text
 * read command → firewall → [confirmation] → execute → verify → write result → activity log
 * ```
 *
 * The loop lives inside the accessibility service, so device tools keep working while the user is
 * in any other app, and it consumes the emergency-stop flag between steps (spec section 36). File
 * operations are delegated to [DeviceFileAgent].
 */
class DeviceAgentBridge(
    private val context: Context,
    private val store: DeviceAgentStore,
    private val engine: MushreaCodeAccessibilityService.Engine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileAgent = DeviceFileAgent(context)
    private val navigator = DeviceNavigator(context, engine)
    private val callExecutor = CallAgentExecutor(context)
    private val statusAgent = DeviceStatusAgent(context)
    private val usbExecutor = UsbExecutor(context)
    private val hubExecutor = HubExecutor(context)
    private val remoteExecutor = RemoteExecutor(context)
    private val networkExecutor = NetworkExecutor(context)
    private val bluetoothExecutor = BluetoothExecutor(context)
    private val serialExecutor = UsbSerialExecutor(context)
    private val sshExecutor = SshExecutor(context)
    private val payloadExecutor = PayloadExecutor(context)
    private val termuxExecutor = TermuxExecutor(context)
    private val safetyPreflight = DeviceSafetyPreflight(context)
    private val availability = DeviceAvailability.onDevice(context)
    private var job: Job? = null

    @Volatile
    private var running = false

    private var lastCommandId: String? = null

    private var lastPublishedContextMillis = Long.MIN_VALUE

    /** Instant wake-ups from the file watcher; conflated so bursts collapse into one pass. */
    private val commandWakeups = Channel<Unit>(Channel.CONFLATED)

    private var commandObserver: FileObserver? = null

    /** workspace -> watched directory; re-watch only when either side moves. */
    private var watchedWorkspace: Pair<String, String>? = null

    fun start() {
        if (running) return
        running = true
        job = scope.launch { loop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        scope.cancel()
        runCatching { commandObserver?.stopWatching() }
        commandObserver = null
    }

    private suspend fun loop() {
        while (running) {
            val workspace = resolveWorkspace()
            if (workspace == null) {
                delay(POLL_INTERVAL_MILLIS)
                continue
            }
            observeWorkspace(workspace)
            // Bookkeeping only: a storage hiccup here must never kill the poll loop (or the
            // accessibility service hosting it).
            runCatching { publishContextIfChanged(workspace) }
            drainCommands(workspace)
            // inotify wakes this instantly on a written command; the timeout doubles as a
            // low-frequency safety poll in case the watcher silently misses an event.
            withTimeoutOrNull(POLL_INTERVAL_MILLIS) { commandWakeups.receive() }
        }
    }

    private fun resolveWorkspace(): String? =
        store.readActiveWorkspace()
            // Fallback: the default runtime workspace — commands originate there even when
            // no UI selection was ever published (fresh install, headless start).
            ?: File(context.filesDir, "runtime/workspace")
                .takeIf(File::isDirectory)?.absolutePath

    /**
     * Watches the command file's directory (inotify on app-private storage is reliable). Until
     * `.mushrea-code` exists the workspace root is watched for its creation; the event then
     * re-points the watcher at the real directory on the next pass.
     */
    private fun observeWorkspace(workspace: String) {
        val commandsDir = File(workspace, COMMAND_DIR_NAME)
        val watchDir = if (commandsDir.isDirectory) commandsDir else File(workspace)
        val watchedName = if (watchDir == commandsDir) COMMAND_FILE_NAME else COMMAND_DIR_NAME
        val key = workspace to watchDir.absolutePath
        if (watchedWorkspace == key) return
        watchedWorkspace = key
        runCatching {
            commandObserver?.stopWatching()
            commandObserver =
                @Suppress("DEPRECATION")
                object : FileObserver(watchDir.absolutePath, CLOSE_WRITE or MOVED_TO or CREATE) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if (path == watchedName) commandWakeups.trySend(Unit)
                    }
                }.also { it.startWatching() }
        }
    }

    private suspend fun drainCommands(workspace: String) {
        val commandFile = File(workspace, COMMAND_RELATIVE_PATH)
        val text = runCatching { commandFile.takeIf(File::isFile)?.readText() }.getOrNull() ?: return
        val command = DeviceCommandCodec.parseRequest(text)
        if (command == null) {
            runCatching { commandFile.delete() }
            return
        }
        if (command.id == lastCommandId) return
        lastCommandId = command.id
        runCatching { process(command, workspace) }
            .onFailure { writeResult(workspace, DeviceCommandCodec.failure(command.id, it.message ?: "unknown error")) }
        runCatching { commandFile.delete() }
    }

    private suspend fun process(
        command: DeviceCommand,
        workspace: String,
    ) {
        // Emergency stop: never start a new step after the user asked to stop (spec section 36).
        if (command.action != DeviceActionFirewall.ACTION_STOP && store.consumeStopRequest()) {
            log(command.action, ok = false, detail = context.getString(R.string.device_agent_stopped), params = command.params)
            writeResult(workspace, DeviceCommandCodec.failure(command.id, "Agent stopped by user"))
            return
        }

        // One gate decides: the tool table, the user's overrides, Read-Only mode and the
        // sensitive-tap escalation are composed in ToolPermissionPolicy, so nothing below this
        // point has to know any of those rules.
        val policy = ToolPermissionPolicy(store.firewallOverrides(), readOnly = store.readOnlyMode())
        val sensitiveLabel = if (command.action == DeviceActionFirewall.ACTION_TAP) describeTapTarget(command).second else null
        val decision = policy.decide(command.action, tapLabel = sensitiveLabel)

        if (decision is PermissionDecision.Deny) {
            log(command.action, ok = false, detail = decision.reason, decision = decision, params = command.params)
            writeResult(workspace, DeviceCommandCodec.failure(command.id, decision.reason))
            return
        }

        // The catalog's requirements, actually evaluated (before the confirmation prompt, so the
        // user is never asked to allow something this device cannot do at all). The reason travels
        // to the agent, which is the difference between "it failed" and "Termux is not installed".
        val unavailable = availability.blockedReason(command.action)
        if (unavailable != null) {
            log(command.action, ok = false, detail = unavailable, decision = decision, params = command.params)
            writeResult(workspace, DeviceCommandCodec.failure(command.id, unavailable))
            return
        }

        if (decision is PermissionDecision.Confirm) {
            val allowed =
                awaitConfirmation(
                    action = command.action,
                    detail =
                        sensitiveLabel ?: command.params.optString("path").ifBlank {
                            command.params.optString("app").ifBlank { command.params.optString("command") }
                        },
                )
            if (!allowed) {
                log(
                    command.action,
                    ok = false,
                    detail = "denied by user",
                    decision = PermissionDecision.Deny("the user rejected the confirmation for ${command.action}"),
                    params = command.params,
                )
                writeResult(workspace, DeviceCommandCodec.failure(command.id, "denied by user", needsConfirmation = true))
                return
            }
            log(command.action, ok = true, detail = "confirmed by user (${decision.level})", decision = decision, params = command.params)
        }

        val startedAt = System.currentTimeMillis()
        val result =
            runCatching {
                execute(command)
            }.fold(
                onSuccess = { payload -> DeviceCommandCodec.success(command.id, payload) },
                onFailure = { DeviceCommandCodec.failure(command.id, it.message ?: "action failed") },
            )
        val ok = result.optBoolean("ok")
        // Every result says what was verified and what was not: an executor that checked its own
        // effect is believed (and quoted), an executor that checked nothing is marked unverified
        // instead of being reported as a confirmed success.
        val verification =
            result.optJSONObject("result")?.let { payload ->
                val found =
                    OutcomeVerification.of(payload)
                        ?: OutcomeVerification.unverified(
                            "the executor reported success; no independent check covers this action",
                        )
                OutcomeVerification.apply(payload, found)
                found
            } ?: OutcomeVerification.failed()
        log(
            command.action,
            ok = ok,
            detail = result.optJSONObject("result")?.optString("summary").orEmpty(),
            decision = decision,
            verification = verification,
            params = command.params,
            startedAt = startedAt,
        )
        writeResult(workspace, result)
        refreshContext(command, result, ok, workspace)
    }

    /** Keeps the Context Engine fresh after every command (spec sections 9 and 22). */
    private suspend fun refreshContext(
        command: DeviceCommand,
        result: JSONObject,
        ok: Boolean,
        workspace: String,
    ) {
        runCatching {
            if (command.action == DeviceActionFirewall.ACTION_SET_TASK) {
                store.updateContextTask(command.params.optString("goal"))
            }
            if (ok) {
                when (command.action) {
                    DeviceActionFirewall.ACTION_OPEN_FILE,
                    DeviceActionFirewall.ACTION_SHARE_FILE,
                    -> store.updateContextLastFile(command.params.optString("path"))
                    DeviceActionFirewall.ACTION_MOVE_FILE,
                    DeviceActionFirewall.ACTION_COPY_FILE,
                    ->
                        store.updateContextLastFile(
                            File(command.params.optString("to"), File(command.params.optString("path")).name).path,
                        )
                    DeviceActionFirewall.ACTION_RENAME_FILE -> {
                        val original = File(command.params.optString("path"))
                        val newName = command.params.optString("new_name")
                        val parent = original.parentFile
                        if (parent != null && newName.isNotBlank()) {
                            store.updateContextLastFile(File(parent, newName).path)
                        }
                    }
                }
            }
            val (app, activity) = withContext(Dispatchers.Main) { engine.currentApp() }
            if (app.isNotBlank()) store.updateContextApp(app, activity)
            publishContextIfChanged(workspace)
        }
    }

    /** Mirrors the store's context into the workspace so device_get_context can read it directly. */
    private fun publishContextIfChanged(workspace: String) {
        val context = store.readContext()
        if (context.updatedAtMillis == lastPublishedContextMillis) return
        lastPublishedContextMillis = context.updatedAtMillis
        runCatching {
            val file = File(workspace, CONTEXT_RELATIVE_PATH)
            file.parentFile?.mkdirs()
            file.writeText(context.toJson().toString())
        }
    }

    private suspend fun execute(command: DeviceCommand): JSONObject.() -> Unit =
        when (command.action) {
            DeviceActionFirewall.ACTION_GET_CURRENT_APP -> executeCurrentApp()
            DeviceActionFirewall.ACTION_READ_SCREEN -> executeReadScreen()
            DeviceActionFirewall.ACTION_FIND_ELEMENT -> executeFindElement(command.params)
            DeviceActionFirewall.ACTION_SEARCH_AND_TYPE -> navigator.executeSearchAndType(command.params)
            DeviceActionFirewall.ACTION_SCROLL_UNTIL_FOUND -> navigator.executeScrollUntilFound(command.params)
            DeviceActionFirewall.ACTION_WAIT_FOR_ELEMENT -> navigator.executeWaitForElement(command.params)
            DeviceActionFirewall.ACTION_FIND_CONTACT,
            DeviceActionFirewall.ACTION_CALL_AGENT,
            DeviceActionFirewall.ACTION_CALL_STATE,
            DeviceActionFirewall.ACTION_CALL_STOP,
            DeviceActionFirewall.ACTION_READ_CALL_LOG,
            -> callExecutor.execute(command)
            DeviceActionFirewall.ACTION_PING -> statusAgent.executePing()
            DeviceActionFirewall.ACTION_DEVICE_STATUS -> statusAgent.executeStatus()
            DeviceActionFirewall.ACTION_CALL_SUMMARIES -> callExecutor.executeCallSummaries(command.params)
            DeviceActionFirewall.ACTION_USB_DEVICES -> usbExecutor.executeDevices()
            DeviceActionFirewall.ACTION_USB_SHELL -> usbExecutor.executeShell(command.params)
            DeviceActionFirewall.ACTION_USB_LIST -> usbExecutor.executeList(command.params)
            DeviceActionFirewall.ACTION_USB_PULL -> usbExecutor.executePull(command.params)
            DeviceActionFirewall.ACTION_USB_PUSH -> usbExecutor.executePush(command.params)
            DeviceActionFirewall.ACTION_USB_TRANSFER_MEDIA -> usbExecutor.executeTransferMedia(command.params)
            DeviceActionFirewall.ACTION_USB_SCREENSHOT -> usbExecutor.executeScreenshot()
            DeviceActionFirewall.ACTION_USB_INSTALL -> usbExecutor.executeInstall(command.params)
            DeviceActionFirewall.ACTION_USB_LOGCAT -> usbExecutor.executeLogcat(command.params)
            DeviceActionFirewall.ACTION_USB_INFO -> usbExecutor.executeInfo()
            DeviceActionFirewall.ACTION_USB_SERIAL_SEND -> serialExecutor.executeSend(command.params)
            DeviceActionFirewall.ACTION_USB_SERIAL_READ -> serialExecutor.executeRead(command.params)
            DeviceActionFirewall.ACTION_USB_TCPIP -> usbExecutor.executeTcpipEnable()
            DeviceActionFirewall.ACTION_TCP_SHELL -> usbExecutor.executeTcpShell(command.params)
            DeviceActionFirewall.ACTION_SSH_EXEC -> sshExecutor.executeExec(command.params)
            DeviceActionFirewall.ACTION_SSH_LIST -> sshExecutor.executeList(command.params)
            DeviceActionFirewall.ACTION_SSH_DOWNLOAD -> sshExecutor.executeDownload(command.params)
            DeviceActionFirewall.ACTION_SSH_UPLOAD -> sshExecutor.executeUpload(command.params)
            DeviceActionFirewall.ACTION_PAYLOAD_INFO -> payloadExecutor.executeInfo(command.params)
            DeviceActionFirewall.ACTION_PAYLOAD_EXTRACT -> payloadExecutor.executeExtract(command.params)
            DeviceActionFirewall.ACTION_FASTBOOT_GETVAR -> usbExecutor.executeFastbootGetvar()
            DeviceActionFirewall.ACTION_MIRROR_START -> usbExecutor.executeMirrorStart()
            DeviceActionFirewall.ACTION_MIRROR_STOP -> usbExecutor.executeMirrorStop()
            DeviceActionFirewall.ACTION_SCRCPY_START -> usbExecutor.executeScrcpyStart()
            DeviceActionFirewall.ACTION_SCRCPY_STOP -> usbExecutor.executeScrcpyStop()
            DeviceActionFirewall.ACTION_USB_HUB_LIST -> usbExecutor.executeHubList()
            DeviceActionFirewall.ACTION_USB_MODE -> usbExecutor.executeUsbMode()
            DeviceActionFirewall.ACTION_USB_DIAGNOSTICS -> usbExecutor.executeDiagnostics()
            DeviceActionFirewall.ACTION_FASTBOOT_GETVAR_FULL -> usbExecutor.executeFastbootGetvarFull(command.params)
            DeviceActionFirewall.ACTION_PAYLOAD_GUARD -> payloadExecutor.executeGuard(command.params)
            DeviceActionFirewall.ACTION_SAFETY_PREFLIGHT -> safetyPreflight.run(command.params)
            DeviceActionFirewall.ACTION_AUDIT_EXPORT -> executeAuditExport()
            DeviceActionFirewall.ACTION_TERMUX_STATUS -> termuxExecutor.executeStatus()
            DeviceActionFirewall.ACTION_TERMUX_RUN -> termuxExecutor.executeRun(command.params)
            DeviceActionFirewall.ACTION_TERMUX_FASTBOOT_RUN -> termuxExecutor.executeFastbootRun(command.params)
            DeviceActionFirewall.ACTION_MITOOL_WRAPPER -> termuxExecutor.executeMitool(command.params)
            DeviceActionFirewall.ACTION_MTP_LIST -> usbExecutor.executeMtpList(command.params)
            DeviceActionFirewall.ACTION_MTP_DOWNLOAD -> usbExecutor.executeMtpDownload(command.params)
            DeviceActionFirewall.ACTION_HID_READ -> hubExecutor.executeHidRead(command.params)
            DeviceActionFirewall.ACTION_STORAGE_VOLUMES -> hubExecutor.executeStorageVolumes()
            DeviceActionFirewall.ACTION_CAMERA_LIST -> hubExecutor.executeCameraList()
            DeviceActionFirewall.ACTION_NET_BROWSE -> remoteExecutor.executeNetBrowse(command.params)
            DeviceActionFirewall.ACTION_REMOTE_LIST -> remoteExecutor.executeRemoteList(command.params)
            DeviceActionFirewall.ACTION_REMOTE_DOWNLOAD -> remoteExecutor.executeRemoteDownload(command.params)
            DeviceActionFirewall.ACTION_WIFI_INFO -> networkExecutor.executeWifiInfo()
            DeviceActionFirewall.ACTION_DNS_LOOKUP -> networkExecutor.executeDnsLookup(command.params)
            DeviceActionFirewall.ACTION_NET_PING -> networkExecutor.executeNetPing(command.params)
            DeviceActionFirewall.ACTION_PORT_CHECK -> networkExecutor.executePortCheck(command.params)
            DeviceActionFirewall.ACTION_HTTP_REQUEST -> networkExecutor.executeHttpRequest(command.params)
            DeviceActionFirewall.ACTION_WEBSOCKET -> networkExecutor.executeWebSocket(command.params)
            DeviceActionFirewall.ACTION_BT_INFO -> bluetoothExecutor.executeInfo()
            DeviceActionFirewall.ACTION_BT_DEVICES -> bluetoothExecutor.executeDevices()
            DeviceActionFirewall.ACTION_BT_SCAN -> bluetoothExecutor.executeScan(command.params)
            DeviceActionFirewall.ACTION_BLE_SCAN -> bluetoothExecutor.executeBleScan(command.params)
            DeviceActionFirewall.ACTION_LIST_APPS -> executeListApps()
            DeviceActionFirewall.ACTION_OPEN_APP -> executeOpenApp(command.params)
            DeviceActionFirewall.ACTION_OPEN_URL -> executeOpenUrl(command.params)
            DeviceActionFirewall.ACTION_OPEN_FILE -> fileAgent.executeOpenFile(command.params)
            DeviceActionFirewall.ACTION_PRESS_BACK -> executeGlobal("back")
            DeviceActionFirewall.ACTION_PRESS_HOME -> executeGlobal("home")
            DeviceActionFirewall.ACTION_OPEN_RECENTS -> executeGlobal("recents")
            DeviceActionFirewall.ACTION_SCROLL -> executeScroll(command.params)
            DeviceActionFirewall.ACTION_SWIPE -> executeSwipe(command.params)
            DeviceActionFirewall.ACTION_TAP -> executeTap(command.params)
            DeviceActionFirewall.ACTION_LONG_PRESS -> executeLongPress(command.params)
            DeviceActionFirewall.ACTION_TYPE_TEXT -> executeTypeText(command.params)
            DeviceActionFirewall.ACTION_CLEAR_TEXT -> executeClearText()
            DeviceActionFirewall.ACTION_SEARCH_FILES -> fileAgent.executeSearchFiles(command.params)
            DeviceActionFirewall.ACTION_SHARE_FILE -> fileAgent.executeShareFile(command.params)
            DeviceActionFirewall.ACTION_DELETE_FILE -> fileAgent.executeDeleteFile(command.params)
            DeviceActionFirewall.ACTION_MOVE_FILE -> fileAgent.executeMoveFile(command.params)
            DeviceActionFirewall.ACTION_COPY_FILE -> fileAgent.executeCopyFile(command.params)
            DeviceActionFirewall.ACTION_RENAME_FILE -> fileAgent.executeRenameFile(command.params)
            DeviceActionFirewall.ACTION_SET_TASK -> executeSetTask(command.params)
            DeviceActionFirewall.ACTION_STOP -> {
                store.requestStop()
                (
                    {
                        put("stopped", true)
                        put("summary", "agent stop requested")
                    }
                )
            }
            else -> throw DeviceFileAgent.DeviceAgentError("unknown action: ${command.action}")
        }

    /** Writes the exportable audit document and reports where it landed. */
    private suspend fun executeAuditExport(): JSONObject.() -> Unit {
        val file = withContext(Dispatchers.IO) { DeviceAuditLog.write(context, store) }
        return {
            put("file", file.absolutePath)
            put("bytes", file.length())
            put(
                "summary",
                "audit log exported to ${file.absolutePath} (activity entries and safety switches; " +
                    "no command parameters and no unlock token)",
            )
        }
    }

    private suspend fun executeCurrentApp(): JSONObject.() -> Unit {
        val (pkg, activity) = withContext(Dispatchers.Main) { engine.currentApp() }
        if (pkg.isBlank()) {
            throw DeviceFileAgent.DeviceAgentError(
                "cannot detect the current app — enable Mushrea Code in Accessibility settings",
            )
        }
        return {
            put("package", pkg)
            activity?.let { put("activity", it) }
            put("summary", "current app: $pkg")
        }
    }

    private suspend fun executeReadScreen(): JSONObject.() -> Unit {
        val snapshot =
            withContext(Dispatchers.Main) { engine.readScreen() }
                ?: throw DeviceFileAgent.DeviceAgentError("cannot read the screen — enable Mushrea Code in Accessibility settings")
        return {
            put("package", snapshot.packageName)
            snapshot.activity?.let { put("activity", it) }
            put("screen", ScreenSnapshotFormatter.toPromptText(snapshot))
            put("summary", "read ${snapshot.elements.size} elements from ${snapshot.packageName}")
        }
    }

    private suspend fun executeFindElement(params: JSONObject): JSONObject.() -> Unit {
        val query = params.optString("query").ifBlank { throw DeviceFileAgent.DeviceAgentError("query is required") }
        val snapshot =
            withContext(Dispatchers.Main) { engine.readScreen() }
                ?: throw DeviceFileAgent.DeviceAgentError("cannot read the screen — enable Mushrea Code in Accessibility settings")
        val allHits = ScreenSnapshotFormatter.find(snapshot, query)
        val hits = allHits.take(5)
        return {
            put(
                "confidence",
                ContextConfidenceHeuristics.fromMatchCount(matchCount = allHits.size, askedWithQuery = true).name,
            )
            put(
                "elements",
                JSONArray().apply {
                    hits.forEach { el ->
                        put(
                            JSONObject()
                                .put("index", el.index)
                                .put("label", el.label)
                                .put("clickable", el.clickable)
                                .put("editable", el.editable)
                                .put("bounds", el.boundsInScreen.toString()),
                        )
                    }
                },
            )
            put("summary", if (hits.isEmpty()) "no element matching \"$query\"" else "found ${hits.size} element(s) for \"$query\"")
        }
    }

    private suspend fun executeListApps(): JSONObject.() -> Unit {
        val apps = installedApps()
        return {
            put(
                "apps",
                JSONArray().apply {
                    apps.take(60).forEach { put(JSONObject().put("label", it.label).put("package", it.packageName)) }
                },
            )
            put("summary", "${apps.size} launchable apps")
        }
    }

    private suspend fun executeOpenApp(params: JSONObject): JSONObject.() -> Unit {
        val query = params.optString("app").ifBlank { throw DeviceFileAgent.DeviceAgentError("app is required") }
        val apps = installedApps()
        val resolution = AppResolver.resolve(query, apps)
        val best =
            resolution.best ?: throw DeviceFileAgent.DeviceAgentError(
                buildString {
                    append("app not found: $query")
                    if (apps.isNotEmpty()) append(" — try device_list_apps to see what is installed")
                },
            )
        val launch =
            withContext(Dispatchers.Main) { engine.openAppIntent(best.packageName) }
                ?: throw DeviceFileAgent.DeviceAgentError("no launcher activity for ${best.packageName}")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
            .onFailure { throw DeviceFileAgent.DeviceAgentError("could not launch ${best.label}: ${it.message}") }
        // Verification (spec section 25): confirm the foreground app actually changed.
        val verified = waitForForeground(best.packageName)
        return {
            put("app", best.label)
            put("package", best.packageName)
            put("confidence", resolution.confidence.name)
            if (resolution.alternatives.isNotEmpty()) {
                put(
                    "alternatives",
                    JSONArray().apply { resolution.alternatives.forEach { put(it.label) } },
                )
            }
            put("verified", verified)
            put(
                "verification",
                if (verified) {
                    "the accessibility service reports ${best.packageName} in the foreground"
                } else {
                    "the app was launched but the accessibility service is not reporting it in the foreground"
                },
            )
            put(
                "summary",
                if (verified) {
                    "opened ${best.label} and verified it is in the foreground"
                } else {
                    "opened ${best.label} (could not verify foreground state)"
                },
            )
        }
    }

    private suspend fun executeOpenUrl(params: JSONObject): JSONObject.() -> Unit {
        val url = params.optString("url").ifBlank { throw DeviceFileAgent.DeviceAgentError("url is required") }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw DeviceFileAgent.DeviceAgentError("only http/https URLs are allowed")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { throw DeviceFileAgent.DeviceAgentError("no app can open $url: ${it.message}") }
        return { put("summary", "opened $url") }
    }

    private suspend fun executeGlobal(which: String): JSONObject.() -> Unit {
        val ok = withContext(Dispatchers.Main) { engine.global(which) }
        if (!ok) throw DeviceFileAgent.DeviceAgentError("could not perform $which — accessibility action unavailable")
        return { put("summary", "pressed $which") }
    }

    private suspend fun executeScroll(params: JSONObject): JSONObject.() -> Unit {
        val direction = params.optString("direction", "down").lowercase()
        val ok =
            withContext(Dispatchers.Main) {
                val snapshot = engine.readScreen() ?: return@withContext false
                val forward = direction == "down" || direction == "right"
                val scrollable = snapshot.elements.firstOrNull { it.scrollable }?.let { engine.locateNode(it) }
                if (scrollable != null) {
                    val action =
                        if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    scrollable.performAction(action)
                } else {
                    val metrics = context.resources.displayMetrics
                    val cx = metrics.widthPixels / 2f
                    val cy = metrics.heightPixels / 2f
                    val distance = metrics.heightPixels / 3f
                    when (direction) {
                        "up" -> engine.swipe(cx, cy + distance / 2, cx, cy - distance / 2, SCROLL_DURATION_MS)
                        "down" -> engine.swipe(cx, cy - distance / 2, cx, cy + distance / 2, SCROLL_DURATION_MS)
                        "left" -> engine.swipe(cx + distance / 2, cy, cx - distance / 2, cy, SCROLL_DURATION_MS)
                        "right" -> engine.swipe(cx - distance / 2, cy, cx + distance / 2, cy, SCROLL_DURATION_MS)
                        else -> false
                    }
                }
            }
        if (!ok) throw DeviceFileAgent.DeviceAgentError("could not scroll $direction")
        return { put("summary", "scrolled $direction") }
    }

    private suspend fun executeSwipe(params: JSONObject): JSONObject.() -> Unit {
        val from = params.optJSONObject("from")
        val to = params.optJSONObject("to")
        if (from == null || to == null) throw DeviceFileAgent.DeviceAgentError("from{x,y} and to{x,y} are required")
        val ok =
            withContext(Dispatchers.Main) {
                engine.swipe(
                    from.optDouble("x", 0.0).toFloat(),
                    from.optDouble("y", 0.0).toFloat(),
                    to.optDouble("x", 0.0).toFloat(),
                    to.optDouble("y", 0.0).toFloat(),
                    params.optLong("durationMs", 250),
                )
            }
        if (!ok) throw DeviceFileAgent.DeviceAgentError("gesture failed")
        return { put("summary", "swiped to (${to.optDouble("x")}, ${to.optDouble("y")})") }
    }

    /** Resolves the tap target before classifying it, then taps semantically with a coordinate fallback. */
    private suspend fun executeTap(params: JSONObject): JSONObject.() -> Unit {
        val snapshot =
            withContext(Dispatchers.Main) { engine.readScreen() }
                ?: throw DeviceFileAgent.DeviceAgentError("cannot read the screen — enable Mushrea Code in Accessibility settings")
        val query = params.optString("query")
        val index = params.optInt("index", -1)
        val target =
            when {
                index >= 0 -> snapshot.elements.firstOrNull { it.index == index }
                query.isNotBlank() -> ScreenSnapshotFormatter.find(snapshot, query).firstOrNull()
                else -> null
            }
        if (target == null && (query.isNotBlank() || index >= 0)) {
            throw DeviceFileAgent.DeviceAgentError("element not found: ${query.ifBlank { index.toString() }}")
        }
        val ok =
            if (target != null) {
                withContext(Dispatchers.Main) {
                    val node = engine.locateNode(target)
                    (node != null && engine.clickNode(node)) ||
                        engine.tap(target.boundsInScreen.centerX.toFloat(), target.boundsInScreen.centerY.toFloat())
                }
            } else {
                val x = params.optDouble("x", Double.NaN)
                val y = params.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) throw DeviceFileAgent.DeviceAgentError("provide query, index, or x/y")
                withContext(Dispatchers.Main) { engine.tap(x.toFloat(), y.toFloat()) }
            }
        if (!ok) throw DeviceFileAgent.DeviceAgentError("tap failed")
        return {
            target?.let { put("target", it.label) }
            put("summary", target?.let { "tapped \"${it.label}\"" } ?: "tapped (${params.optDouble("x")}, ${params.optDouble("y")})")
        }
    }

    private suspend fun executeLongPress(params: JSONObject): JSONObject.() -> Unit {
        val snapshot =
            withContext(Dispatchers.Main) { engine.readScreen() }
                ?: throw DeviceFileAgent.DeviceAgentError("cannot read the screen — enable Mushrea Code in Accessibility settings")
        val query = params.optString("query")
        val index = params.optInt("index", -1)
        val target =
            when {
                index >= 0 -> snapshot.elements.firstOrNull { it.index == index }
                query.isNotBlank() -> ScreenSnapshotFormatter.find(snapshot, query).firstOrNull()
                else -> null
            }
        if (target == null) throw DeviceFileAgent.DeviceAgentError("element not found: ${query.ifBlank { index.toString() }}")
        val ok =
            withContext(Dispatchers.Main) {
                engine.longPress(target.boundsInScreen.centerX.toFloat(), target.boundsInScreen.centerY.toFloat())
            }
        if (!ok) throw DeviceFileAgent.DeviceAgentError("long press failed")
        return { put("summary", "long-pressed \"${target.label}\"") }
    }

    private suspend fun executeTypeText(params: JSONObject): JSONObject.() -> Unit {
        val text = params.optString("text")
        val append = params.optBoolean("append", false)
        if (text.isEmpty() && !append) throw DeviceFileAgent.DeviceAgentError("text is required")
        val ok =
            withContext(Dispatchers.Main) {
                if (append) engine.appendToFocused(text) else engine.typeIntoFocused(text)
            }
        if (!ok) throw DeviceFileAgent.DeviceAgentError("no focused text field — tap the field first")
        return { put("summary", "typed ${text.length} character(s)") }
    }

    private suspend fun executeClearText(): JSONObject.() -> Unit {
        val ok = withContext(Dispatchers.Main) { engine.clearFocused() }
        if (!ok) throw DeviceFileAgent.DeviceAgentError("no focused text field to clear")
        return { put("summary", "cleared the focused field") }
    }

    /** Records the agent's stated goal so context survives app switches (spec section 22). */
    private suspend fun executeSetTask(params: JSONObject): JSONObject.() -> Unit {
        val goal = params.optString("goal").ifBlank { throw DeviceFileAgent.DeviceAgentError("goal is required") }
        store.updateContextTask(goal)
        return { put("summary", "task recorded: $goal") }
    }

    // region Confirmation + verification helpers

    /**
     * Describes the tap target for the firewall: (queryUsed, labelOrNull). Reading the label first
     * is what lets the firewall escalate taps on sensitive controls (spec section 35).
     */
    private suspend fun describeTapTarget(command: DeviceCommand): Pair<String, String?> {
        val query = command.params.optString("query")
        val index = command.params.optInt("index", -1)
        if (query.isBlank() && index < 0) return "" to null
        val snapshot = withContext(Dispatchers.Main) { engine.readScreen() } ?: return query to null
        val target =
            when {
                index >= 0 -> snapshot.elements.firstOrNull { it.index == index }
                else -> ScreenSnapshotFormatter.find(snapshot, query).firstOrNull()
            }
        return query to target?.label
    }

    private suspend fun awaitConfirmation(
        action: String,
        detail: String,
    ): Boolean {
        val requestId = store.requestConfirmation(action, detail)
        postConfirmationNotification(requestId, action, detail)
        val deadline = System.currentTimeMillis() + CONFIRMATION_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (store.consumeStopRequest()) {
                NotificationManagerCompat.from(context).cancel(requestId.hashCode())
                return false
            }
            when (store.consumeConfirmationDecision(requestId)) {
                "allow" -> {
                    NotificationManagerCompat.from(context).cancel(requestId.hashCode())
                    return true
                }
                "deny" -> {
                    NotificationManagerCompat.from(context).cancel(requestId.hashCode())
                    return false
                }
            }
            delay(200)
        }
        NotificationManagerCompat.from(context).cancel(requestId.hashCode())
        return false
    }

    private fun postConfirmationNotification(
        requestId: String,
        action: String,
        detail: String,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val channel =
            NotificationChannel(
                CHANNEL_CONFIRMATIONS,
                context.getString(R.string.device_agent_channel_confirmations),
                NotificationManager.IMPORTANCE_HIGH,
            )
        manager.createNotificationChannel(channel)

        fun actionIntent(decision: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                (requestId + decision).hashCode(),
                Intent(context, DeviceConfirmReceiver::class.java)
                    .putExtra(DeviceConfirmReceiver.EXTRA_REQUEST_ID, requestId)
                    .putExtra(DeviceConfirmReceiver.EXTRA_DECISION, decision),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val body =
            buildString {
                append(action)
                if (detail.isNotBlank()) append(": ").append(detail)
            }
        val notification =
            NotificationCompat.Builder(context, CHANNEL_CONFIRMATIONS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.device_agent_confirm_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.device_agent_confirm_allow), actionIntent("allow"))
                .addAction(0, context.getString(R.string.device_agent_confirm_deny), actionIntent("deny"))
                .build()
        runCatching { manager.notify(requestId.hashCode(), notification) }
    }

    private suspend fun installedApps(): List<AppEntry> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val result = ArrayList<AppEntry>()
            for (info in pm.getInstalledApplications(0)) {
                // A non-null launch intent is the definition of "launchable" here; package
                // visibility may hide the resolver activity but the intent itself is enough.
                pm.getLaunchIntentForPackage(info.packageName) ?: continue
                result += AppEntry(label = pm.getApplicationLabel(info)?.toString() ?: info.packageName, packageName = info.packageName)
            }
            result.sortedBy { it.label.lowercase() }
        }

    private suspend fun waitForForeground(packageName: String): Boolean {
        if (!MushreaCodeAccessibilityService.isRunning()) return false
        val deadline = System.currentTimeMillis() + FOREGROUND_WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val (current, _) = withContext(Dispatchers.Main) { engine.currentApp() }
            if (current == packageName) return true
            delay(250)
        }
        return false
    }

    private fun writeResult(
        workspace: String,
        result: JSONObject,
    ) {
        runCatching {
            val file = File(workspace, RESULT_RELATIVE_PATH)
            file.parentFile?.mkdirs()
            file.writeText(result.toString())
        }
    }

    /**
     * Writes one audit entry.
     *
     * Every action is written with the decision that allowed it: what it was (tool id), who asked
     * ([PermissionActor.AGENT] here - a *claimed* identity, since the command file cannot
     * authenticate its writer), the decision and its reason, and the declared risk. The parameters
     * themselves are never stored; the audit export says so in its notes.
     */
    private fun log(
        action: String,
        ok: Boolean,
        detail: String,
        decision: PermissionDecision? = null,
        actor: PermissionActor = PermissionActor.AGENT,
        verification: OutcomeVerification? = null,
        params: JSONObject? = null,
        startedAt: Long? = null,
    ) {
        val entry =
            JSONObject()
                .put("action", action)
                .put("ok", ok)
                .put("detail", detail)
                .put("actor", actor.name.lowercase())
                .put("risk", DeviceToolCatalog.riskFor(action)?.name?.lowercase() ?: "unknown")
        // What was asked, as a fingerprint rather than as parameters: the export stays safe to hand
        // over while a review can still tell two commands apart (DeviceAuditLog format 4).
        DeviceAuditLog.paramsDigest(params)?.let { entry.put("params_digest", it) }
        // Only an executed command has a window; a denied or blocked one never ran.
        if (startedAt != null) entry.put("started_at", startedAt)
        if (decision != null) {
            entry.put("decision", decision.label)
            entry.put("reason", decision.reason)
            if (decision is PermissionDecision.Confirm) entry.put("confirmation_level", decision.level.name)
        }
        // The audit trail keeps the verification, not just "ok": a session review has to be able to
        // tell "the app proved it" from "the executor said so" (DeviceAuditLog format 3).
        if (verification != null) {
            entry.put(OutcomeVerification.KEY_VERIFIED, verification.verified)
            entry.put(OutcomeVerification.KEY_DETAIL, verification.detail)
        }
        store.appendActivity(entry)
    }

    companion object {
        const val COMMAND_DIR_NAME = ".mushrea-code"
        const val COMMAND_FILE_NAME = "device-command.json"
        const val COMMAND_RELATIVE_PATH = "$COMMAND_DIR_NAME/$COMMAND_FILE_NAME"
        const val RESULT_RELATIVE_PATH = ".mushrea-code/device-result.json"
        const val CONTEXT_RELATIVE_PATH = ".mushrea-code/device-context.json"
        const val POLL_INTERVAL_MILLIS = 500L
        const val CONFIRMATION_TIMEOUT_MILLIS = 120_000L
        const val FOREGROUND_WAIT_MILLIS = 5_000L
        const val SCROLL_DURATION_MS = 220L
        const val CHANNEL_CONFIRMATIONS = "device_agent_confirmations"
    }
}
