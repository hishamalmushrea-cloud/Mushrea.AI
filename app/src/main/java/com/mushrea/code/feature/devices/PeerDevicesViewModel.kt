package com.mushrea.code.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mushrea.code.R
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.core.execution.ExecutionRecord
import com.mushrea.code.device.bridge.PeerAdbBridge
import com.mushrea.code.device.bridge.PeerAdbErrorClassifier
import com.mushrea.code.device.bridge.PeerAdbPairingPayload
import com.mushrea.code.device.bridge.PeerDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One device, flattened for the screen: identity first, then what the phone reported. */
data class PeerDeviceUi(
    val serial: String,
    val label: String,
    val state: String,
    val ready: Boolean,
    val connected: Boolean,
    val address: String,
    val android: String,
    val abi: String,
    val capabilities: List<String>,
    val missing: List<String>,
    val lastSeenMillis: Long,
    val lastExecutions: List<ExecutionRecord>,
) {
    val hasCapabilities: Boolean get() = capabilities.isNotEmpty() || missing.isNotEmpty()
}

data class PeerDevicesUiState(
    val devices: List<PeerDeviceUi> = emptyList(),
    val pairing: PeerAdbPairingPayload? = null,
    val busy: Boolean = false,
    val busySerial: String? = null,
    val status: String? = null,
    val error: String? = null,
    val showCodeDialog: Boolean = false,
    val codeHost: String = "",
    val codePort: String = "",
    val code: String = "",
    val confirmForgetSerial: String? = null,
)

/**
 * The Devices screen's state holder.
 *
 * Everything it can do is injected, so the screen has no idea that `adb`, mDNS or the Permission
 * Center exist - and the pairing/connection logic stays where it can be unit tested without a phone.
 * The one rule it enforces for the user: a device is shown as ready only when the platform has really
 * talked to it ([PeerDeviceUi.ready]), never because a socket was opened.
 */
class PeerDevicesViewModel(
    private val bridgeProvider: () -> PeerAdbBridge?,
    private val getString: (Int) -> String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PeerDevicesUiState())
    val state: StateFlow<PeerDevicesUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    private fun bridge(): PeerAdbBridge? = bridgeProvider()

    fun refresh() {
        val bridge = bridge() ?: return
        mutableState.update { current ->
            current.copy(
                devices =
                    bridge.devices().map { device ->
                        device.toUi(bridge.recentExecutions(LOG_WINDOW).filter { it.targetId == device.serial }.take(3))
                    },
            )
        }
    }

    /** Starts a QR session: the payload is rendered as the QR the other phone scans. */
    fun startQrPairing() {
        val bridge = bridge() ?: return reportUnavailable()
        val payload = bridge.beginPairingQr()
        mutableState.update { current ->
            current.copy(pairing = payload, error = null, status = getString(R.string.peer_devices_pairing_waiting))
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    bridge.pairWithQr(payload, PAIRING_WAIT_MILLIS, CONNECT_WAIT_MILLIS)
                }
            settle(result, onSuccess = { device -> getString(R.string.peer_devices_paired_format).format(device.label) })
        }
    }

    fun dismissPairing() {
        mutableState.update { current -> current.copy(pairing = null, status = null) }
    }

    fun showCodeDialog() {
        mutableState.update { current -> current.copy(showCodeDialog = true, error = null) }
    }

    fun dismissCodeDialog() {
        mutableState.update { current -> current.copy(showCodeDialog = false) }
    }

    fun updateCodeHost(value: String) {
        mutableState.update { current -> current.copy(codeHost = value) }
    }

    fun updateCodePort(value: String) {
        mutableState.update { current -> current.copy(codePort = value.filter(Char::isDigit)) }
    }

    fun updateCode(value: String) {
        mutableState.update { current -> current.copy(code = value.filter(Char::isDigit).take(CODE_LENGTH)) }
    }

    fun pairWithCode() {
        val bridge = bridge() ?: return reportUnavailable()
        val current = mutableState.value
        val port = current.codePort.toIntOrNull()
        if (current.codeHost.isBlank() || port == null || current.code.length < CODE_LENGTH) {
            mutableState.update { state -> state.copy(error = getString(R.string.peer_devices_code_incomplete)) }
            return
        }
        mutableState.update { state -> state.copy(busy = true, error = null, showCodeDialog = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bridge.pairWithCode(current.codeHost.trim(), port, current.code) }
            settle(result, onSuccess = { device -> getString(R.string.peer_devices_paired_format).format(device.label) })
        }
    }

    fun connect(serial: String) {
        val bridge = bridge() ?: return reportUnavailable()
        busyOn(serial)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bridge.reconnect(serial) }
            settle(result, onSuccess = { device -> getString(R.string.peer_devices_connected_format).format(device.label) })
        }
    }

    fun disconnect(serial: String) {
        val bridge = bridge() ?: return reportUnavailable()
        busyOn(serial)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bridge.disconnect(serial) }
            result.fold(
                onSuccess = {
                    refresh()
                    mutableState.update { state ->
                        state.copy(busy = false, busySerial = null, status = getString(R.string.peer_devices_disconnected), error = null)
                    }
                },
                onFailure = { throwable -> fail(throwable) },
            )
        }
    }

    fun refreshCapabilities(serial: String) {
        val bridge = bridge() ?: return reportUnavailable()
        busyOn(serial)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bridge.refreshCapabilities(serial) }
            result.fold(
                onSuccess = { report ->
                    refresh()
                    val available = report.all.count { it.status == CapabilityStatus.AVAILABLE }
                    mutableState.update { state ->
                        state.copy(
                            busy = false,
                            busySerial = null,
                            error = null,
                            status =
                                getString(R.string.peer_devices_capabilities_count_format)
                                    .format(available, report.all.size - available),
                        )
                    }
                },
                onFailure = { throwable -> fail(throwable) },
            )
        }
    }

    fun askForget(serial: String) {
        mutableState.update { current -> current.copy(confirmForgetSerial = serial) }
    }

    fun dismissForget() {
        mutableState.update { current -> current.copy(confirmForgetSerial = null) }
    }

    fun confirmForget() {
        val bridge = bridge() ?: return reportUnavailable()
        val serial = mutableState.value.confirmForgetSerial ?: return
        bridge.forget(serial)
        mutableState.update { current ->
            current.copy(confirmForgetSerial = null, status = getString(R.string.peer_devices_forgotten), error = null)
        }
        refresh()
    }

    fun clearMessage() {
        mutableState.update { current -> current.copy(status = null, error = null) }
    }

    private fun busyOn(serial: String) {
        mutableState.update { current -> current.copy(busy = true, busySerial = serial, error = null, status = null) }
    }

    private fun <T> settle(
        result: Result<T>,
        onSuccess: (T) -> String,
    ) {
        result.fold(
            onSuccess = { value ->
                refresh()
                mutableState.update { current ->
                    current.copy(busy = false, busySerial = null, error = null, status = onSuccess(value), pairing = null)
                }
            },
            onFailure = { throwable -> fail(throwable) },
        )
    }

    private fun fail(throwable: Throwable) {
        val error = PeerAdbErrorClassifier.classify(throwable.message.orEmpty())
        mutableState.update { current ->
            current.copy(
                busy = false,
                busySerial = null,
                status = null,
                error = "${getString(R.string.peer_devices_failed)}: ${error.nextStep} (${error.code})",
            )
        }
    }

    private fun reportUnavailable() {
        mutableState.update { current ->
            current.copy(busy = false, status = null, error = getString(R.string.peer_devices_unavailable))
        }
    }

    private fun PeerDevice.toUi(executions: List<ExecutionRecord>): PeerDeviceUi {
        val report = capabilityReport()
        return PeerDeviceUi(
            serial = serial,
            label = label,
            state = state.name,
            ready = state.readyForExecution,
            connected = connected,
            address = if (host.isBlank()) "" else "$host:$port",
            android = androidVersion.ifBlank { getString(R.string.peer_devices_unknown) },
            abi = abi.ifBlank { getString(R.string.peer_devices_unknown) },
            capabilities = report.all.filter { it.status == CapabilityStatus.AVAILABLE }.map { it.name },
            missing = report.all.filter { it.status == CapabilityStatus.MISSING }.map { it.name },
            lastSeenMillis = lastSeenMillis,
            lastExecutions = executions,
        )
    }

    companion object {
        const val CODE_LENGTH = 6
        const val LOG_WINDOW = 50
        const val PAIRING_WAIT_MILLIS = 90_000L
        const val CONNECT_WAIT_MILLIS = 45_000L
    }
}
