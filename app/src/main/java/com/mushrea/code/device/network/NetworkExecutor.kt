package com.mushrea.code.device.network

import android.content.Context
import android.net.wifi.WifiManager
import com.mushrea.code.core.util.safeMessage
import com.mushrea.code.device.usb.AdbException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Network layer of the device agent (user request: "طبقة الشبكة").
 *
 * Wi-Fi state, DNS resolution, ICMP ping, one-port TCP reachability, plain HTTP/HTTPS
 * requests and a short WebSocket window. Everything here targets hosts the user named
 * or the local network the phone joined - the agent-context file tells the model to use
 * these tools only for legitimate, authorized diagnostics.
 *
 * Wi-Fi details are read from the public WifiManager. Android hides the SSID/BSSID unless
 * the app holds location permission, so the result carries an honest note instead of a
 * fake network name when the system reports "<unknown ssid>".
 */
class NetworkExecutor(private val context: Context) {
    /** Wi-Fi state: enabled, ssid, ip/gateway from DHCP, rssi-derived signal level and band. */
    @Suppress("DEPRECATION")
    suspend fun executeWifiInfo(): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val manager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: throw AdbException("wi-fi manager is unavailable on this device")
            val info = manager.connectionInfo
            val dhcp = manager.dhcpInfo
            val rawSsid = info?.ssid?.removeSurrounding("\"").orEmpty()
            val ssidKnown = rawSsid.isNotBlank() && !rawSsid.contains("unknown", ignoreCase = true)
            return@withContext {
                put("wifi_enabled", manager.isWifiEnabled)
                put("connected", info != null && rawSsid.isNotBlank() && ssidKnown)
                put("ssid", if (ssidKnown) rawSsid else "")
                if (!ssidKnown) {
                    put(
                        "ssid_note",
                        "android hides the ssid without location permission - turn location on to see it",
                    )
                }
                if (info != null) {
                    put("bssid", info.bssid.orEmpty())
                    put("ip", intToIp(info.ipAddress))
                    put("rssi", info.rssi)
                    put("signal_level", WifiManager.calculateSignalLevel(info.rssi, 4))
                    put("link_speed_mbps", info.linkSpeed)
                    put("frequency_mhz", info.frequency)
                    put("band", bandOf(info.frequency))
                }
                if (dhcp != null) {
                    put("gateway", intToIp(dhcp.gateway))
                    put("netmask", intToIp(dhcp.netmask))
                    put("dhcp_dns", intToIp(dhcp.dns1))
                    put("lease_seconds", dhcp.leaseDuration)
                }
            }
        }

    /** Resolve a hostname to its IP addresses (A and AAAA together). */
    suspend fun executeDnsLookup(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val host = params.optString("host").trim()
            if (host.isBlank()) throw AdbException("host is required")
            val addresses =
                withTimeoutOrNull(10_000L) {
                    runCatching { InetAddress.getAllByName(host).toList() }
                        .getOrElse { t -> throw AdbException("dns lookup failed: " + t.safeMessage("unresolved host")) }
                } ?: throw AdbException("dns lookup timed out after 10 s")
            val entries = JSONArray()
            addresses.forEach { address ->
                entries.put(
                    JSONObject()
                        .put("address", address.hostAddress.orEmpty())
                        .put("version", if (address.hostAddress?.contains(':') == true) "ipv6" else "ipv4"),
                )
            }
            return@withContext {
                put("host", host)
                put("count", addresses.size)
                put("addresses", entries)
            }
        }

    /** ICMP ping through the system ping binary, with an honest TCP-echo fallback probe. */
    suspend fun executeNetPing(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val host = params.optString("host").trim()
            if (host.isBlank()) throw AdbException("host is required")
            val count = params.optInt("count", 4).coerceIn(1, 10)
            return@withContext runCatching { pingViaBinary(host, count) }.getOrElse { exec ->
                val reachable =
                    runCatching {
                        (1..count).any { InetAddress.getByName(host).isReachable(null, 0, 2_000) }
                    }.getOrDefault(false) {
                        put("host", host)
                        put("count", count)
                        put("icmp_available", false)
                        put("reachable_via_fallback", reachable)
                        put(
                            "note",
                            "the system ping binary is unavailable (" +
                                exec.safeMessage("exec failed") + "); the fallback uses tcp echo probes",
                        )
                    }
            }
        }

    /** One TCP connect() against a user-named host:port (authorized diagnostics only). */
    suspend fun executePortCheck(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val host = params.optString("host").trim()
            if (host.isBlank()) throw AdbException("host is required")
            val port = params.optInt("port", -1)
            if (port < 1 || port > 65_535) throw AdbException("port must be 1-65535")
            val seconds = params.optInt("seconds", 3).coerceIn(1, 10)
            var opened = false
            var detail = "connected"
            val started = System.nanoTime()
            try {
                Socket().use { socket -> socket.connect(InetSocketAddress(host, port), seconds * 1_000) }
                opened = true
            } catch (timeout: SocketTimeoutException) {
                detail = "timed out after ${seconds}s"
            } catch (io: IOException) {
                detail = io.safeMessage("connection refused")
            }
            val latencyMs = (System.nanoTime() - started) / 1_000_000
            return@withContext {
                put("host", host)
                put("port", port)
                put("open", opened)
                put("latency_ms", latencyMs)
                put("detail", detail)
            }
        }

    /** Plain HTTP/HTTPS request; the body is captured up to 64 KiB and flagged when longer. */
    suspend fun executeHttpRequest(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val url = params.optString("url").trim()
            if (url.isBlank()) throw AdbException("url is required")
            val method = params.optString("method", "GET").uppercase().ifBlank { "GET" }
            if (method !in setOf("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH")) {
                throw AdbException("method must be one of GET HEAD POST PUT DELETE PATCH")
            }
            val seconds = params.optInt("seconds", 15).coerceIn(2, 60)
            val headers = params.optJSONObject("headers") ?: JSONObject()
            val bodyText = params.optString("body")
            val client =
                OkHttpClient.Builder()
                    .connectTimeout(seconds.toLong(), TimeUnit.SECONDS)
                    .readTimeout(seconds.toLong(), TimeUnit.SECONDS)
                    .callTimeout(seconds.toLong(), TimeUnit.SECONDS)
                    .build()
            val builder =
                try {
                    Request.Builder().url(url)
                } catch (t: Throwable) {
                    throw AdbException("bad url: " + t.safeMessage("malformed"))
                }
            for (name in headers.keys()) builder.header(name, headers.optString(name))
            when (method) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                "DELETE" ->
                    if (bodyText.isNotBlank()) {
                        builder.method("DELETE", bodyText.toRequestBody(JSON_MEDIA_TYPE))
                    } else {
                        builder.delete()
                    }
                else -> builder.method(method, bodyText.toRequestBody(JSON_MEDIA_TYPE))
            }
            val call = client.newCall(builder.build())
            val response =
                runCatching { call.execute() }
                    .getOrElse { t -> throw AdbException("request failed: " + t.safeMessage("no connection")) }
            response.use { resp ->
                val headerLines = JSONArray()
                for (name in resp.headers.names()) {
                    headerLines.put(name + ": " + resp.headers.values(name).joinToString(", "))
                }
                val peeked = runCatching { resp.peekBody(MAX_BODY_BYTES) }.getOrNull()
                val peekedBytes = peeked?.bytes()
                val bodyOut = peekedBytes?.let { String(it, Charsets.UTF_8) }.orEmpty()
                val contentLength = resp.header("content-length")?.toLongOrNull() ?: -1L
                val truncated =
                    peekedBytes != null &&
                        (contentLength > peekedBytes.size || peekedBytes.size >= MAX_BODY_BYTES.toInt())
                return@use {
                    put("url", url)
                    put("code", resp.code)
                    put("message", resp.message)
                    put("protocol", resp.protocol.toString())
                    put("headers", headerLines)
                    put("body", bodyOut)
                    put("body_bytes", peekedBytes?.size ?: 0)
                    put("content_length", contentLength)
                    put("body_truncated", truncated)
                }
            }
        }

    /** Open a WebSocket, optionally send one message, collect replies for a short window. */
    suspend fun executeWebSocket(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val url = params.optString("url").trim()
            if (url.isBlank()) throw AdbException("url is required")
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                throw AdbException("websocket url must start with ws:// or wss://")
            }
            val seconds = params.optInt("seconds", 5).coerceIn(1, 30)
            val message = params.optString("message").ifBlank { null }
            val client =
                OkHttpClient.Builder()
                    .connectTimeout(seconds.toLong(), TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .build()
            val messages = Collections.synchronizedList(mutableListOf<String>())
            var opened = false
            var failure: String? = null
            var closedCode = -1
            val latch = CountDownLatch(1)
            val request = Request.Builder().url(url).build()
            val socket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            opened = true
                            if (message != null) webSocket.send(message)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            messages.add(text)
                            if (messages.size >= MAX_WS_MESSAGES) latch.countDown()
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            closedCode = code
                            latch.countDown()
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            failure = t.safeMessage("connection failed")
                            latch.countDown()
                        }
                    },
                )
            val elapsed = latch.await(seconds.toLong(), TimeUnit.SECONDS)
            if (opened) runCatching { socket.close(1000, "mushrea window elapsed") }
            socket.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            val snapshot = synchronized(messages) { messages.toList().take(MAX_WS_MESSAGES) }
            val replies = JSONArray()
            snapshot.forEach { replies.put(it) }
            return@withContext {
                put("url", url)
                put("connected", opened)
                put("sent", message != null)
                put("window_elapsed", !elapsed)
                put("messages_count", snapshot.size)
                put("messages", replies)
                put("closed_code", closedCode)
                if (failure != null) put("failure", failure)
            }
        }

    private fun pingViaBinary(
        host: String,
        count: Int,
    ): JSONObject.() -> Unit {
        val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", count.toString(), "-W", "2", host))
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        try {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines -> lines.forEach { stdout.appendLine(it) } }
            BufferedReader(InputStreamReader(process.errorStream)).useLines { lines -> lines.forEach { stderr.appendLine(it) } }
            val finished = process.waitFor((count * 3L + 5L), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return {
                    put("host", host)
                    put("count", count)
                    put("timed_out", true)
                    put("output", clip(stdout.toString()))
                }
            }
        } finally {
            runCatching { process.destroy() }
        }
        val out = stdout.toString()
        val summary = LOSS_REGEX.find(out)
        val rtt = RTT_REGEX.find(out)
        val replyTimes = TIME_REGEX.findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        return {
            put("host", host)
            put("count", count)
            put("exit_code", process.exitValue())
            if (summary != null) {
                put("transmitted", summary.groupValues[1].toInt())
                put("received", summary.groupValues[2].toInt())
                put("loss_percent", summary.groupValues[3].toDoubleOrNull() ?: -1.0)
            }
            if (rtt != null) {
                put("min_ms", rtt.groupValues[1].toDoubleOrNull() ?: -1.0)
                put("avg_ms", rtt.groupValues[2].toDoubleOrNull() ?: -1.0)
                put("max_ms", rtt.groupValues[3].toDoubleOrNull() ?: -1.0)
            } else if (replyTimes.isNotEmpty()) {
                put("avg_ms", replyTimes.sorted()[replyTimes.size / 2])
            }
            put("output", clip(out + (if (stderr.isNotBlank()) stderr.toString() else "")))
        }
    }

    private fun bandOf(frequencyKhz: Int): String =
        when {
            frequencyKhz in 2_400..2_500 -> "2.4 GHz"
            frequencyKhz in 4_900..5_900 -> "5 GHz"
            frequencyKhz >= 5_900 -> "6 GHz"
            else -> "unknown"
        }

    private fun intToIp(address: Int): String =
        arrayOf(address, address shr 8, address shr 16, address shr 24)
            .joinToString(".") { (it and 0xFF).toString() }

    private fun clip(
        text: String,
        limit: Int = 4_000,
    ): String = if (text.length <= limit) text else text.substring(text.length - limit)

    private companion object {
        const val MAX_BODY_BYTES = 64L * 1024
        const val MAX_WS_MESSAGES = 50
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val LOSS_REGEX = Regex("(\\d+) packets transmitted, (\\d+) received[^%]*?(\\d+(?:\\.\\d+)?)% packet loss")
        val RTT_REGEX = Regex("(?:rtt|round-trip) min/avg/max(?:/mdev)? = ([\\d.]+)/([\\d.]+)/([\\d.]+)")
        val TIME_REGEX = Regex("time=([\\d.]+) ms")
    }
}
