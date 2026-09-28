package com.mushrea.code.device

/**
 * A single visible UI element captured from the Accessibility tree.
 *
 * `index` is the element's position inside the [ScreenSnapshot] it came from; the agent refers to
 * elements by this index (or by a text query) and the engine re-locates the live node before
 * acting, so stale snapshots never tap the wrong thing.
 */
data class ScreenElement(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewIdResourceName: String,
    val boundsInScreen: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
) {
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2

        override fun toString(): String = "[$left,$top][$right,$bottom]"
    }

    /** The most human-readable label available for this element. */
    val label: String
        get() = text.ifBlank { contentDescription }.ifBlank { shortClassName }

    val shortClassName: String
        get() = className.substringAfterLast('.').ifBlank { className }
}

/**
 * A compact picture of what is currently on screen (prompt sections 8 and 15).
 */
data class ScreenSnapshot(
    val packageName: String,
    val activity: String?,
    val elements: List<ScreenElement>,
    val truncated: Boolean,
)

/**
 * Formats snapshots for the agent and finds elements inside a snapshot.
 *
 * Pure logic — the Accessibility engine converts live nodes into plain [ScreenElement]s, everything
 * after that (matching, ranking, prompt text) is testable on the JVM without a device.
 */
object ScreenSnapshotFormatter {
    /** Renders a snapshot the way it is handed to the LLM (prompt section 8 example). */
    fun toPromptText(snapshot: ScreenSnapshot): String =
        buildString {
            append("Current app: ")
            append(snapshot.packageName)
            snapshot.activity?.let {
                append("\nCurrent screen: ")
                append(it)
            }
            append('\n')
            if (snapshot.elements.isEmpty()) {
                append("Visible elements: (none accessible)")
                return@buildString
            }
            append("Visible elements:\n")
            for (element in snapshot.elements) {
                append(element.index)
                append(". ")
                append(element.label.ifBlank { element.shortClassName })
                val flags =
                    buildList {
                        if (element.clickable) add("clickable")
                        if (element.editable) add("editable")
                        if (element.scrollable) add("scrollable")
                    }
                if (flags.isNotEmpty()) {
                    append("  [")
                    append(flags.joinToString(", "))
                    append(']')
                }
                if (element.viewIdResourceName.isNotBlank()) {
                    append("  id=")
                    append(element.viewIdResourceName)
                }
                append("  ")
                append(element.boundsInScreen)
                append('\n')
            }
            if (snapshot.truncated) {
                append("(element list truncated)\n")
            }
        }

    /**
     * Finds elements matching a natural query such as "بحث", "Search", "id:com.youtube.search"
     * or "#12". Ranking: exact label → label prefix → label contains → substring; editable fields
     * additionally match by class name (EditText / حقل إدخال).
     */
    fun find(
        snapshot: ScreenSnapshot,
        query: String,
    ): List<ScreenElement> {
        val q = AppResolver.normalize(query)
        if (q.isEmpty()) return emptyList()
        val indexQuery = query.trim().removePrefix("#").toIntOrNull()
        snapshot.elements
            .firstOrNull { it.index == indexQuery }
            ?.let { return listOf(it) }
        val expansion = ScreenSearch.expand(q)
        return snapshot.elements
            .map { it to score(it, q, expansion) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ScreenElement, Int>> { it.second }.thenBy { it.first.index })
            .map { it.first }
    }

    /** Direct label score plus concept-sibling and role bonuses from [ScreenSearch]. */
    private fun score(
        element: ScreenElement,
        q: String,
        expansion: ScreenSearch.Expansion,
    ): Int {
        var best = labelScore(element, q)
        for (term in expansion.terms) {
            val s = labelScore(element, term).coerceAtMost(50)
            if (s > best) best = s
        }
        if (best == 0) return 0
        var bonus = 0
        expansion.wantClickable?.let { if (element.clickable == it) bonus += 15 }
        expansion.wantEditable?.let { if (element.editable == it) bonus += 15 }
        return best + bonus
    }

    private fun labelScore(
        element: ScreenElement,
        q: String,
    ): Int {
        val labels =
            buildList {
                add(AppResolver.normalize(element.text))
                add(AppResolver.normalize(element.contentDescription))
                element.viewIdResourceName?.let { add(it.substringAfterLast('/').lowercase()) }
                add(AppResolver.normalize(element.shortClassName))
            }.filter(String::isNotBlank)
        var best = 0
        for (label in labels) {
            val s =
                when {
                    label == q -> 100
                    label.startsWith(q) -> 80
                    label.contains(" $q ") || label.startsWith("$q ") || label.endsWith(" $q") -> 60
                    label.contains(q) -> 40
                    q.length >= 3 && label.contains(q.substring(0, q.length / 2)) -> 10
                    else -> 0
                }
            if (s > best) best = s
        }
        return best
    }
}
