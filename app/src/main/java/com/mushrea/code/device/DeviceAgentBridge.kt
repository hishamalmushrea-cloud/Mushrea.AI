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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mushrea.code.R
import com.mushrea.code.device.call.CallAgentExecutor
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
import android.os.FileObserver
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
            val workspace = resolveWorkspace() ?: run { delay(POLL_INTERVAL_MILLIS); continue }
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
                object : FileObserver(watchDir, CLOSE_WRITE or MOVED_TO or CREATE) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if (path == watchedName) commandWakeups.trySend(Unit)
                    }
                }.also { it.startWatching() }
        }
    }

    private fun drainCommands(workspace: String) {
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
            log(command.action, ok = false, detail = context.getString(R.string.device_agent_stopped))
            writeResult(workspace, DeviceCommandCodec.failure(command.id, "Agent stopped by user"))
            return
        }

        if (command.action !in DeviceActionFirewall.ALL_ACTIONS) {
            writeResult(workspace, DeviceCommandCodec.failure(command.id, "unknown action: ${command.action}"))
            return
        }

        val firewall = DeviceActionFirewall(store.firewallOverrides())
        var level = firewall.levelFor(command.action)
        var sensitiveLabel: String? = null

        // Taps are classified against the element they target: "Pay now" is never an auto tap.
        if (command.action == DeviceActionFirewall.ACTION_TAP) {
            val detail = describeTapTarget(command)
            sensitiveLabel = detail.second
            level = firewall.levelForTap(sensitiveLabel)
        }

        if (level != ConfirmationLevel.AUTO) {
            val allowed =
                awaitConfirmation(
                    action = command.action,
                    detail = sensitiveLabel ?: command.params.optString("path").ifBlank { command.params.optString("app") },
                )
            if (!allowed) {
                log(command.action, ok = false, detail = "denied by user")
                writeResult(workspace, DeviceCommandCodec.failure(command.id, "denied by user", needsConfirmation = true))
                return
            }
        }

        val result =
            runCatching {
                execute(command)
            }.fold(
                onSuccess = { payload -> DeviceCommandCodec.success(command.id, payload) },
                onFailure = { DeviceCommandCodec.failure(command.id, it.message ?: "action failed") },
            )
        val ok = result.optBoolean("ok")
        log(command.action, ok = ok, detail = result.optJSONObject("result")?.optString("summary").orEmpty())
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
            put(
                "verified",
                if (verified) true else "unverified (accessibility not reporting this app)",
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

    private fun log(
        action: String,
        ok: Boolean,
        detail: String,
    ) {
        store.appendActivity(
            JSONObject()
                .put("action", action)
                .put("ok", ok)
                .put("detail", detail),
        )
    }

    private companion object {
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
