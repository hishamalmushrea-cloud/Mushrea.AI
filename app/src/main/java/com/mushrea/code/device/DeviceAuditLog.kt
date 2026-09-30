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
 * - it contains only the **activity log** (action, result, summary, timestamp) plus the safety
 *   switches in force at export time (read-only mode, risk acknowledgment, firewall overrides);
 * - it never contains command *parameters*, file contents, or the unlock token. That is not a
 *   redaction pass over free text — the token never enters the log in the first place, and a unit
 *   test pins that down (`DeviceAuditLogTest`).
 */
object DeviceAuditLog {
    const val FORMAT_VERSION = 1

    fun build(
        context: Context,
        store: DeviceAgentStore,
    ): JSONObject {
        val entries = JSONArray()
        store.activityLog().forEach { entry ->
            entries.put(
                JSONObject()
                    .put("at", entry.optLong("ts"))
                    .put("action", entry.optString("action"))
                    .put("ok", entry.optBoolean("ok"))
                    .put("summary", entry.optString("detail")),
            )
        }
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
                    .put("activity entries only: action, result, summary, timestamp")
                    .put("no command parameters, no file contents, and no unlock token are stored here")
                    .put("the log is bounded; export before a long session if you need the full history"),
            )
            .put("entries", entries)
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
