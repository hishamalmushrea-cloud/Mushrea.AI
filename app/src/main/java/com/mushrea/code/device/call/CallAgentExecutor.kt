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
            else -> executeCallStop()
        }

    private fun executeFindContact(params: JSONObject): JSONObject.() -> Unit {
        val query = params.optString("query").ifBlank { fail("query is required") }
        val resolved = controller.resolveContact(query) ?: fail("no contact matching \"$query\"")
        return {
            put("number", resolved.first)
            put("label", resolved.second)
            put("summary", "found ${resolved.second}: ${resolved.first}")
        }
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
                val contact = controller.resolveContact(parsed.contactQuery)
                    ?: fail("no contact matching \"${parsed.contactQuery}\"")
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
    ): JSONObject.() -> Unit = {
        put("started", true)
        put("contact", contact)
        put("summary", "call agent started for $contact with $goalCount goal(s) — poll device_call_state")
    }

    /** The real error type so failures flow the bridge's normal error path. */
    private fun fail(message: String): Nothing = throw DeviceFileAgent.DeviceAgentError(message)
}
