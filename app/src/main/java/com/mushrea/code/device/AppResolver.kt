package com.mushrea.code.device

/**
 * One installed, launchable app as seen by the Device Agent's app resolver.
 */
data class AppEntry(
    val label: String,
    val packageName: String,
)

/**
 * The outcome of resolving a natural app name such as "youtube" or "يوتيوب" to a package.
 *
 * `confidence` mirrors the Context Confidence model (prompt section 10): [AppMatch.HIGH] means a
 * single unambiguous app, [AppMatch.MEDIUM] means a ranked favorite, [AppMatch.NONE] means the
 * agent should tell the user it could not find the app (and possibly list alternatives).
 */
data class AppResolution(
    val best: AppEntry?,
    val confidence: AppMatch,
    val alternatives: List<AppEntry>,
)

enum class AppMatch { HIGH, MEDIUM, NONE }

/**
 * Resolves a user-spoken/typed app name to an installed app without any network or AI call.
 *
 * Matching order (first hit wins, later stages rank weaker):
 * 1. exact package name
 * 2. exact label
 * 3. label starts with the query / query starts with the label
 * 4. label contains the query as a word
 * 5. all query words appear in the label (any order)
 *
 * Arabic labels are normalized before comparing (alef/ya/ta-marbuta folding, diacritics and
 * tatweel stripping) so "الأعداءات"-style variants still match their intended app.
 */
object AppResolver {

    fun resolve(
        query: String,
        entries: List<AppEntry>,
    ): AppResolution {
        val q = normalize(query)
        if (q.isEmpty() || entries.isEmpty()) {
            return AppResolution(best = null, confidence = AppMatch.NONE, alternatives = emptyList())
        }

        findExact(entries) { it.packageName.equals(query, ignoreCase = true) }?.let {
            return single(it)
        }
        findExact(entries) { normalize(it.label) == q }?.let {
            return single(it)
        }

        val starts = entries.filter { normalize(it.label).startsWith(q) || q.startsWith(normalize(it.label)) && normalize(it.label).isNotEmpty() }
        val containsWord = entries.filter { containsWord(normalize(it.label), q) }
        val allWords = entries.filter { entry ->
            val label = normalize(entry.label)
            q.split(' ').all { word -> word.isEmpty() || label.contains(word) }
        }

        val ranked =
            when {
                starts.isNotEmpty() -> starts
                containsWord.isNotEmpty() -> containsWord
                else -> allWords
            }
        if (ranked.isEmpty()) {
            return AppResolution(best = null, confidence = AppMatch.NONE, alternatives = emptyList())
        }
        return if (ranked.size == 1) {
            AppResolution(best = ranked.first(), confidence = AppMatch.HIGH, alternatives = emptyList())
        } else {
            // Prefer the shortest label: "YouTube" beats "YouTube Music" for the query "youtube" only
            // when it is shorter, which is the usual user intent.
            val best = ranked.minByOrNull { normalize(it.label).length } ?: ranked.first()
            AppResolution(
                best = best,
                confidence = AppMatch.MEDIUM,
                alternatives = ranked.filterNot { it == best }.take(MAX_ALTERNATIVES),
            )
        }
    }

    private fun single(entry: AppEntry) =
        AppResolution(best = entry, confidence = AppMatch.HIGH, alternatives = emptyList())

    private fun findExact(
        entries: List<AppEntry>,
        predicate: (AppEntry) -> Boolean,
    ): AppEntry? {
        val hits = entries.filter(predicate)
        return hits.singleOrNull() ?: hits.firstOrNull()
    }

    private fun containsWord(
        label: String,
        word: String,
    ): Boolean = label == word || label.startsWith("$word ") || label.contains(" $word ") || label.endsWith(" $word")

    /**
     * Lower-cases, collapses whitespace, and folds common Arabic orthography variants so that
     * queries like "اليوتيوب" still resolve like "يوتيوب".
     */
    fun normalize(value: String): String {
        var s = value.lowercase().trim().replace(WHITESPACE, " ")
        if (s.any { it in '٠'..'٩' }) {
            s = s.map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("")
        }
        return buildString(s.length) {
            for (ch in s) {
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                    'ة' -> append('ه')
                    'ى' -> append('ي')
                    'ئ', 'ؤ' -> append(ch)
                    '\u064B'..'\u065F', '\u0670', '\u0640' -> {
                        // diacritics + tatweel: drop
                    }
                    else -> append(ch)
                }
            }
        }.trim()
    }

    private val WHITESPACE = Regex("\\s+")

    private const val MAX_ALTERNATIVES = 5
}
