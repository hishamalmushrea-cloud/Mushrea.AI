package com.mushrea.code.device.usb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent surface for the other-phone-over-USB feature: list attached ADB phones (AUTO read)
 * and run a shell command on one (CONFIRM — the user sees the exact command in the approval
 * notification). Output is reported verbatim; destructive shell commands are never suggested
 * unless the user asked for them explicitly.
 */
class UsbExecutor(private val context: Context) {
    private val agent by lazy { UsbDeviceAgent(context) }

    fun executeDevices(): JSONObject.() -> Unit {
        val devices = agent.adbDevices()
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject()
                    .put("name", device.deviceName)
                    .put("product", device.productName ?: JSONObject.NULL)
                    .put("vendor_id", device.vendorId)
                    .put("product_id", device.productId)
                    .put("has_permission", agent.hasPermission(device)),
            )
        }
        return {
            put("devices", array)
            put(
                "summary",
                if (devices.isEmpty()) {
                    "no ADB phone attached — connect one with an OTG cable and enable USB debugging on it"
                } else {
                    "${devices.size} ADB phone(s) attached"
                },
            )
        }
    }

    suspend fun executeShell(params: JSONObject): JSONObject.() -> Unit {
        val command = params.optString("command").trim()
        if (command.isEmpty()) throw AdbException("command is required")
        val device =
            agent.adbDevices().firstOrNull()
                ?: throw AdbException("no ADB phone attached — connect one and enable USB debugging on it")
        if (!agent.hasPermission(device)) agent.ensurePermission(device)
        if (!agent.hasPermission(device)) throw AdbException("USB permission not granted — the user must accept the dialog on this phone")
        val output = agent.shell(device, command)
        val firstLine = output.lineSequence().firstOrNull()?.take(120).orEmpty().ifEmpty { "(no output)" }
        return {
            put("command", command)
            put("output", if (output.length > 8000) output.takeLast(8000) else output)
            put("summary", "ran on the other phone — first line: $firstLine")
        }
    }
}
