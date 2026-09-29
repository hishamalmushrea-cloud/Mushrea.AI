package com.mushrea.code.device

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject

/**
 * Read-only device status behind the firewall (AUTO): battery, charging, network, ringer and
 * screen-lock state, plus the channel ping the readiness probe uses to prove the whole command
 * path (write → inotify wake → process → result) is alive end to end.
 */
class DeviceStatusAgent(private val context: Context) {
    fun executePing(): JSONObject.() -> Unit {
        val atMillis = System.currentTimeMillis()
        return {
            put("pong", true)
            put("at_millis", atMillis)
            put("summary", "pong")
        }
    }

    fun executeStatus(): JSONObject.() -> Unit {
        val battery = readBattery()
        val network = readNetwork()
        val ringer =
            runCatching {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
            }.getOrDefault("unknown")
        val screenOn =
            runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }
                .getOrDefault(false)
        val keyguardLocked =
            runCatching { (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked }
                .getOrDefault(false)
        return {
            put("battery_pct", battery.first)
            put("charging", battery.second)
            put("power_source", battery.third)
            put("network", network)
            put("ringer", ringer)
            put("screen_on", screenOn)
            put("keyguard_locked", keyguardLocked)
            put(
                "summary",
                "battery ${battery.first}%${if (battery.second) " (charging)" else ""}, network $network, ringer $ringer, " +
                    "screen ${if (screenOn) "on" else "off"}, ${if (keyguardLocked) "locked" else "unlocked"}",
            )
        }
    }

    private fun readBattery(): Triple<Int, Boolean, String> =
        runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val source =
                when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> "battery"
                }
            Triple(pct, charging, source)
        }.getOrDefault(Triple(-1, false, "unknown"))

    private fun readNetwork(): String =
        runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            when {
                capabilities == null -> "disconnected"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "other"
            }
        }.getOrDefault("unknown")
}
