package com.mushrea.code.device.network

import com.mushrea.code.device.usb.AdbException

/**
 * Pure helpers for the network tools: ping-output parsing, HTTP method checks, WebSocket URL
 * shape, IPv4 formatting and Wi-Fi band labels. The executor still talks to the platform; these
 * rules are what a unit test can pin without a radio.
 */
object NetworkDiagnostics {
    data class PingStats(
        val transmitted: Int? = null,
        val received: Int? = null,
        val lossPercent: Double? = null,
        val minMs: Double? = null,
        val avgMs: Double? = null,
        val maxMs: Double? = null,
        val sampleMs: List<Double> = emptyList(),
    )

    fun parsePingOutput(output: String): PingStats {
        val summary = LOSS_REGEX.find(output)
        val rtt = RTT_REGEX.find(output)
        val samples = TIME_REGEX.findAll(output).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        return PingStats(
            transmitted = summary?.groupValues?.get(1)?.toIntOrNull(),
            received = summary?.groupValues?.get(2)?.toIntOrNull(),
            lossPercent = summary?.groupValues?.get(3)?.toDoubleOrNull(),
            minMs = rtt?.groupValues?.get(1)?.toDoubleOrNull(),
            avgMs = rtt?.groupValues?.get(2)?.toDoubleOrNull() ?: samples.medianOrNull(),
            maxMs = rtt?.groupValues?.get(3)?.toDoubleOrNull(),
            sampleMs = samples,
        )
    }

    fun httpMethod(raw: String): String {
        val method = raw.uppercase().ifBlank { "GET" }
        if (method !in HTTP_METHODS) {
            throw AdbException("method must be one of GET HEAD POST PUT DELETE PATCH")
        }
        return method
    }

    fun requireWebSocketUrl(url: String) {
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            throw AdbException("websocket url must start with ws:// or wss://")
        }
    }

    fun requireHost(host: String): String {
        val trimmed = host.trim()
        if (trimmed.isBlank()) throw AdbException("host is required")
        return trimmed
    }

    fun requirePort(port: Int): Int {
        if (port < 1 || port > 65_535) throw AdbException("port must be 1-65535")
        return port
    }

    fun bandOf(frequencyMhz: Int): String =
        when {
            frequencyMhz in 2_400..2_500 -> "2.4 GHz"
            frequencyMhz in 4_900..5_900 -> "5 GHz"
            frequencyMhz >= 5_900 -> "6 GHz"
            else -> "unknown"
        }

    fun intToIpv4(address: Int): String =
        arrayOf(address, address shr 8, address shr 16, address shr 24)
            .joinToString(".") { (it and 0xFF).toString() }

    fun clip(
        text: String,
        limit: Int = 4_000,
    ): String = if (text.length <= limit) text else text.substring(text.length - limit)

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        return sorted[sorted.size / 2]
    }

    private val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH")
    private val LOSS_REGEX = Regex("""(\d+) packets transmitted, (\d+) received[^%]*?(\d+(?:\.\d+)?)% packet loss""")
    private val RTT_REGEX = Regex("""(?:rtt|round-trip) min/avg/max(?:/mdev)? = ([\d.]+)/([\d.]+)/([\d.]+)""")
    private val TIME_REGEX = Regex("""time=([\d.]+) ms""")
}
