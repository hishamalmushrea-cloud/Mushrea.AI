package com.mushrea.code.device

import android.Manifest
import android.content.Context
import com.mushrea.code.R
import com.mushrea.code.device.call.PhoneCallController
import androidx.core.content.ContextCompat
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
        val grantedCount = callPermissions.count { ContextCompat.checkSelfPermission(appContext, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
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

        return items
    }
}
