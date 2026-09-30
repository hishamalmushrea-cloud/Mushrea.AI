package com.mushrea.code.feature.chat

/** Longest text handed to the speech engine: past this the read is capped and says so. */
private const val MAX_SPEECH_CHARS = 1200

private val FENCED_CODE_BLOCK = Regex("(?s)```.*?```")
private val UNTERMINATED_CODE_BLOCK = Regex("(?s)```.*$")
private val INLINE_CODE = Regex("`([^`]+)`")
private val MARKDOWN_LINK = Regex("!?\\[([^\\]]*)]\\([^)\\s]*\\)")
private val BARE_URL = Regex("https?://\\S+")
private val HEADING_MARKER = Regex("(?m)^\\s*#{1,6}\\s*")
private val BULLET_MARKER = Regex("(?m)^\\s*[-*•+]\\s+")
private val DECORATION_MARKS = Regex("[*_~|]")
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Turns markdown-flavoured chat text into something comfortable to hear.
 *
 * Code blocks collapse into a one-word placeholder (nobody wants identifiers read aloud), links
 * keep only their label, bare URLs are dropped, and emphasis, heading, and bullet decorations are
 * stripped. The result is capped at [MAX_SPEECH_CHARS] characters so a huge reply cannot hold the
 * engine for minutes; the cut is announced with [truncationMark].
 */
internal fun textForSpeech(
    raw: String,
    codePlaceholder: String = "code snippet",
    truncationMark: String = "…",
): String {
    if (raw.isBlank()) return ""
    val spoken =
        raw
            .replace(FENCED_CODE_BLOCK, " $codePlaceholder. ")
            .replace(UNTERMINATED_CODE_BLOCK, " $codePlaceholder. ")
            .replace(MARKDOWN_LINK, "$1")
            .replace(BARE_URL, " ")
            .replace(INLINE_CODE, "$1")
            .replace(HEADING_MARKER, "")
            .replace(BULLET_MARKER, "")
            .replace(DECORATION_MARKS, "")
            .replace(WHITESPACE_RUN, " ")
            .trim()
    if (spoken.length <= MAX_SPEECH_CHARS) return spoken
    return spoken.take(MAX_SPEECH_CHARS).trimEnd() + " " + truncationMark
}
