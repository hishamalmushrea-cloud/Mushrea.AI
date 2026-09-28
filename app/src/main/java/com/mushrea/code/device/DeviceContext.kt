package com.mushrea.code.device

import org.json.JSONArray
import org.json.JSONObject

/**
 * Context Confidence (prompt section 10): how sure the agent is about what "this" refers to.
 *
 * HIGH   — one unambiguous match; act on it.
 * MEDIUM — a preferred interpretation exists but it is not certain; act, and say what was picked.
 * LOW    — nothing, or too many plausible candidates; ask the user instead of guessing.
 */
enum class ContextConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * Heuristics that turn a match result into a confidence level. Pure logic so the agent-facing
 * behavior is testable off-device.
 */
object ContextConfidenceHeuristics {
    /**
     * @param matchCount number of elements/files that matched the user's reference
     * @param askedWithQuery true when the user actually pointed at something ("اضغط بحث"),
     *   false when the action had no reference at all
     */
    fun fromMatchCount(
        matchCount: Int,
        askedWithQuery: Boolean,
    ): ContextConfidence =
        when {
            !askedWithQuery -> ContextConfidence.HIGH // nothing was referenced; no ambiguity to resolve
            matchCount == 1 -> ContextConfidence.HIGH
            matchCount in 2..MAX_PREFERRED -> ContextConfidence.MEDIUM
            matchCount > MAX_PREFERRED -> ContextConfidence.LOW
            else -> ContextConfidence.LOW
        }

    const val MAX_PREFERRED = 3
}

/**
 * The Device Agent's running context (prompt sections 5, 8, 9, 16, 22): what app the user is in,
 * what the current task is, the last file the agent touched, and the recent app trail — the state
 * that lets "هذا / أرسله / افتحه" resolve to the right thing, and that survives app changes while
 * a task is in flight.
 *
 * The app keeps this file (`.mushrea-code/device-context.json` in the active workspace) fresh, and
 * the agent reads it directly with the `device_get_context` tool — no command round-trip needed.
 */
data class DeviceContext(
    val currentApp: String,
    val currentActivity: String?,
    val currentTask: String,
    val lastFile: String?,
    val recentApps: List<String>,
    val updatedAtMillis: Long,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put(KEY_APP, currentApp)
            .put(KEY_ACTIVITY, currentActivity ?: "")
            .put(KEY_TASK, currentTask)
            .put(KEY_LAST_FILE, lastFile ?: "")
            .put(KEY_RECENT_APPS, JSONArray().apply { recentApps.forEach { put(it) } })
            .put(KEY_UPDATED, updatedAtMillis)

    companion object {
        const val KEY_APP = "current_app"
        const val KEY_ACTIVITY = "current_activity"
        const val KEY_TASK = "current_task"
        const val KEY_LAST_FILE = "last_file"
        const val KEY_RECENT_APPS = "recent_apps"
        const val KEY_UPDATED = "updated_at"
        const val MAX_RECENT_APPS = 8

        val EMPTY =
            DeviceContext(
                currentApp = "",
                currentActivity = null,
                currentTask = "",
                lastFile = null,
                recentApps = emptyList(),
                updatedAtMillis = 0,
            )

        fun fromJson(text: String): DeviceContext? =
            runCatching {
                val root = JSONObject(text)
                val recent =
                    buildList {
                        val arr = root.optJSONArray(KEY_RECENT_APPS) ?: JSONArray()
                        for (i in 0 until arr.length()) arr.optString(i)?.takeIf(String::isNotBlank)?.let { add(it) }
                    }
                DeviceContext(
                    currentApp = root.optString(KEY_APP),
                    currentActivity = root.optString(KEY_ACTIVITY).ifBlank { null },
                    currentTask = root.optString(KEY_TASK),
                    lastFile = root.optString(KEY_LAST_FILE).ifBlank { null },
                    recentApps = recent,
                    updatedAtMillis = root.optLong(KEY_UPDATED, 0),
                )
            }.getOrNull()
    }
}

/**
 * Pure reducer producing the next context state — used by the store and the accessibility service
 * so every writer applies identical rules (dedupe of consecutive same-app pushes, bounded trail).
 */
object DeviceContextReducer {
    fun withApp(
        before: DeviceContext,
        app: String,
        activity: String?,
        nowMillis: Long,
    ): DeviceContext {
        if (app.isBlank()) return before
        val trail =
            if (before.currentApp.isNotBlank() && before.currentApp != app) {
                (listOf(before.currentApp) + before.recentApps).distinct().take(DeviceContext.MAX_RECENT_APPS)
            } else {
                before.recentApps
            }
        return before.copy(
            currentApp = app,
            currentActivity = activity?.ifBlank { null },
            recentApps = trail,
            updatedAtMillis = nowMillis,
        )
    }

    fun withTask(
        before: DeviceContext,
        task: String,
        nowMillis: Long,
    ): DeviceContext = before.copy(currentTask = task.trim(), updatedAtMillis = nowMillis)

    fun withLastFile(
        before: DeviceContext,
        path: String,
        nowMillis: Long,
    ): DeviceContext = before.copy(lastFile = path, updatedAtMillis = nowMillis)
}
