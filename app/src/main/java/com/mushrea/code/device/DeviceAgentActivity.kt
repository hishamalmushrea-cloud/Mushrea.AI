package com.mushrea.code.device

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mushrea.code.R
import com.mushrea.code.core.UrlLauncher
import com.mushrea.code.core.permission.ConfirmationLevel
import com.mushrea.code.device.usb.UsbExecutor
import com.mushrea.code.ui.theme.MushreaCodeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Device Agent control surface: accessibility enablement, the Permission Firewall toggles, the
 * activity log, and the emergency STOP button (prompt sections 35–37). Deliberately a separate,
 * dependency-light screen so it stays reviewable; it will be folded into Settings later.
 */
class DeviceAgentActivity : ComponentActivity() {
    private lateinit var store: DeviceAgentStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceAgentStore(this)
        setContent {
            MushreaCodeTheme {
                DeviceAgentScreen(
                    store = store,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenCallAgent = {
                        startActivity(Intent(this, com.mushrea.code.device.call.CallAgentActivity::class.java))
                    },
                    onStopAgent = {
                        sendBroadcast(Intent(this, StopAgentReceiver::class.java))
                    },
                )
            }
        }
    }
}

private data class FirewallRow(
    val action: String,
    val level: ConfirmationLevel,
    val isOverridden: Boolean,
)

@Composable
private fun DeviceAgentScreen(
    store: DeviceAgentStore,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenCallAgent: () -> Unit,
    onStopAgent: () -> Unit,
) {
    var accessibilityOn by remember { mutableStateOf(MushreaCodeAccessibilityService.isRunning()) }
    var overrides by remember { mutableStateOf(store.firewallOverrides()) }
    var log by remember { mutableStateOf(store.activityLog()) }
    var readiness by remember { mutableStateOf<List<DeviceReadiness.Item>?>(null) }
    var pingItem by remember { mutableStateOf<DeviceReadiness.Item?>(null) }
    var readOnly by remember { mutableStateOf(store.readOnlyMode()) }
    var riskAcknowledgedAt by remember { mutableStateOf(store.riskAcknowledgedAt()) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = MushreaCodeAccessibilityService.isRunning()
            overrides = store.firewallOverrides()
            log = store.activityLog()
            readOnly = store.readOnlyMode()
            delay(1_000)
        }
    }

    val baseFirewall = remember { DeviceActionFirewall() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.device_agent_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        if (accessibilityOn) {
                            stringResource(R.string.device_agent_accessibility_enabled)
                        } else {
                            stringResource(R.string.device_agent_accessibility_disabled)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!accessibilityOn) {
                    Button(onClick = onOpenAccessibilitySettings) {
                        Text(stringResource(R.string.device_agent_accessibility_enable))
                    }
                }
            }
        }

        OutlinedButton(onClick = onOpenCallAgent, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.call_agent_screen_title))
        }

        val screenContext = LocalContext.current
        val scope = rememberCoroutineScope()
        val autostartIntent =
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
        val defaultDialerIntent =
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, screenContext.packageName)
        OutlinedButton(onClick = { readiness = DeviceReadiness.check(screenContext) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.readiness_run))
        }
        OutlinedButton(
            onClick = { scope.launch { pingItem = DeviceReadiness.ping(screenContext) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.readiness_ping))
        }
        (readiness.orEmpty() + listOfNotNull(pingItem)).forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (item.ok) "✓" else "✕",
                        color = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Column {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text(stringResource(R.string.settings_helper), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = { openSettingsOrFallback(screenContext, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_accessibility))
        }
        OutlinedButton(
            onClick = { openSettingsOrFallback(screenContext, autostartIntent, Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_autostart))
        }
        OutlinedButton(
            onClick = {
                openSettingsOrFallback(
                    screenContext,
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_battery))
        }
        OutlinedButton(
            onClick = { openSettingsOrFallback(screenContext, defaultDialerIntent, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_default_dialer))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.usb_card_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.usb_card_body), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        openSettingsOrFallback(screenContext, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), null)
                    },
                ) {
                    Text(stringResource(R.string.usb_open_dev_settings))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_agent_readonly_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        stringResource(
                            if (readOnly) R.string.device_agent_readonly_on else R.string.device_agent_readonly_off,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        store.setReadOnlyMode(!readOnly)
                        readOnly = store.readOnlyMode()
                    },
                ) {
                    Text(
                        stringResource(
                            if (readOnly) R.string.device_agent_readonly_disable else R.string.device_agent_readonly_enable,
                        ),
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            diagnostics =
                                runCatching {
                                    val payload = JSONObject().apply(UsbExecutor(screenContext).executeDiagnostics())
                                    notes =
                                        payload.optJSONArray("notes")?.let { array ->
                                            (0 until array.length()).map { array.optString(it) }
                                        }.orEmpty()
                                    payload.optString("summary")
                                }.getOrElse { it.message ?: "diagnostics failed" }
                        }
                    },
                ) {
                    Text(stringResource(R.string.device_agent_diagnostics_run))
                }
                Text(
                    text = diagnostics ?: stringResource(R.string.device_agent_diagnostics_none),
                    style = MaterialTheme.typography.bodySmall,
                )
                notes.forEach { note ->
                    Text(text = "• $note", style = MaterialTheme.typography.bodySmall)
                }
                // The unlock path is never hidden behind prose: the official page is one tap away,
                // and this button points at the same URL the diagnostics payload reports.
                OutlinedButton(onClick = { UrlLauncher.openUrl(screenContext, XiaomiUnlock.OFFICIAL_URL) }) {
                    Text(stringResource(R.string.device_agent_unlock_official))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_agent_risk_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.device_agent_risk_body), style = MaterialTheme.typography.bodySmall)
                if (riskAcknowledgedAt == 0L) {
                    OutlinedButton(
                        onClick = {
                            store.acknowledgeRisk()
                            riskAcknowledgedAt = store.riskAcknowledgedAt()
                        },
                    ) {
                        Text(stringResource(R.string.device_agent_risk_accept))
                    }
                } else {
                    Text(stringResource(R.string.device_agent_risk_accepted), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Button(
            onClick = onStopAgent,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
        ) {
            Text(stringResource(R.string.device_agent_stop), fontWeight = FontWeight.Bold)
        }

        Text(
            text = stringResource(R.string.device_agent_firewall_title),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            items(DeviceActionFirewall.CONFIGURABLE_ACTIONS) { action ->
                val row =
                    FirewallRow(
                        action = action,
                        level = overrides[action] ?: baseFirewall.levelFor(action),
                        isOverridden = overrides.containsKey(action),
                    )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = action, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            val next =
                                when (row.level) {
                                    ConfirmationLevel.AUTO -> ConfirmationLevel.CONFIRM
                                    ConfirmationLevel.CONFIRM -> ConfirmationLevel.STRONG_CONFIRM
                                    ConfirmationLevel.STRONG_CONFIRM -> null
                                }
                            store.setFirewallOverride(action, next)
                            overrides = store.firewallOverrides()
                        },
                    ) {
                        Text(
                            text = row.level.name + if (row.isOverridden) " *" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.device_agent_activity_title),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (log.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.device_agent_empty_log),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(log.take(30)) { entry ->
                DeviceLogRow(entry)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun DeviceLogRow(entry: JSONObject) {
    val ok = entry.optBoolean("ok")
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = (if (ok) "✓ " else "✕ ") + entry.optString("action"),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        val detail = entry.optString("detail")
        if (detail.isNotBlank()) {
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Opens a settings screen, falling back to the app's own system-settings page when absent. */
private fun openSettingsOrFallback(
    context: android.content.Context,
    intent: Intent,
    fallbackAction: String?,
) {
    val started = runCatching { context.startActivity(intent) }.isSuccess
    if (!started && fallbackAction != null) {
        runCatching {
            context.startActivity(Intent(fallbackAction, Uri.fromParts("package", context.packageName, null)))
        }
    }
}
