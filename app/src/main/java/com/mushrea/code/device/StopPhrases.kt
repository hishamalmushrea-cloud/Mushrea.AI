package com.mushrea.code.device

/**
 * Recognizes emergency-stop utterances (prompt section 36) so the voice/assistant path can halt the
 * Device Agent immediately — without a round-trip through the LLM.
 *
 * Matching is deliberately conservative: short, unambiguous stop phrases in Arabic and English,
 * optionally preceded by the wake word ("hey mushrea stop" / "يا مشيرة توقف"). Sentences like
 * "stop the music" never match, so they still reach the agent as normal prompts.
 */
object StopPhrases {
    /** Maximum words a bare stop phrase may have ("الغ الأمر", "stop agent"). */
    private const val MAX_PHRASE_WORDS = 2

    /** Wake-word-ish prefixes that may precede a stop phrase ("hey mushrea stop"). */
    private const val MAX_PREFIX_WORDS = 2

    private val phrases =
        setOf(
            // Arabic
            "توقف",
            "وقف",
            "أوقف",
            "اوقف",
            "توقفي",
            "وقفى",
            "كفى",
            "الغاء",
            "إلغاء",
            "الغ الأمر",
            "إلغاء الأمر",
            // English
            "stop",
            "stop agent",
            "stop it",
            "stop now",
            "cancel",
            "abort",
        )

    private val wakePrefixes = setOf("hey", "ok", "mushrea", "mushreacode", "يا", "مشيرة", "مشيره")

    /**
     * Phrases and inputs both go through [AppResolver.normalize] (alef folding, diacritics), plus
     * this pass drops the hamza seats (ؤ ئ ء) so orthography variants like "اؤوقف" or
     * "الغاء الأمر" still match without hard-coding every spelling.
     */
    private val foldedPhrases = phrases.map { fold(it) }.toSet()

    private fun fold(text: String): String = AppResolver.normalize(text).replace("ؤ", "").replace("ئ", "").replace("ء", "")

    /** True when [text] is a plain stop command in Arabic or English, with optional wake prefix. */
    fun isStopCommand(text: String): Boolean {
        val normalized = fold(text)
        if (normalized.isEmpty()) return false
        val words = normalized.split(' ')
        if (words.size > MAX_PHRASE_WORDS + MAX_PREFIX_WORDS) return false
        if (normalized in foldedPhrases) return true
        val stripped = words.dropWhile { it in wakePrefixes }
        if (stripped.isEmpty() || stripped.size == words.size && words.size > MAX_PHRASE_WORDS) {
            // Nothing stripped and the raw text already missed the phrase set above.
            return false
        }
        return stripped.joinToString(" ") in foldedPhrases
    }
}
