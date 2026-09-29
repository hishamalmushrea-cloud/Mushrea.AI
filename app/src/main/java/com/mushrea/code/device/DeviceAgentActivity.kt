package com.mushrea.code.device

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mushrea.code.R
import com.mushrea.code.ui.theme.MushreaCodeTheme
import kotlinx.coroutines.delay
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

    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = MushreaCodeAccessibilityService.isRunning()
            overrides = store.firewallOverrides()
            log = store.activityLog()
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
        OutlinedButton(onClick = { readiness = DeviceReadiness.check(screenContext) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.readiness_run))
        }
        readiness?.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (item.ok) "✓" else "✕", color = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Column {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall)
                    }
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
        LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
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
                                    ConfirmationLevel.CONFIRM -> ConfirmationLevel.STRONG
                                    ConfirmationLevel.STRONG -> null
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
