package com.mushrea.code.feature.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mushrea.code.R
import com.mushrea.code.device.bridge.AdbPairingQr

/**
 * The Devices screen: the other phones reached over wireless debugging.
 *
 * It shows what the user's phones *are* before what they can do - identity, state, address, Android
 * version - and separates "paired" from "ready": only a device the platform has really talked to is
 * marked ready, because a socket that accepted a connection proves nothing about what is on the other
 * end of it.
 */
@Composable
fun PeerDevicesScreen(
    state: PeerDevicesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStartQrPairing: () -> Unit,
    onDismissPairing: () -> Unit,
    onShowCodeDialog: () -> Unit,
    onDismissCodeDialog: () -> Unit,
    onCodeHostChange: (String) -> Unit,
    onCodePortChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onPairWithCode: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onProvision: (String) -> Unit,
    onRefreshCapabilities: (String) -> Unit,
    onAskForget: (String) -> Unit,
    onDismissForget: () -> Unit,
    onConfirmForget: () -> Unit,
    onMessageShown: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.peer_devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.peer_devices_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartQrPairing, enabled = !state.busy) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Text(
                        text = stringResource(R.string.peer_devices_pair_button),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(onClick = onShowCodeDialog, enabled = !state.busy) {
                    Text(stringResource(R.string.peer_devices_pair_code_button))
                }
            }

            state.status?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onMessageShown) { Text(stringResource(R.string.peer_devices_dismiss)) }
            }
            state.error?.let { message -> Text(text = message, color = MaterialTheme.colorScheme.error) }

            if (state.devices.isEmpty()) {
                Text(stringResource(R.string.peer_devices_empty), style = MaterialTheme.typography.bodyMedium)
            }
            state.devices.forEach { device ->
                DeviceCard(
                    device = device,
                    busy = state.busySerial == device.serial,
                    onConnect = { onConnect(device.serial) },
                    onDisconnect = { onDisconnect(device.serial) },
                    onProvision = { onProvision(device.serial) },
                    onRefreshCapabilities = { onRefreshCapabilities(device.serial) },
                    onForget = { onAskForget(device.serial) },
                )
            }
        }
    }

    state.pairing?.let { payload ->
        AlertDialog(
            onDismissRequest = onDismissPairing,
            title = { Text(stringResource(R.string.peer_devices_qr_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val bitmap = remember(payload) { AdbPairingQr.render(payload, QR_SIZE_PX) }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.peer_devices_qr_description),
                        modifier = Modifier.size(QR_SIZE_DP.dp),
                    )
                    Text(stringResource(R.string.peer_devices_qr_instructions))
                    Text(
                        text = payload.serviceName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissPairing) { Text(stringResource(R.string.peer_devices_dismiss)) }
            },
        )
    }

    if (state.showCodeDialog) {
        AlertDialog(
            onDismissRequest = onDismissCodeDialog,
            title = { Text(stringResource(R.string.peer_devices_code_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.codeHost,
                        onValueChange = onCodeHostChange,
                        label = { Text(stringResource(R.string.peer_devices_code_host)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.codePort,
                        onValueChange = onCodePortChange,
                        label = { Text(stringResource(R.string.peer_devices_code_port)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = onCodeChange,
                        label = { Text(stringResource(R.string.peer_devices_code_value)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onPairWithCode) { Text(stringResource(R.string.peer_devices_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissCodeDialog) { Text(stringResource(R.string.peer_devices_cancel)) }
            },
        )
    }

    state.confirmForgetSerial?.let { serial ->
        AlertDialog(
            onDismissRequest = onDismissForget,
            title = { Text(stringResource(R.string.peer_devices_forget_title)) },
            text = { Text(stringResource(R.string.peer_devices_forget_message).format(serial)) },
            confirmButton = {
                TextButton(onClick = onConfirmForget) { Text(stringResource(R.string.peer_devices_forget_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissForget) { Text(stringResource(R.string.peer_devices_cancel)) }
            },
        )
    }
}

@Composable
private fun DeviceCard(
    device: PeerDeviceUi,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onProvision: () -> Unit,
    onRefreshCapabilities: () -> Unit,
    onForget: () -> Unit,
) {
    var showDetails by remember(device.serial) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = device.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        stringResource(
                            if (device.ready) R.string.peer_devices_ready else R.string.peer_devices_not_ready,
                        ),
                    color =
                        if (device.ready) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(R.string.peer_devices_state_format).format(device.state),
                style = MaterialTheme.typography.bodySmall,
            )
            if (device.address.isNotBlank()) {
                Text(
                    text = stringResource(R.string.peer_devices_address_format).format(device.address),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                // Which channel last carried a command to this phone: the address alone does not say
                // whether it arrived over the wireless-debugging session or a cable.
                text = stringResource(R.string.peer_devices_transport_format).format(device.transport),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(R.string.peer_devices_android_format).format(device.android, device.abi),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.peer_devices_readiness_format).format(device.readiness),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.peer_devices_trust_format).format(device.trust),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.connected) {
                    OutlinedButton(onClick = onDisconnect, enabled = !busy) {
                        Text(stringResource(R.string.peer_devices_disconnect))
                    }
                } else {
                    Button(onClick = onConnect, enabled = !busy) {
                        Text(stringResource(R.string.peer_devices_connect))
                    }
                }
                OutlinedButton(onClick = onProvision, enabled = !busy) {
                    Text(stringResource(R.string.peer_devices_provision))
                }
                OutlinedButton(onClick = onRefreshCapabilities, enabled = !busy) {
                    Text(stringResource(R.string.peer_devices_capabilities))
                }
                IconButton(onClick = onForget, enabled = !busy) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.peer_devices_forget))
                }
            }
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(
                    stringResource(
                        if (showDetails) R.string.peer_devices_hide_details else R.string.peer_devices_show_details,
                    ),
                )
            }
            if (showDetails) {
                if (!device.hasCapabilities) {
                    Text(stringResource(R.string.peer_devices_no_capabilities), style = MaterialTheme.typography.bodySmall)
                }
                if (device.capabilities.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.peer_devices_capabilities_available).format(device.capabilities.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (device.missing.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.peer_devices_capabilities_missing).format(device.missing.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (device.lastExecutions.isEmpty()) {
                    Text(stringResource(R.string.peer_devices_no_executions), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.peer_devices_last_executions), style = MaterialTheme.typography.labelLarge)
                    device.lastExecutions.forEach { record ->
                        Text(
                            text =
                                stringResource(R.string.peer_devices_execution_line).format(
                                    record.operation.name,
                                    record.commandIdentity,
                                    record.stage.name,
                                    record.durationMillis,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.peer_devices_verification_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val QR_SIZE_DP = 240
private const val QR_SIZE_PX = 640
