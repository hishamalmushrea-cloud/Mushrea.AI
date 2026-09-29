package com.mushrea.code.device.call

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence for the call agent (spec sections 27/36): incoming-call rules, privacy toggles,
 * the live call state file the MCP surface reads, taken messages, and the bounded call log.
 * Everything lives in the device-agent directory — app-private storage, deletable by clearing
 * app data — and audio is never recorded by default (recording is not implemented at all).
 */
class CallAgentStore(
    private val context: Context,
) {
    private val dir: File
        get() = File(context.filesDir, "device-agent").apply { mkdirs() }

    private val policyFile: File get() = File(dir, "call-policy.json")
    private val stateFile: File get() = File(dir, "call-state.json")
    private val logFile: File get() = File(dir, "call-log.json")
    private val messagesFile: File get() = File(dir, "call-messages.json")

    // --- Policies -------------------------------------------------------------

    fun readCallerRules(): MutableMap<String, CallPolicy.IncomingAction> {
        val json = readJson(policyFile) ?: return mutableMapOf()
        val rules = json.optJSONObject("caller_rules") ?: JSONObject()
        val result = mutableMapOf<String, CallPolicy.IncomingAction>()
        rules.keys().forEach { key ->
            CallPolicy.IncomingAction.entries
                .firstOrNull { it.name == rules.optString(key) }
                ?.let { result[key] = it }
        }
        return result
    }

    fun writeCallerRules(rules: Map<String, CallPolicy.IncomingAction>) {
        val json =
            JSONObject()
                .put("caller_rules", JSONObject().apply { rules.forEach { (k, v) -> put(k, v.name) } })
                .put("allow_known_contacts_by_default", readJson(policyFile)?.optBoolean("allow_known_contacts_by_default") ?: false)
                .put("identity_template", readJson(policyFile)?.optString("identity_template") ?: "")
                .put("user_display_name", readJson(policyFile)?.optString("user_display_name") ?: "")
        policyFile.writeText(json.toString(2))
    }

    fun readAllowKnownContactsByDefault(): Boolean = readJson(policyFile)?.optBoolean("allow_known_contacts_by_default") ?: false

    fun writeAllowKnownContactsByDefault(allow: Boolean) {
        rewritePolicy { it.put("allow_known_contacts_by_default", allow) }
    }

    fun readIdentityTemplate(): String = readJson(policyFile)?.optString("identity_template").orEmpty()

    fun writeIdentityTemplate(template: String) = rewritePolicy { it.put("identity_template", template) }

    fun readUserDisplayName(): String = readJson(policyFile)?.optString("user_display_name").orEmpty()

    fun writeUserDisplayName(name: String) = rewritePolicy { it.put("user_display_name", name) }

    // --- Privacy (section 27): transcript/summary storage are opt-in per item; audio is never
    // recorded by this implementation, so there is no toggle to turn that on. Cloud processing
    // stays off unless the user enables it explicitly.

    fun readPrivacy(): JSONObject = readJson(policyFile)?.optJSONObject("privacy") ?: defaultPrivacy()

    fun writePrivacy(
        storeTranscript: Boolean,
        storeSummary: Boolean,
        cloudProcessing: Boolean = readPrivacy().optBoolean("cloud_processing"),
    ) {
        rewritePolicy {
            it.put(
                "privacy",
                defaultPrivacy()
                    .put("store_transcript", storeTranscript)
                    .put("store_summary", storeSummary)
                    .put("cloud_processing", cloudProcessing),
            )
        }
    }

    private fun defaultPrivacy(): JSONObject =
        JSONObject().put("store_transcript", false).put("store_summary", true).put("store_audio", false).put("cloud_processing", false)

    // --- Live state (read by MCP get_call_state / the bridge) ------------------

    fun writeLiveState(
        state: CallStateMachine.State,
        conversation: ConversationState?,
    ) {
        val json =
            JSONObject()
                .put("state", state.name)
                .put("updated_at_millis", System.currentTimeMillis())
                .put(
                    "conversation",
                    conversation?.let { c ->
                        JSONObject()
                            .put("target", c.callTarget)
                            .put("purpose", c.callPurpose)
                            .put(
                                "goals",
                                JSONArray().apply {
                                    c.goals.forEach {
                                            g ->
                                        put(JSONObject().put("question", g.question).put("answer", g.answer ?: JSONObject.NULL))
                                    }
                                },
                            )
                            .put("last_caller", c.lastCallerStatement ?: JSONObject.NULL)
                            .put("last_agent", c.lastAgentStatement ?: JSONObject.NULL)
                            .put(
                                "transcript",
                                JSONArray().apply {
                                    if (readPrivacy().optBoolean("store_transcript")) {
                                        c.turns.forEach { t -> put(JSONObject().put("speaker", t.speaker.name).put("text", t.text)) }
                                    }
                                },
                            )
                    } ?: JSONObject.NULL,
                )
        stateFile.writeText(json.toString(2))
    }

    fun readLiveState(): JSONObject = readJson(stateFile) ?: JSONObject().put("state", CallStateMachine.State.IDLE.name)

    fun clearLiveState() {
        stateFile.delete()
    }

    // --- Bounded log + taken messages -----------------------------------------

    fun appendCallSummary(summary: CallSummary) {
        val privacy = readPrivacy()
        if (!privacy.optBoolean("store_summary")) return
        val log = readJson(logFile) ?: JSONObject().put("entries", JSONArray())
        val entries = log.optJSONArray("entries") ?: JSONArray()
        entries.put(summary.toJson())
        while (entries.length() > MAX_LOG_ENTRIES) entries.remove(0)
        logFile.writeText(log.put("entries", entries).toString(2))
    }

    fun readCallLog(): JSONArray = readJson(logFile)?.optJSONArray("entries") ?: JSONArray()

    fun recordTakenMessage(
        caller: String,
        message: String,
    ) {
        val json = readJson(messagesFile) ?: JSONObject().put("messages", JSONArray())
        val messages = json.optJSONArray("messages") ?: JSONArray()
        messages.put(
            JSONObject()
                .put("caller", caller)
                .put("message", message)
                .put("at_millis", System.currentTimeMillis()),
        )
        while (messages.length() > MAX_MESSAGES) messages.remove(0)
        messagesFile.writeText(json.put("messages", messages).toString(2))
    }

    fun readTakenMessages(): JSONArray = readJson(messagesFile)?.optJSONArray("messages") ?: JSONArray()

    fun clearAllData() {
        listOf(policyFile, stateFile, logFile, messagesFile).forEach(File::delete)
    }

    private fun rewritePolicy(mutate: (JSONObject) -> Unit) {
        val current = readJson(policyFile) ?: JSONObject()
        mutate(current)
        if (!current.has("privacy")) current.put("privacy", defaultPrivacy())
        policyFile.writeText(current.toString(2))
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 50
        const val MAX_MESSAGES = 20
    }

    private fun readJson(file: File): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { file.isFile }
}
