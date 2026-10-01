package com.mushrea.code.device

import android.Manifest
import android.content.Context
import androidx.core.content.ContextCompat
import com.mushrea.code.R
import com.mushrea.code.device.call.PhoneCallController
import com.mushrea.code.device.tool.DeviceAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The one-tap readiness probe behind the "check readiness" button: a short pass/fail list that
 * answers the questions support requests start with — is the accessibility service actually up,
 * is the command channel registered, is the runtime installed, are the call permissions granted,
 * and what the default-dialer role (auto-answer) needs. Details are diagnostic strings, kept
 * plain so they can be pasted into a bug report.
 */
object DeviceReadiness {
    data class Item(
        val ok: Boolean,
        val title: String,
        val detail: String,
    )

    fun check(context: Context): List<Item> {
        val appContext = context.applicationContext
        val items = mutableListOf<Item>()

        val accessibilityOn = MushreaCodeAccessibilityService.isRunning()
        items +=
            Item(
                ok = accessibilityOn,
                title = appContext.getString(R.string.readiness_accessibility),
                detail = if (accessibilityOn) "OK" else appContext.getString(R.string.readiness_accessibility_hint),
            )

        val store = DeviceAgentStore(appContext)
        val workspace =
            store.readActiveWorkspace()
                ?: File(appContext.filesDir, "runtime/workspace").takeIf(File::isDirectory)?.absolutePath
        items +=
            Item(
                ok = workspace != null,
                title = appContext.getString(R.string.readiness_channel),
                detail = workspace ?: appContext.getString(R.string.readiness_channel_hint),
            )

        val runtimeInstalled = File(appContext.filesDir, "runtime/environment/rootfs").isDirectory
        items +=
            Item(
                ok = runtimeInstalled,
                title = appContext.getString(R.string.readiness_runtime),
                detail = if (runtimeInstalled) "OK" else appContext.getString(R.string.readiness_runtime_hint),
            )

        val callPermissions =
            listOf(
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_PHONE_STATE,
            )
        val grantedCount =
            callPermissions.count {
                ContextCompat.checkSelfPermission(appContext, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        items +=
            Item(
                ok = grantedCount == callPermissions.size,
                title = appContext.getString(R.string.readiness_call_permissions),
                detail = "$grantedCount/${callPermissions.size}",
            )

        val defaultDialer = PhoneCallController(appContext).isDefaultDialer()
        items +=
            Item(
                ok = defaultDialer,
                title = appContext.getString(R.string.readiness_default_dialer),
                detail = if (defaultDialer) "OK" else appContext.getString(R.string.readiness_default_dialer_hint),
            )

        // The catalog's requirements, evaluated rather than declared: which tools this device
        // cannot run right now and the one reason that stops each kind of them. Details stay plain
        // so they can be pasted into a bug report, like the rest of this list.
        val blocked = DeviceAvailability.onDevice(appContext).blockedTools()
        items +=
            Item(
                ok = blocked.isEmpty(),
                title =
                    if (blocked.isEmpty()) {
                        appContext.getString(R.string.readiness_tools_ready)
                    } else {
                        appContext.getString(R.string.readiness_tools_blocked, blocked.size)
                    },
                detail =
                    blocked
                        .map { it.second }
                        .distinct()
                        .take(MAX_BLOCKED_REASONS)
                        .joinToString(" · ")
                        .ifEmpty { "OK" },
            )

        return items
    }

    /** Enough to name the problems without turning the readiness card into a wall of text. */
    private const val MAX_BLOCKED_REASONS = 3

    /**
     * Active probe: writes a ping command into the channel exactly like the agent core does and
     * waits for the bridge to answer — proving write → inotify wake → process → result end to end.
     */
    suspend fun ping(context: Context): Item =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val store = DeviceAgentStore(appContext)
            val workspace =
                store.readActiveWorkspace()
                    ?: File(appContext.filesDir, "runtime/workspace").takeIf(File::isDirectory)?.absolutePath
            val title = appContext.getString(R.string.readiness_ping)
            if (workspace == null) {
                return@withContext Item(false, title, appContext.getString(R.string.readiness_ping_no_channel))
            }
            val id = "readiness-${System.currentTimeMillis()}"
            val sentAtMillis = System.currentTimeMillis()
            val request =
                JSONObject()
                    .put("id", id)
                    .put("action", DeviceActionFirewall.ACTION_PING)
                    .put("params", JSONObject())
                    .put("ts", sentAtMillis)
            val commandFile = File(File(workspace, DeviceAgentBridge.COMMAND_DIR_NAME), DeviceAgentBridge.COMMAND_FILE_NAME)
            val resultFile = File(workspace, DeviceAgentBridge.RESULT_RELATIVE_PATH)
            runCatching {
                commandFile.parentFile?.mkdirs()
                commandFile.writeText(request.toString())
            }.onFailure {
                return@withContext Item(false, title, it.message ?: "write failed")
            }
            while (System.currentTimeMillis() - sentAtMillis < PING_TIMEOUT_MILLIS) {
                val result =
                    runCatching { resultFile.takeIf(File::isFile)?.readText() }.getOrNull()
                        ?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
                if (result != null && result.optString("id") == id) {
                    runCatching { resultFile.delete() }
                    val ok = result.optBoolean("ok")
                    return@withContext Item(
                        ok = ok,
                        title = title,
                        detail = if (ok) "OK (${System.currentTimeMillis() - sentAtMillis}ms)" else result.optString("error"),
                    )
                }
                delay(100)
            }
            Item(false, title, appContext.getString(R.string.readiness_ping_timeout))
        }

    private const val PING_TIMEOUT_MILLIS = 5_000L
}
