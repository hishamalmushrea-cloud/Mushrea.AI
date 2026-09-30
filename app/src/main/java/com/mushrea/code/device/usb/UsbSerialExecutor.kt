package com.mushrea.code.device.usb

import android.content.Context
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * The agent surface for the serial feature: send a line to a board (CONFIRM) and collect what it
 * prints back (AUTO read) — the embedded twin of a serial monitor, driven in natural language
 * ("أرسل for uint8_t للوحة" أو "اقرأ ما تطبعه الحساسة").
 */
class UsbSerialExecutor(private val context: Context) {
    private val agent by lazy { UsbSerialAgent(context) }

    suspend fun executeSend(params: JSONObject): JSONObject.() -> Unit {
        val text = params.optString("text")
        if (text.isEmpty()) throw AdbException("text is required")
        val baudrate = params.optInt("baudrate", 115200).coerceIn(300, 3_000_000)
        val newline = params.optBoolean("newline", true)
        val driver =
            agent.ports().firstOrNull()
                ?: throw AdbException("no USB serial device attached (Arduino/ESP32/serial adapter)")
        val payload = (if (newline) text + "\n" else text).toByteArray(Charsets.UTF_8)
        agent.withPort(driver, baudrate) { port -> port.write(payload, WRITE_TIMEOUT_MILLIS) }
        return {
            put("bytes", payload.size)
            put("baudrate", baudrate)
            put("summary", "sent ${payload.size} byte(s) at $baudrate baud to the serial device")
        }
    }

    suspend fun executeRead(params: JSONObject): JSONObject.() -> Unit {
        val baudrate = params.optInt("baudrate", 115200).coerceIn(300, 3_000_000)
        val milliseconds = params.optInt("milliseconds", 1000).coerceIn(100, 10_000)
        val driver =
            agent.ports().firstOrNull()
                ?: throw AdbException("no USB serial device attached (Arduino/ESP32/serial adapter)")
        val bytes =
            agent.withPort(driver, baudrate) { port ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                val deadline = System.currentTimeMillis() + milliseconds
                while (output.size() < MAX_BYTES) {
                    val remaining = (deadline - System.currentTimeMillis()).toInt()
                    if (remaining <= 0) break
                    val count =
                        runCatching { port.read(buffer, remaining.coerceAtMost(500)) }.getOrDefault(-1)
                    if (count > 0) output.write(buffer, 0, count) else break
                }
                output.toByteArray()
            }
        val text = String(bytes, Charsets.UTF_8)
        return {
            put("bytes", bytes.size)
            put("baudrate", baudrate)
            put("text", text.take(4000))
            put(
                "summary",
                if (bytes.isEmpty()) {
                    "no data arrived in ${milliseconds}ms — check the baudrate and wiring"
                } else {
                    "received ${bytes.size} byte(s) from the serial device"
                },
            )
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val MAX_BYTES = 8192
    }
}
