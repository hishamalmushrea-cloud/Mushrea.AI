package com.mushrea.code.device

import android.content.Context
import com.mushrea.code.core.permission.ConfirmationLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed state for the Device Agent (kept deliberately dependency-free: plain JSON files under
 * `filesDir/device-agent/`, like the org.json style used across the runtime code).
 *
 * Holds:
 * - the active workspace path (published by the app UI whenever the user switches chats)
 * - the Permission Firewall overrides
 * - pending confirmation requests + the user's decision (written by [com.mushrea.code.device.DeviceConfirmReceiver])
 * - the emergency-stop flag ([com.mushrea.code.device.StopAgentReceiver] sets it, the bridge consumes it)
 * - a bounded activity log (prompt section 37)
 */
class DeviceAgentStore(context: Context) {
    private val dir: File = File(context.applicationInfo.dataDir, DIR_NAME).apply { mkdirs() }

    // region Active workspace

    @Synchronized
    fun writeActiveWorkspace(path: String?) {
        val file = File(dir, ACTIVE_WORKSPACE_FILE)
        if (path.isNullOrBlank()) {
            file.delete()
        } else {
            file.writeText(path)
        }
    }

    @Synchronized
    fun readActiveWorkspace(): String? {
        val file = File(dir, ACTIVE_WORKSPACE_FILE)
        val path = runCatching { file.takeIf(File::isFile)?.readText()?.trim() }.getOrNull()
        return path?.takeIf { it.isNotBlank() && File(it).isDirectory }
    }

    // endregion

    // region Context (prompt sections 9, 16, 22)

    @Synchronized
    fun readContext(): DeviceContext = DeviceContext.fromJson(readFile(CONTEXT_FILE)) ?: DeviceContext.EMPTY

    @Synchronized
    private fun writeContext(next: DeviceContext) {
        File(dir, CONTEXT_FILE).writeText(next.toJson().toString(2))
    }

    @Synchronized
    fun updateContextApp(
        app: String,
        activity: String?,
    ) {
        writeContext(DeviceContextReducer.withApp(readContext(), app, activity, System.currentTimeMillis()))
    }

    @Synchronized
    fun updateContextTask(task: String) {
        writeContext(DeviceContextReducer.withTask(readContext(), task, System.currentTimeMillis()))
    }

    @Synchronized
    fun updateContextLastFile(path: String) {
        writeContext(DeviceContextReducer.withLastFile(readContext(), path, System.currentTimeMillis()))
    }

    // endregion

    // region Firewall overrides

    @Synchronized
    fun firewallOverrides(): Map<String, ConfirmationLevel> =
        runCatching {
            val root = JSONObject(readFile(FIREWALL_FILE))
            root.keys().asSequence().mapNotNull { key ->
                // ConfirmationLevel.parseOrNull accepts the pre-P2 "STRONG" spelling, so an override
                // the user raised before the unified vocabulary still applies instead of silently
                // falling back to the tool's catalog level.
                val level = ConfirmationLevel.parseOrNull(root.optString(key)) ?: return@mapNotNull null
                key to level
            }.toMap()
        }.getOrDefault(emptyMap())

    @Synchronized
    fun setFirewallOverride(
        action: String,
        level: ConfirmationLevel?,
    ) {
        val root = runCatching { JSONObject(readFile(FIREWALL_FILE)) }.getOrDefault(JSONObject())
        if (level == null) {
            root.remove(action)
        } else {
            root.put(action, level.name)
        }
        File(dir, FIREWALL_FILE).writeText(root.toString(2))
    }

    // endregion

    // region Read-only mode + risk acknowledgment

    /**
     * Read-Only Default: until the user turns it off, only actions that read state are allowed to
     * run automatically. Anything that writes, installs or changes another device is refused by
     * the bridge with a pointer to this switch. Defaults to `true` on a fresh install.
     */
    @Synchronized
    fun readOnlyMode(): Boolean = readFile(READ_ONLY_FILE).trim().let { it.isEmpty() || it.toBoolean() }

    @Synchronized
    fun setReadOnlyMode(enabled: Boolean) {
        File(dir, READ_ONLY_FILE).writeText(enabled.toString())
    }

    /** When the user accepted the unlock/flash risks; 0 means "not accepted yet". */
    @Synchronized
    fun riskAcknowledgedAt(): Long = readFile(RISK_FILE).trim().toLongOrNull() ?: 0L

    @Synchronized
    fun acknowledgeRisk() {
        File(dir, RISK_FILE).writeText(System.currentTimeMillis().toString())
    }

    @Synchronized
    fun clearRiskAcknowledgement() {
        File(dir, RISK_FILE).delete()
    }

    // endregion

    // region Confirmations

    /**
     * Registers a confirmation the user must answer; returns its unique id.
     */
    @Synchronized
    fun requestConfirmation(
        action: String,
        detail: String,
    ): String {
        val id = "confirm-" + System.currentTimeMillis()
        JSONObject()
            .put("id", id)
            .put("action", action)
            .put("detail", detail)
            .put("ts", System.currentTimeMillis())
            .let { File(dir, PENDING_CONFIRM_FILE).writeText(it.toString()) }
        return id
    }

    /** Returns "allow" / "deny" once the user answered, null while still pending. */
    @Synchronized
    fun consumeConfirmationDecision(requestId: String): String? {
        val file = File(dir, PENDING_CONFIRM_FILE)
        val root = runCatching { JSONObject(readFile(PENDING_CONFIRM_FILE)) }.getOrNull() ?: return null
        if (root.optString("id") != requestId) return null
        val decision = root.optString("decision")
        if (decision !in setOf("allow", "deny")) return null
        file.delete()
        return decision
    }

    /** Called by the confirm receiver without validating the pending id first. */
    @Synchronized
    fun answerConfirmation(
        requestId: String,
        decision: String,
    ) {
        val root = runCatching { JSONObject(readFile(PENDING_CONFIRM_FILE)) }.getOrNull() ?: return
        if (root.optString("id") != requestId) return
        root.put("decision", decision)
        File(dir, PENDING_CONFIRM_FILE).writeText(root.toString())
    }

    @Synchronized
    fun pendingConfirmation(): JSONObject? = runCatching { JSONObject(readFile(PENDING_CONFIRM_FILE)) }.getOrNull()

    // endregion

    // region Emergency stop

    @Synchronized
    fun requestStop() {
        File(dir, STOP_FILE).writeText(System.currentTimeMillis().toString())
    }

    /**
     * Reads and clears the stop flag. A flag older than [STOP_FLAG_TTL_MILLIS] is discarded instead
     * of consumed: the user may have said stop with nothing running, and a stale flag must not
     * silently abort the *next* task's first step.
     */
    @Synchronized
    fun consumeStopRequest(): Boolean {
        val file = File(dir, STOP_FILE)
        val requestedAt = file.takeIf(File::isFile)?.readText()?.toLongOrNull()
        file.delete()
        return requestedAt != null && System.currentTimeMillis() - requestedAt <= STOP_FLAG_TTL_MILLIS
    }

    /**
     * Reads the stop flag *without* clearing it.
     *
     * The device channel consumes the flag, because it is the one that has to abort a running task.
     * A second reader that also consumed it would silently swallow the user's stop request before
     * the task it was meant for ever saw it - so the peer path peeks, and only the device path
     * takes.
     */
    @Synchronized
    fun stopRequested(): Boolean {
        val requestedAt = File(dir, STOP_FILE).takeIf(File::isFile)?.readText()?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - requestedAt <= STOP_FLAG_TTL_MILLIS
    }

    // endregion

    // region Activity log

    @Synchronized
    fun appendActivity(entry: JSONObject) {
        val log =
            runCatching { JSONArray(readFile(ACTIVITY_FILE)) }.getOrDefault(JSONArray())
        entry.put("ts", System.currentTimeMillis())
        log.put(entry)
        while (log.length() > MAX_LOG_ENTRIES) {
            log.remove(0)
        }
        File(dir, ACTIVITY_FILE).writeText(log.toString(2))
    }

    @Synchronized
    fun activityLog(): List<JSONObject> =
        runCatching {
            val log = JSONArray(readFile(ACTIVITY_FILE))
            (0 until log.length()).mapNotNull { log.optJSONObject(it) }
        }.getOrDefault(emptyList()).reversed()

    // endregion

    /**
     * File contents, or "" when the file does not exist yet (first run). Callers parse through
     * runCatching / [DeviceContext.fromJson], which map "" to their empty defaults — reading
     * context or log state must never throw just because nothing was written before.
     */
    private fun readFile(name: String): String = runCatching { File(dir, name).takeIf(File::isFile)?.readText() }.getOrNull().orEmpty()

    private companion object {
        const val DIR_NAME = "device-agent"
        const val CONTEXT_FILE = "device-context.json"
        const val ACTIVE_WORKSPACE_FILE = "active-workspace"
        const val FIREWALL_FILE = "firewall-overrides.json"
        const val PENDING_CONFIRM_FILE = "pending-confirmation.json"
        const val STOP_FILE = "stop-requested"
        const val ACTIVITY_FILE = "activity-log.json"
        const val READ_ONLY_FILE = "read-only-mode"
        const val RISK_FILE = "risk-acknowledged-at"
        const val MAX_LOG_ENTRIES = 200

        /** The emergency stop is only meaningful while the user's stop intent is still current. */
        const val STOP_FLAG_TTL_MILLIS = 60_000L
    }
}
