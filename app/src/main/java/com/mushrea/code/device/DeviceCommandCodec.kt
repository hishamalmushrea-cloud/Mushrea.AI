package com.mushrea.code.device

import org.json.JSONObject

/**
 * One device command dropped by the agent into `<workspace>/.mushrea-code/device-command.json`
 * (the same file-channel pattern the guest browser MCP uses).
 */
data class DeviceCommand(
    val id: String,
    val action: String,
    val params: JSONObject,
    val requestedAtMillis: Long,
)

/**
 * Parses device commands and encodes device results.
 *
 * Request: `{"id":"c1","action":"open_app","params":{"app":"youtube"},"ts":1720000000000}`
 * Result:  `{"id":"c1","ok":true,"result":{...}}` or
 *          `{"id":"c1","ok":false,"error":"…","needs_confirmation":true}`
 *
 * Pure logic so the protocol is unit-testable without Android.
 */
object DeviceCommandCodec {

    fun parseRequest(text: String): DeviceCommand? =
        runCatching {
            val root = JSONObject(text)
            val id = root.optString("id")
            val action = root.optString("action")
            if (id.isBlank() || action.isBlank()) return null
            DeviceCommand(
                id = id,
                action = action.lowercase(),
                params = root.optJSONObject("params") ?: JSONObject(),
                requestedAtMillis = root.optLong("ts", System.currentTimeMillis()),
            )
        }.getOrNull()

    fun success(
        id: String,
        payload: JSONObject.() -> Unit = {},
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("ok", true)
            put("result", JSONObject().apply(payload))
        }

    fun failure(
        id: String,
        error: String,
        needsConfirmation: Boolean = false,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("ok", false)
            put("error", error)
            if (needsConfirmation) put("needs_confirmation", true)
        }
}
