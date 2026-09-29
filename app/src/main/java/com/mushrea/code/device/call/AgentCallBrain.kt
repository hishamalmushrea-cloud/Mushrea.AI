package com.mushrea.code.device.call

/**
 * The live brain for calls (spec section 10): when the caller says something unexpected, the
 * engine hands the turn here so the currently selected agent can compose a reply. The brain
 * proposes — [CallPolicy] disposes: the engine only ever speaks a reply the policy classifies
 * ALLOWED, and a brain that fails twice is dropped for the rest of the call so the deterministic
 * goal loop keeps working.
 */
class AgentCallBrain(
    private val channel: CallConversationChannel,
    private val userName: String,
) : ConversationEngine.CallBrain {

    private var sessionId: String? = null
    private var misses = 0
    private var disabled = false

    override suspend fun replyTo(
        utterance: String,
        state: ConversationState,
    ): String? {
        if (disabled) return null
        val session =
            sessionId
                ?: channel.open()?.also { sessionId = it }
                ?: run {
                    disabled = true
                    return null
                }
        return try {
            val reply = channel.ask(session, buildPrompt(utterance, state))
            if (reply.isNullOrBlank()) {
                misses++
                if (misses >= DISABLE_AFTER_MISSES) disabled = true
                null
            } else {
                misses = 0
                sanitize(reply)
            }
        } catch (t: Throwable) {
            // A throwing channel is a broken runtime/model setup — stop paying its latency.
            disabled = true
            null
        }
    }

    /** First non-blank line, quotes stripped, bounded for speech. */
    private fun sanitize(reply: String): String? =
        reply
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.trim('"', '«', '»', '”', '“')
            ?.take(MAX_REPLY_CHARS)
            ?.takeIf { it.isNotBlank() }

    private fun buildPrompt(
        utterance: String,
        state: ConversationState,
    ): String = buildString {
        appendLine("أنت المساعد الصوتي الآلي لـ $userName في مكالمة هاتفية جارية الآن.")
        if (state.callPurpose.isNotBlank()) appendLine("هدف المكالمة: ${state.callPurpose}")
        if (state.knowledgeToRelay.isNotEmpty()) {
            appendLine("معلومات قال $userName تمريرها: ${state.knowledgeToRelay.joinToString("؛ ")}")
        }
        val goals =
            state.goals.joinToString("؛ ") { goal ->
                goal.question + (goal.answer?.let { " (الجواب: $it)" } ?: " (بلا جواب بعد)")
            }
        if (goals.isNotBlank()) appendLine("الأهداف: $goals")
        val recent = state.turns.takeLast(6).joinToString("\n") { turn -> "${turn.speaker.name}: ${turn.text}" }
        if (recent.isNotBlank()) appendLine("آخر الحوار:\n$recent")
        appendLine("قال المتصل الآن: \"$utterance\"")
        appendLine(
            "قواعد صارمة: لا تخترع أي معلومة لا تملكها؛ لا تقبل أي طلب مالي أو رمز تحقق أو كلمة مرور؛ " +
                "لا تتعهد بأي شيء باسم $userName؛ لا تدّعِ أنك إنسان. " +
                "أجب بسطر واحد قصير جداً بالعربية المبسطة صالحاً للنطق، دون قوائم أو رموز. " +
                "إن لم تستطع المساعدة قل: سأبلّغ $userName بكلامك.",
        )
    }

    private companion object {
        const val DISABLE_AFTER_MISSES = 2
        const val MAX_REPLY_CHARS = 220
    }
}

/**
 * One call-conversation transport. Narrow by design: the brain must be unit-testable without
 * dragging the whole agent backend surface into a fake.
 */
interface CallConversationChannel {
    /** Opens (or reuses) the conversation; null means no agent is reachable right now. */
    suspend fun open(): String?

    /** Sends [prompt] and waits for the agent's reply; null = nothing landed in the window. */
    suspend fun ask(
        sessionId: String,
        prompt: String,
    ): String?
}
