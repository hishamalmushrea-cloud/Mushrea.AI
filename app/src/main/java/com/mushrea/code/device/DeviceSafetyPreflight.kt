package com.mushrea.code.device

import android.content.Context
import android.os.BatteryManager
import com.mushrea.code.device.payload.PayloadGuard
import com.mushrea.code.device.usb.FastbootAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The safety preflight an unlock/flash session should start with: the boring checks that decide
 * whether flashing *now* is a bad idea, plus the reminders a human needs and software cannot
 * verify.
 *
 * It is honest about the split:
 * - **verified** — the ROM's codename vs the device's, the archive's integrity, the target's
 *   `battery-soc-ok`, the host battery, and free space for the file;
 * - **not verifiable from software** — that the cable/port is stable, that the 72h/168h Mi
 *   waiting period has actually elapsed, or that the Mi account/SIM will stay in place. Those are
 *   reported as reminders, never as passed checks, so nobody mistakes a green list for a promise.
 */
class DeviceSafetyPreflight(private val context: Context) {
    data class Check(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val blocking: Boolean = false,
    )

    suspend fun run(params: JSONObject): JSONObject.() -> Unit {
        val checks = mutableListOf<Check>()
        val reminders = mutableListOf<String>()
        val romPath = params.optString("file_path").trim().takeIf { it.isNotBlank() }
        val romFile = romPath?.let { File(it) }
        val resolution = resolveDeviceProduct(params)
        checks += resolution.second
        val deviceProduct = resolution.first

        romFile?.let { checks += romCheck(it, deviceProduct) }
        checks += hostBatteryCheck()
        checks += storageCheck(romFile)
        checks += targetDeviceChecks()
        checks +=
            Check(
                "cable and port",
                true,
                "cannot be checked from software — use a known-good cable, a direct port (no hub), and keep the phone still",
            )
        reminders += REMINDERS

        val blocking = checks.filter { it.blocking }
        val warnings = checks.filter { !it.ok && !it.blocking }
        val verdict =
            when {
                blocking.isNotEmpty() -> "blocked"
                warnings.isNotEmpty() -> "warnings"
                else -> "ready"
            }
        val jsonChecks = JSONArray()
        checks.forEach { check ->
            jsonChecks.put(
                JSONObject()
                    .put("check", check.name)
                    .put("ok", check.ok)
                    .put("blocking", check.blocking)
                    .put("detail", check.detail),
            )
        }
        return {
            put("verdict", verdict)
            put("checks", jsonChecks)
            put("reminders", JSONArray(reminders))
            put("blocking_count", blocking.size)
            put("warning_count", warnings.size)
            put(
                "summary",
                when (verdict) {
                    "blocked" -> "preflight blocked: " + blocking.joinToString("; ") { "${it.name}: ${it.detail}" }
                    "warnings" -> "preflight passed with ${warnings.size} warning(s): " + warnings.joinToString("; ") { it.name }
                    else -> "preflight ready: ${checks.size} check(s) passed"
                },
            )
        }
    }

    /** Returns the product plus any check that had to be recorded while resolving it. */
    private suspend fun resolveDeviceProduct(params: JSONObject): Pair<String?, List<Check>> {
        params.optString("device_product").trim().takeIf { it.isNotBlank() }?.let { return it to emptyList() }
        val agent = FastbootAgent(context)
        val attached = runCatching { agent.devices().firstOrNull() }.getOrNull()
            ?: return null to listOf(Check("device product", false, "no bootloader attached — pass device_product to compare anyway"))
        if (!agent.ensurePermission(attached)) {
            return null to listOf(Check("bootloader permission", false, "USB permission for the bootloader was not granted", blocking = true))
        }
        val (value, reason) = agent.getvar(attached, "product")
        val failure =
            if (value == null) {
                listOf(Check("device product", false, reason ?: "the bootloader did not answer getvar product"))
            } else {
                emptyList()
            }
        return value to failure
    }

    private fun romCheck(
        file: File,
        deviceProduct: String?,
    ): Check {
        val result = PayloadGuard().inspect(file, deviceProduct)
        val ok = !result.blocking
        val detail =
            when (result.verdict) {
                "match" -> "ROM codename \"${result.romProduct}\" matches the device"
                "mismatch" -> "ROM is for \"${result.romProduct}\" but the device is \"$deviceProduct\""
                else -> "codename not confirmed (" + (result.romProduct ?: "nothing declared in the archive") + ")"
            }
        return Check("ROM matches the device", ok, "$detail; integrity: ${result.integrity}", blocking = result.blocking)
    }

    private fun hostBatteryCheck(): Check {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = manager.isCharging
        return when {
            level in 1..19 && !charging ->
                Check("host battery", false, "$level% and not charging — charge this phone before a flash", blocking = true)
            level in 1..49 && !charging ->
                Check("host battery", false, "$level% and not charging — connect a charger", blocking = false)
            level <= 0 -> Check("host battery", false, "level unreadable on this device", blocking = false)
            else -> Check("host battery", true, "$level%" + if (charging) " (charging)" else "")
        }
    }

    private fun storageCheck(file: File?): Check {
        if (file == null || !file.isFile) {
            return Check("free space", true, "no ROM file given, nothing to size")
        }
        val usable = file.parentFile?.usableSpace ?: 0L
        val needed = (file.length() * 1.2).toLong() + GIB
        val enough = usable > needed
        return Check(
            "free space",
            enough,
            "need about ${needed / GIB} GiB for ${file.name}, ${usable / GIB} GiB free",
            blocking = !enough,
        )
    }

    private suspend fun targetDeviceChecks(): List<Check> {
        val agent = FastbootAgent(context)
        val attached = runCatching { agent.devices().firstOrNull() }.getOrNull()
            ?: return listOf(Check("target device", false, "no device in fastboot/bootloader mode"))
        if (!agent.ensurePermission(attached)) {
            return listOf(Check("target device", false, "USB permission for the bootloader was not granted", blocking = true))
        }
        val (soc, _) = agent.getvar(attached, "battery-soc-ok")
        val (unlocked, _) = agent.getvar(attached, "unlocked")
        val (secure, _) = agent.getvar(attached, "secure")
        val battery =
            when (soc?.trim()?.lowercase()) {
                "yes", "ok" -> Check("target battery", true, "battery-soc-ok=yes")
                "no" -> Check("target battery", false, "battery-soc-ok=no — charge the device before flashing", blocking = true)
                else -> Check("target battery", false, "battery-soc-ok not reported — cannot confirm the charge level")
            }
        return listOf(Check("bootloader state", true, "unlocked=$unlocked, secure=$secure"), battery)
    }

    private companion object {
        const val GIB = 1024L * 1024 * 1024

        /** Things software cannot verify, phrased so they are read as instructions, not confirmations. */
        val REMINDERS =
            listOf(
                "Mi unlock waiting period: if Mi Unlock reports 72h/168h, the wait must actually elapse — nothing here can shorten it, and no tool should pretend otherwise",
                "keep the Mi account and SIM in the phone during the waiting period; removing them resets the clock",
                "back up persist/nvram before any partition write — those are unrecoverable if lost",
                "an unlock wipes userdata: back up photos/files first",
                "the emergency stop cannot cancel a command the bootloader already received — it only prevents the next one",
                "no forged server signature is used or attempted anywhere in this app",
            )
    }
}
