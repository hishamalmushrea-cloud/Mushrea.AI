package com.mushrea.code.device.call

import org.json.JSONArray
import org.json.JSONObject

/**
 * The post-call deliverable (spec sections 24-26): an honest verification block, the answers
 * collected against each goal, caller-provided facts, and a short summary. Built from the same
 * [ConversationState] the live engine used, so nothing is reconstructed from memory.
 */
data class CallSummary(
    val verification: Verification,
    val purpose: String,
    val answers: List<Pair<String, String>>,
    val callerFacts: List<String>,
    val callerMessage: String?,
    val durationMillis: Long,
    val state: CallStateMachine.State,
) {
    /** Section 24: initiated ≠ connected ≠ goal-completed. Each claim is separately recorded. */
    data class Verification(
        val initiated: Boolean,
        val answered: Boolean,
        val goalCompleted: Boolean,
    )

    fun toJson(): JSONObject =
        JSONObject()
            .put(
                "verification",
                JSONObject()
                    .put("initiated", verification.initiated)
                    .put("answered", verification.answered)
                    .put("goal_completed", verification.goalCompleted),
            )
            .put("purpose", purpose)
            .put(
                "answers",
                JSONArray().apply {
                    answers.forEach { (question, answer) -> put(JSONObject().put("question", question).put("answer", answer)) }
                },
            )
            .put("caller_facts", JSONArray(callerFacts))
            .put("caller_message", callerMessage ?: JSONObject.NULL)
            .put("duration_millis", durationMillis)
            .put("final_state", state.name)

    /** The human-readable report handed back to the user and to the agent core. */
    fun toText(): String =
        buildString {
            appendLine(if (verification.answered) "تمت المكالمة." else "لم تكتمل المكالمة (${state.name}).")
            if (purpose.isNotBlank()) appendLine("الهدف: $purpose")
            answers.forEach { (question, answer) -> appendLine("- $question → $answer") }
            callerFacts.forEach { appendLine("- معلومة: $it") }
            callerMessage?.let { appendLine("رسالة من المتصل: $it") }
            appendLine("المدة: ${durationMillis / 1000} ثانية")
        }.trimEnd()

    companion object {
        fun fromJson(json: JSONObject): CallSummary {
            val verification = json.optJSONObject("verification") ?: JSONObject()
            return CallSummary(
                verification =
                    Verification(
                        initiated = verification.optBoolean("initiated"),
                        answered = verification.optBoolean("answered"),
                        goalCompleted = verification.optBoolean("goal_completed"),
                    ),
                purpose = json.optString("purpose"),
                answers =
                    json.optJSONArray("answers")?.let { array ->
                        (0 until array.length()).mapNotNull { i ->
                            val item = array.optJSONObject(i) ?: return@mapNotNull null
                            item.optString("question") to item.optString("answer")
                        }
                    }.orEmpty(),
                callerFacts =
                    json.optJSONArray("caller_facts")?.let { array ->
                        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    }.orEmpty(),
                callerMessage = json.optString("caller_message").takeIf { it.isNotBlank() },
                durationMillis = json.optLong("duration_millis"),
                state =
                    json.optString("final_state").let { name ->
                        CallStateMachine.State.entries.firstOrNull { it.name == name } ?: CallStateMachine.State.COMPLETED
                    },
            )
        }

        /** Builds the summary from the live conversation state and the machine's final state. */
        fun from(
            conversation: ConversationState,
            finalState: CallStateMachine.State,
            durationMillis: Long,
            wasInitiated: Boolean,
            wasAnswered: Boolean,
        ): CallSummary =
            CallSummary(
                verification =
                    Verification(
                        initiated = wasInitiated,
                        answered = wasAnswered,
                        goalCompleted = conversation.allGoalsAnswered,
                    ),
                purpose = conversation.callPurpose,
                answers = conversation.goals.mapNotNull { goal -> goal.answer?.let { goal.question to it } },
                callerFacts = conversation.importantFacts.toList(),
                callerMessage =
                    conversation.turns
                        .lastOrNull { it.speaker == ConversationState.Speaker.CALLER }
                        ?.text
                        ?.takeIf { conversation.callPurpose == "ماذا تحتاج؟" },
                durationMillis = durationMillis,
                state = finalState,
            )
    }
}
