package com.mushrea.code.device.call

/**
 * What the call is for, parsed from the user's natural command (spec section 33): "اتصل بأحمد
 * واسأله أين هو وكيف حاله" becomes a contact query plus an ordered list of goals, plus any
 * information the user asked the agent to relay (spec section 12). The agent never adds facts of
 * its own — everything it says comes from this task or from the caller's own words.
 */
data class CallTask(
    val contactQuery: String,
    val goals: List<ConversationGoal>,
    val knowledgeToRelay: List<String> = emptyList(),
    val mode: Mode = Mode.OUTGOING,
) {
    enum class Mode {
        /** Dial the contact and run the goals. */
        OUTGOING,

        /** Answer-policy request ("إذا اتصل بي أحمد رد عليه وخذ منه رسالة"). */
        ANSWER_POLICY,
    }

    val isMessageTakingOnly: Boolean
        get() = goals.isEmpty()
}

/**
 * One thing to find out or one thing to relay. A goal is complete when a caller utterance has
 * been recorded against it (spec section 8/9) — completion is never inferred from silence.
 */
data class ConversationGoal(
    val question: String,
    val answer: String? = null,
) {
    val isAnswered: Boolean get() = answer != null

    fun withAnswer(answer: String): ConversationGoal = copy(answer = answer)
}

/**
 * In-memory conversation context for the running call (spec section 8). Deliberately tiny and
 * serializable so the activity log and the post-call summary are built from the same data the
 * engine used live.
 */
data class ConversationState(
    val callTarget: String = "",
    val callPurpose: String = "",
    val goals: List<ConversationGoal> = emptyList(),
    val knowledgeToRelay: List<String> = emptyList(),
    val relaidKnowledge: List<String> = emptyList(),
    val lastCallerStatement: String? = null,
    val lastAgentStatement: String? = null,
    val pendingCallerQuestion: String? = null,
    val importantFacts: List<String> = emptyList(),
    val turns: List<Turn> = emptyList(),
) {
    data class Turn(
        val speaker: Speaker,
        val text: String,
        val elapsedMillis: Long,
    )

    enum class Speaker { AGENT, CALLER, SYSTEM }

    val nextUnansweredGoal: ConversationGoal?
        get() = goals.firstOrNull { !it.isAnswered }

    val pendingGoalCount: Int
        get() = goals.count { !it.isAnswered }

    val allGoalsAnswered: Boolean
        get() = goals.isNotEmpty() && goals.all { it.isAnswered }

    fun recordAgent(text: String, elapsedMillis: Long): ConversationState =
        copy(lastAgentStatement = text, turns = turns + Turn(Speaker.AGENT, text, elapsedMillis))

    fun recordCaller(
        text: String,
        elapsedMillis: Long,
    ): ConversationState = copy(lastCallerStatement = text, turns = turns + Turn(Speaker.CALLER, text, elapsedMillis))

    fun recordSystem(text: String, elapsedMillis: Long): ConversationState =
        copy(turns = turns + Turn(Speaker.SYSTEM, text, elapsedMillis))

    fun answerCurrentGoal(answer: String): ConversationState {
        val index = goals.indexOfFirst { !it.isAnswered }
        if (index < 0) return this
        return copy(goals = goals.mapIndexed { i, goal -> if (i == index) goal.withAnswer(answer) else goal })
    }

    fun markKnowledgeRelaid(item: String): ConversationState =
        copy(relaidKnowledge = relaidKnowledge + item)

    fun addFact(fact: String): ConversationState =
        if (fact.isBlank() || importantFacts.contains(fact)) this else copy(importantFacts = importantFacts + fact)

    fun setPendingCallerQuestion(question: String?): ConversationState = copy(pendingCallerQuestion = question)
}
