package com.mushrea.code.device

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic navigation recipes over the accessibility engine (spec: common flows must not
 * depend on the model re-planning every tap). A recipe composes the same audited primitives the
 * bridge exposes one command at a time, and is honest about partial failure: "submitted" is only
 * true when the IME action was actually delivered, and a scroll search that exhausts its budget
 * reports the miss instead of pretending success.
 */
class DeviceNavigator(
    private val context: Context,
    private val engine: MushreaCodeAccessibilityService.Engine,
) {

    /** Finds the search affordance ("بحث"/"search" via ScreenSearch), taps it, types, optionally submits. */
    suspend fun executeSearchAndType(params: JSONObject): JSONObject.() -> Unit {
        val text = params.optString("text").ifBlank { throw DeviceFileAgent.DeviceAgentError("text is required") }
        val submit = params.optBoolean("submit", true)
        val query = params.optString("query").ifBlank { "search" }
        val hits = ScreenSnapshotFormatter.find(readScreen(), query)
        val field =
            hits.firstOrNull { it.editable }
                ?: hits.firstOrNull()
                ?: throw DeviceFileAgent.DeviceAgentError("no search element found for \"$query\"")
        if (!tapElement(field)) throw DeviceFileAgent.DeviceAgentError("could not tap \"${field.label}\"")
        if (params.optBoolean("clear", true)) withContext(Dispatchers.Main) { engine.clearFocused() }
        val typed = withContext(Dispatchers.Main) { engine.typeIntoFocused(text) }
        if (!typed) throw DeviceFileAgent.DeviceAgentError("no focused text field after tapping \"${field.label}\"")
        val submitted =
            if (!submit) {
                false
            } else {
                withContext(Dispatchers.Main) { engine.sendImeEnter() } ||
                    withContext(Dispatchers.Main) { engine.typeIntoFocused("\n") }
            }
        return {
            put("target", field.label)
            put("submitted", submitted)
            put(
                "summary",
                "typed ${text.length} character(s) into \"${field.label}\"" +
                    when {
                        !submit -> ""
                        submitted -> " and submitted"
                        else -> "; submit not delivered (no IME action available)"
                    },
            )
        }
    }

    /** Scrolls down until [query] is visible or [maxSwipes] is spent; optionally taps the hit. */
    suspend fun executeScrollUntilFound(params: JSONObject): JSONObject.() -> Unit {
        val query = params.optString("query").ifBlank { throw DeviceFileAgent.DeviceAgentError("query is required") }
        val maxSwipes = params.optInt("max_swipes", DEFAULT_MAX_SWIPES).coerceIn(1, 20)
        val tapFound = params.optBoolean("tap", false)
        var swipes = 0
        var hits: List<ScreenElement> = emptyList()
        while (swipes <= maxSwipes) {
            hits = ScreenSnapshotFormatter.find(readScreen(), query)
            if (hits.isNotEmpty() || swipes == maxSwipes) break
            if (!scrollDown()) throw DeviceFileAgent.DeviceAgentError("could not scroll — nothing scrollable on screen")
            swipes++
            delay(SCROLL_SETTLE_MILLIS)
        }
        if (hits.isEmpty()) throw DeviceFileAgent.DeviceAgentError("no element matching \"$query\" after $swipes swipe(s)")
        var tapped: String? = null
        if (tapFound && tapElement(hits.first())) tapped = hits.first().label
        return {
            put("swipes", swipes)
            put(
                "elements",
                JSONArray().apply {
                    hits.take(5).forEach { el -> put(JSONObject().put("index", el.index).put("label", el.label)) }
                },
            )
            tapped?.let { put("tapped", it) }
            put("summary", "found \"${hits.first().label}\" after $swipes swipe(s)" + if (tapped != null) ", tapped it" else "")
        }
    }

    /** Polls fresh snapshots until [query] appears or the timeout passes; optionally taps the hit. */
    suspend fun executeWaitForElement(params: JSONObject): JSONObject.() -> Unit {
        val query = params.optString("query").ifBlank { throw DeviceFileAgent.DeviceAgentError("query is required") }
        val timeout = params.optLong("timeout_ms", DEFAULT_TIMEOUT_MILLIS).coerceIn(500L, 30000L)
        val deadline = System.currentTimeMillis() + timeout
        var hits: List<ScreenElement>
        while (true) {
            hits = ScreenSnapshotFormatter.find(readScreen(), query)
            if (hits.isNotEmpty()) break
            if (System.currentTimeMillis() >= deadline) {
                throw DeviceFileAgent.DeviceAgentError("\"$query\" did not appear within ${timeout}ms")
            }
            delay(POLL_INTERVAL_MILLIS)
        }
        var tapped: String? = null
        if (params.optBoolean("tap", false) && tapElement(hits.first())) tapped = hits.first().label
        return {
            put(
                "elements",
                JSONArray().apply {
                    hits.take(5).forEach { el -> put(JSONObject().put("index", el.index).put("label", el.label)) }
                },
            )
            tapped?.let { put("tapped", it) }
            put("summary", "\"${hits.first().label}\" is on screen" + if (tapped != null) ", tapped it" else "")
        }
    }

    private suspend fun readScreen(): ScreenSnapshot =
        withContext(Dispatchers.Main) { engine.readScreen() }
            ?: throw DeviceFileAgent.DeviceAgentError("cannot read the screen — enable Mushrea Code in Accessibility settings")

    private suspend fun tapElement(element: ScreenElement): Boolean =
        withContext(Dispatchers.Main) {
            val node = engine.locateNode(element)
            (node != null && engine.clickNode(node)) ||
                engine.tap(element.boundsInScreen.centerX.toFloat(), element.boundsInScreen.centerY.toFloat())
        }

    /** One scroll step: the scrollable node's action when present, a fling down otherwise. */
    private suspend fun scrollDown(): Boolean =
        withContext(Dispatchers.Main) {
            val snapshot = engine.readScreen() ?: return@withContext false
            val scrollable = snapshot.elements.firstOrNull { it.scrollable }?.let { engine.locateNode(it) }
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } else {
                val metrics = context.resources.displayMetrics
                val cx = metrics.widthPixels / 2f
                engine.swipe(cx, metrics.heightPixels / 3f, cx, metrics.heightPixels * 2f / 3f, SCROLL_DURATION_MILLIS)
            }
        }

    private companion object {
        const val DEFAULT_MAX_SWIPES = 8
        const val DEFAULT_TIMEOUT_MILLIS = 5000L
        const val SCROLL_SETTLE_MILLIS = 600L
        const val SCROLL_DURATION_MILLIS = 250L
        const val POLL_INTERVAL_MILLIS = 400L
    }
}
