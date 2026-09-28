package com.mushrea.code.device.call

import com.mushrea.code.device.AppResolver

/**
 * Turns a natural command into a [CallTask] (spec section 33): "اتصل بأحمد واسأله أين هو وكيف
 * حاله" → contact "أحمد", goals ["أين هو؟", "كيف حالك؟"]. Deterministic keyword/segment parsing
 * in the style of StopPhrases — no model needed for the common shapes; unparseable input returns
 * [Parsed.NotACall] so callers hand the text to the LLM agent core instead of guessing.
 */
object CallIntentParser {

    /** Parsed command shapes the deterministic layer understands. */
    sealed interface Parsed {
        data class Task(val task: CallTask) : Parsed

        /** A contact plus a dial verb, but no ask/tell/message segment. */
        data class DialOnly(val contactQuery: String) : Parsed

        /** Not a call command. */
        data object NotACall : Parsed
    }

    // Every marker below is written post-normalization (AppResolver.normalize folds the alef
    // seats, ة→ه and ى→ي), because the input is normalized before matching.
    private val dialVerbs = listOf("اتصل ب", "اتصل علي", "اتصل", "call ", "dial ", "phone ")
    private val answerVerbs = listOf("اذا اتصل", "لو اتصل", "عندما يتصل", "when he calls", "if he calls", "if called")
    private val actionMarkers = listOf("واساله", "واسالها", "اساله", "اسالها", "واسال", "وقل له", "وقلها", "قل له", "قل لها", "وخبره", "ask him", "ask her", "ask", "tell him", "tell her", "tell")
    private val messageMarkers = listOf("خذ منه رساله", "خذ رساله", "خذ منه", "take a message", "take message")
    private val endTriggers = listOf("ثم اخبرني", "واخبرني", "واخبرني", "then tell me", "and tell me")

    private val statusQuestions: List<Pair<String, String>> =
        listOf(
            "اين هو" to "أين أنت الآن؟",
            "اين انت" to "أين أنت الآن؟",
            "كيف حاله" to "كيف حالك؟",
            "متى سياتي" to "متى ستأتي؟",
            "متى يصل" to "متى ستصل؟",
            "ماذا يريد" to "ماذا تحتاج؟",
            "where is he" to "Where are you right now?",
            "where are you" to "Where are you right now?",
            "how is he" to "How are you doing?",
            "when will he come" to "When are you coming?",
            "when will he arrive" to "When will you arrive?",
        )

    /**
     * Parses [text]: the contact is the segment after the dial verb up to the first action
     * marker; status questions become canonical goals; a "قل له ..." segment becomes relay
     * knowledge. The message-taking shape ("رد عليه وخذ منه رسالة") maps to the purpose question.
     */
    fun parse(text: String): Parsed {
        val normalized = AppResolver.normalize(text)
        if (normalized.isBlank()) return Parsed.NotACall

        val isAnswer = answerVerbs.any(normalized::contains)
        val hasCallWord = "اتصل" in normalized || normalized.contains("call")
        if (!isAnswer && !hasCallWord) return Parsed.NotACall

        val dialIdx = dialVerbs.map { normalized.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        val contact = extractContact(normalized, dialIdx, isAnswer)
        if (contact.isBlank()) return Parsed.NotACall

        val messageOnly = messageMarkers.any(normalized::contains) && !actionMarkers.any(normalized::contains)
        val questions =
            if (messageOnly) {
                listOf("ماذا تحتاج؟")
            } else {
                extractQuestions(normalized)
            }
        val knowledge =
            if (messageOnly) {
                emptyList()
            } else {
                extractRelayItems(normalized)
            }

        if (questions.isEmpty() && knowledge.isEmpty()) return Parsed.DialOnly(contact)
        return Parsed.Task(
            CallTask(
                contactQuery = contact,
                goals = questions.map(::ConversationGoal),
                knowledgeToRelay = knowledge,
                mode = if (isAnswer) CallTask.Mode.ANSWER_POLICY else CallTask.Mode.OUTGOING,
            ),
        )
    }

    private fun extractContact(
        normalized: String,
        dialIdx: Int,
        isAnswer: Boolean,
    ): String {
        val start = if (dialIdx >= 0) dialIdx else 0
        var rest = normalized.substring(start)
        val verb = dialVerbs.filter { rest.startsWith(it) }.maxByOrNull { it.length }
        rest = verb?.let { rest.removePrefix(it) } ?: rest
        if (isAnswer && dialIdx < 0) {
            // "اذا اتصل احمد" → contact follows the verb inside the clause.
            answerVerbs.firstOrNull { rest.startsWith(it) }?.let { rest = rest.removePrefix(it) }
        }
        rest = rest.trim(' ', ':', '،', ',')
        // One leading preposition ("بأحمد") or clitic pronoun ("اتصل بي احمد") after the verb.
        rest = rest.removePrefix("بي").removePrefix("بيه").removePrefix("به").removePrefix("ب").removePrefix("ل").trim(' ')
        val cut = actionMarkers + messageMarkers + endTriggers
        var contact = rest
        cut.forEach { marker ->
            val idx = rest.indexOf(marker)
            if (idx in 0 until contact.length) contact = rest.substring(0, idx)
        }
        return contact.trim(' ', '،', ',').removeSuffix(" و").trim(' ')
            .takeIf { it.isNotBlank() && it.length <= 40 } ?: ""
    }

    /** Status-table questions in order of appearance, then nothing else (verbatim relays below). */
    private fun extractQuestions(normalized: String): List<String> =
        statusQuestions
            .filter { (pattern, _) -> normalized.contains(pattern) }
            .map { (_, canonical) -> canonical }
            .distinct()

    /** "وقل له إنني..." segments become relay knowledge, minus markers and trailing tails. */
    private fun extractRelayItems(normalized: String): List<String> {
        val items = mutableListOf<String>()
        val allMarkers = actionMarkers
        allMarkers.forEach { marker ->
            var idx = normalized.indexOf(marker)
            while (idx >= 0) {
                val rest = normalized.substring(idx + marker.length).trimStart(' ', ':', ' ')
                    .removePrefix("انني").removePrefix("اني").removePrefix("ان").removePrefix("that").trim(' ')
                val end = endTriggers.map { rest.indexOf(it) }.filter { it > 0 }.minOrNull() ?: rest.length
                val segment = rest.substring(0, end).trim(' ', '،', ',')
                if (segment.length > 2 && segment !in items && !statusQuestions.any { (p, _) -> p in segment }) {
                    items.add(segment)
                }
                idx = normalized.indexOf(marker, idx + marker.length)
            }
        }
        return items
    }
}
