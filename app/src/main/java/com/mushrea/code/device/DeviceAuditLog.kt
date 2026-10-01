package com.mushrea.code.device

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The exportable audit trail the owner asked for: one JSON document per export, containing what
 * the Device Agent did, when, and whether it succeeded — so a flashing session can be reviewed
 * afterwards (or handed to someone else) without trusting the screen.
 *
 * It is deliberately narrow:
 * - it contains only the **activity log** (action, result, summary, timestamp, and the permission
 *   record: who asked, the declared risk, the decision, its reason) plus the safety switches in
 *   force at export time (read-only mode, risk acknowledgment, firewall overrides);
 * - it never contains command *parameters*, file contents, or the unlock token. That is not a
 *   redaction pass over free text — the token never enters the log, and [exportEntry] copies only
 *   the keys listed above, so an entry that ever carried an extra field would still not export it
 *   (`DeviceAuditLogTest` pins both down).
 */
object DeviceAuditLog {
    const val FORMAT_VERSION = 2

    fun build(
        context: Context,
        store: DeviceAgentStore,
    ): JSONObject {
        val entries = JSONArray()
        store.activityLog().forEach { entry -> entries.put(exportEntry(entry)) }
        val overrides = JSONObject()
        store.firewallOverrides().forEach { (action, level) -> overrides.put(action, level.name) }
        return JSONObject()
            .put("format", "mushrea-code.device-audit")
            .put("format_version", FORMAT_VERSION)
            .put("exported_at", System.currentTimeMillis())
            .put("app_package", context.packageName)
            .put("app_version", versionName(context))
            .put("read_only_mode", store.readOnlyMode())
            .put("risk_acknowledged_at", store.riskAcknowledgedAt())
            .put("firewall_overrides", overrides)
            .put(
                "notes",
                JSONArray()
                    .put("activity entries: action, result, summary, timestamp, actor, risk, decision, reason")
                    .put("the actor is the channel that asked (agent), not an authenticated identity")
                    .put("no command parameters, no file contents, and no unlock token are stored here")
                    .put("the log is bounded; export before a long session if you need the full history"),
            )
            .put("entries", entries)
    }

    /**
     * One activity entry as it appears in the export (format 2).
     *
     * Only these keys travel: the action log stores nothing else, and a key the log does not define
     * is dropped rather than copied - which is why the guarantee "no parameters, no token" holds
     * even if an entry ever carries extra fields. The permission fields default to the format-1
     * reading (`agent` / `unknown` / `unrecorded`) so an old entry still exports cleanly.
     */
    fun exportEntry(entry: JSONObject): JSONObject {
        val exported =
            JSONObject()
                .put("at", entry.optLong("ts"))
                .put("action", entry.optString("action"))
                .put("ok", entry.optBoolean("ok"))
                .put("summary", entry.optString("detail"))
                .put("actor", entry.optString("actor", "agent"))
                .put("tool", entry.optString("action"))
                .put("risk", entry.optString("risk", "unknown"))
                .put("decision", entry.optString("decision", "unrecorded"))
        if (entry.has("confirmation_level")) exported.put("confirmation_level", entry.optString("confirmation_level"))
        if (entry.has("reason")) exported.put("reason", entry.optString("reason"))
        return exported
    }

    /** Writes the audit document under the app's private storage and returns the file. */
    fun write(
        context: Context,
        store: DeviceAgentStore,
        timestamp: Long = System.currentTimeMillis(),
    ): File {
        val dir = File(context.filesDir, "device-agent/audit").apply { mkdirs() }
        val file = File(dir, "mushrea-device-audit-$timestamp.json")
        file.writeText(build(context, store).toString(2))
        return file
    }

    private fun versionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
}
