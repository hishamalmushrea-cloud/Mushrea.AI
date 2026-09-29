package com.mushrea.code.device.call

import android.content.Context
import android.content.Intent
import com.mushrea.code.device.DeviceActionFirewall
import com.mushrea.code.device.DeviceCommand
import com.mushrea.code.device.DeviceFileAgent
import org.json.JSONArray
import org.json.JSONObject

/**
 * The call-agent surface the LLM agent core drives through the same device command channel as
 * every other device tool (spec section 34): find a contact, hand a natural-language command to
 * the conversation agent, poll its state, or stop it. Outbound call management goes through the
 * firewall first (CONFIRM by default); this class runs only after that pipeline approved.
 */
class CallAgentExecutor(
    private val context: Context,
) {
    private val controller by lazy { PhoneCallController(context) }
    private val store by lazy { CallAgentStore(context) }

    fun execute(command: DeviceCommand): JSONObject.() -> Unit =
        when (command.action) {
            DeviceActionFirewall.ACTION_FIND_CONTACT -> executeFindContact(command.params)
            DeviceActionFirewall.ACTION_CALL_AGENT -> executeCallAgent(command.params)
            DeviceActionFirewall.ACTION_CALL_STATE -> executeCallState()
            DeviceActionFirewall.ACTION_READ_CALL_LOG -> executeCallLog()
            else -> executeCallStop()
        }

    private fun executeFindContact(params: JSONObject): JSONObject.() -> Unit {
        val query = params.optString("query").ifBlank { fail("query is required") }
        val matches = controller.resolveContacts(query)
        if (matches.isEmpty()) fail("no contact matching \"$query\"")
        return {
            put(
                "matches",
                JSONArray().apply {
                    matches.forEach { (number, label) -> put(JSONObject().put("label", label).put("number", number)) }
                },
            )
            put("exact_match", matches.size == 1)
            if (matches.size == 1) {
                put("number", matches[0].first)
                put("label", matches[0].second)
            }
            put(
                "summary",
                if (matches.size == 1) {
                    "found ${matches[0].second}: ${matches[0].first}"
                } else {
                    "${matches.size} contacts match \"$query\" — ask the user which one before device_call_agent"
                },
            )
        }
    }

    /** The recent call log (missed included) — an AUTO read behind the firewall. */
    private fun executeCallLog(): JSONObject.() -> Unit {
        val entries = controller.recentCalls()
        return {
            put(
                "calls",
                JSONArray().apply {
                    entries.forEach { (number, name, type) ->
                        put(
                            JSONObject()
                                .put("number", number)
                                .put("name", name ?: JSONObject.NULL)
                                .put("type", callTypeName(type)),
                        )
                    }
                },
            )
            put(
                "summary",
                if (entries.isEmpty()) "no recent calls (or the call-log permission is missing)" else "${entries.size} recent call(s)",
            )
        }
    }

    /** Call memory: the stored summaries (purpose, answers, facts, outcome) — an AUTO read. */
    fun executeCallSummaries(params: JSONObject): JSONObject.() -> Unit {
        val limit = params.optInt("limit", 10).coerceIn(1, 50)
        val all = store.readCallLog()
        val privacyOn = store.readPrivacy().optBoolean("store_summary")
        val entries = JSONArray()
        for (index in (all.length() - limit).coerceAtLeast(0) until all.length()) {
            runCatching { entries.put(all.getJSONObject(index)) }
        }
        return {
            put("entries", entries)
            put(
                "summary",
                when {
                    entries.length() == 0 && !privacyOn -> "no stored call summaries (summary storage is off in call privacy)"
                    entries.length() == 0 -> "no stored call summaries"
                    else -> "${entries.length()} stored call summary(ies), newest last",
                },
            )
        }
    }

    private fun callTypeName(type: Int): String =
        when (type) {
            android.provider.CallLog.Calls.MISSED_TYPE -> "missed"
            android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"
            android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            android.provider.CallLog.Calls.REJECTED_TYPE -> "rejected"
            else -> "other"
        }

    /**
     * Parses the natural command locally and hands the task to the foreground service. A command
     * the deterministic parser does not understand is reported as such — the agent core is told
     * to decompose it into supported shapes instead of the service guessing.
     */
    private fun executeCallAgent(params: JSONObject): JSONObject.() -> Unit {
        val command = params.optString("command").ifBlank { fail("command is required") }
        return when (val parsed = CallIntentParser.parse(command)) {
            is CallIntentParser.Parsed.Task -> {
                CallAgentService.start(context, parsed.task)
                startedResult(parsed.task.contactQuery, parsed.task.goals.size)
            }
            is CallIntentParser.Parsed.DialOnly -> {
                val matches = controller.resolveContacts(parsed.contactQuery)
                val contact =
                    matches.singleOrNull()
                        ?: fail(
                            if (matches.isEmpty()) {
                                "no contact matching \"${parsed.contactQuery}\""
                            } else {
                                "ambiguous contact \"${parsed.contactQuery}\": " + matches.joinToString("، ") { it.second } +
                                    " — ask the user which one"
                            },
                        )
                CallAgentService.start(
                    context,
                    CallTask(
                        contactQuery = parsed.contactQuery,
                        goals = listOf(ConversationGoal("كيف حالك؟")),
                    ),
                )
                startedResult(contact.second, 1)
            }
            CallIntentParser.Parsed.NotACall ->
                fail("command is not a recognizable call task — use shapes like: اتصل بـ<X> واسأله <سؤال>")
        }
    }

    private fun executeCallState(): JSONObject.() -> Unit {
        val state = store.readLiveState()
        return {
            put("state", state.optString("state"))
            state.optJSONObject("conversation")?.let { put("conversation", it) }
            put("summary", "call agent state: ${state.optString("state")}")
        }
    }

    private fun executeCallStop(): JSONObject.() -> Unit {
        context.startService(
            Intent(context, CallAgentService::class.java).setAction(CallAgentService.ACTION_STOP_AGENT),
        )
        return { put("summary", "stop requested — the call agent halts before its next turn") }
    }

    /** Kept as its own function so the trailing lambda is never parsed as start()'s argument. */
    private fun startedResult(
        contact: String,
        goalCount: Int,
    ): JSONObject.() -> Unit =
        {
            put("started", true)
            put("contact", contact)
            put("summary", "call agent started for $contact with $goalCount goal(s) — poll device_call_state")
        }

    /** The real error type so failures flow the bridge's normal error path. */
    private fun fail(message: String): Nothing = throw DeviceFileAgent.DeviceAgentError(message)
}
