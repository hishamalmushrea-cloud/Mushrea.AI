package com.mushrea.code.device

import android.content.Context
import com.mushrea.code.core.security.SecretRedaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The exportable audit trail the owner asked for: one JSON document per export, containing what
 * the Device Agent did, when, and whether it succeeded — so a flashing session can be reviewed
 * afterwards (or handed to someone else) without trusting the screen.
 *
 * It is deliberately narrow:
 * - it contains only the **activity log** (action, result, summary, timestamp, the permission
 *   record: who asked, the declared risk, the decision, its reason, what was verified, a digest of
 *   the parameters, and for an executed command how long it took) plus the safety switches in
 *   force at export time (read-only mode, risk acknowledgment, firewall overrides);
 * - it never contains command *parameters*, file contents, or the unlock token. That is not a
 *   redaction pass over free text — the token never enters the log, and [exportEntry] copies only
 *   the keys listed above, so an entry that ever carried an extra field would still not export it
 *   (`DeviceAuditLogTest` pins both down).
 */
object DeviceAuditLog {
    const val FORMAT_VERSION = 4

    /**
     * A fingerprint of a command's parameters, so two entries can be told apart without the export
     * ever carrying the parameters themselves (the audit trail must stay safe to hand over).
     *
     * Two properties are deliberate:
     *  * the text is passed through [SecretRedaction] first, so a credential-shaped value never
     *    reaches the digest input at all;
     *  * the hash is keyed with a key generated once per process, which makes digests comparable
     *    **within one app run** (the unit a session is reviewed in) and useless as an offline hash
     *    of a short secret. A bare SHA-256 of `{"password":"…"}` would be brute-forced from the
     *    export; an HMAC with a key that dies with the process cannot be.
     *
     * Returns null when there is nothing to fingerprint.
     */
    fun paramsDigest(params: JSONObject?): String? {
        if (params == null || params.length() == 0) return null
        val redacted = SecretRedaction.redact(params.toString())
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply { init(SecretKeySpec(SESSION_KEY, HMAC_ALGORITHM)) }
        val bytes = mac.doFinal(redacted.toByteArray(Charsets.UTF_8))
        return DIGEST_PREFIX + bytes.take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
    }

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
                    .put("activity entries: action, result, summary, timestamp, actor, risk, decision, reason, verification, params_digest, and started_at/ended_at/duration_ms for an executed command")
                    .put("verification says whether the app checked the effect itself, or only that the executor reported success")
                    .put("the actor is the channel that asked (agent), not an authenticated identity")
                    .put("no command parameters, no file contents, and no unlock token are stored here")
                    .put("params_digest is an HMAC of the redacted parameters; it compares entries within this app run and cannot be reversed")
                    .put("started_at/ended_at cover the execution of the command; a denied or blocked command has no window")
                    .put("the log is bounded; export before a long session if you need the full history"),
            )
            .put("entries", entries)
    }

    /**
     * One activity entry as it appears in the export (format 4).
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
        // Format 3: what was actually verified about the outcome, so a review can tell a proven
        // effect from a reported one.
        // (Format 4 keeps this pair and adds the parameter digest and the execution window below.)
        if (entry.has("verified")) exported.put("verified", entry.optBoolean("verified"))
        if (entry.has("verification")) exported.put("verification", entry.optString("verification"))
        // Format 4: the parameter fingerprint, and the execution window for an action that ran.
        // `ended_at` is the entry's own timestamp, so a reader never has to guess which of the two
        // is which. A denied or blocked command has no window: it never executed.
        if (entry.has("params_digest")) exported.put("params_digest", entry.optString("params_digest"))
        if (entry.has("started_at")) {
            val startedAt = entry.optLong("started_at")
            val endedAt = entry.optLong("ts")
            exported.put("started_at", startedAt)
            exported.put("ended_at", endedAt)
            if (endedAt >= startedAt) exported.put("duration_ms", endedAt - startedAt)
        }
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

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val DIGEST_PREFIX = "hmac-sha256-"
    private const val DIGEST_BYTES = 16

    /** Generated once per process; see [paramsDigest] for why the digest is keyed rather than plain. */
    private val SESSION_KEY = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun versionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
}
