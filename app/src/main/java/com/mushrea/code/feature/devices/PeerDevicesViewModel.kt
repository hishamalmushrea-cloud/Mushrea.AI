package com.mushrea.code.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mushrea.code.R
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.core.execution.ExecutionRecord
import com.mushrea.code.core.peer.PeerDevice
import com.mushrea.code.core.provisioning.ProvisioningRequest
import com.mushrea.code.core.provisioning.ProvisioningStatus
import com.mushrea.code.device.bridge.PeerAdbBridge
import com.mushrea.code.device.bridge.PeerAdbErrorClassifier
import com.mushrea.code.device.bridge.PeerAdbPairingPayload
import com.mushrea.code.device.provisioning.PeerProvisioningService
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
    /** How far the phone was taken, as proven (see `DeviceReadiness`) - not "connected". */
    val readiness: String,
    val trust: String,
    /** Which transport last carried a command, for the "how did it get here" line. */
    val transport: String,
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
    /**
     * The provisioning service, resolved lazily like the bridge: setting a phone up for remote work is
     * a sequence of steps (route, pair, connect, verify, measure, persist, prove) and the screen must
     * be able to offer it without owning any of that logic.
     */
    private val provisioningProvider: () -> PeerProvisioningService? = { null },
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

    /**
     * Sets a phone up for remote work in one tap, and reports what happened step by step.
     *
     * The status line is the report's own headline: either the proven level, or the single instruction
     * that unblocks the one step only the phone's owner can take. Nothing here decides policy - the
     * steps that change the other phone go through the bridge and the Permission Center like every
     * other command, and a refusal comes back as the report's failure reason.
     */
    fun provision(serial: String) {
        val provisioning = provisioningProvider() ?: return reportUnavailable()
        busyOn(serial)
        // The line under the buttons says what is happening during the (possibly minute-long) flow,
        // because a silent button does not tell the user whether the phone was found.
        mutableState.update { current -> current.copy(status = getString(R.string.peer_devices_provisioning)) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { provisioning.provision(ProvisioningRequest(targetId = serial)) }
            refresh()
            mutableState.update { current ->
                current.copy(
                    busy = false,
                    busySerial = null,
                    pairing = null,
                    error = if (report.status == ProvisioningStatus.PROVISIONED) null else report.headline().takeIf(String::isNotBlank),
                    status =
                        when (report.status) {
                            ProvisioningStatus.PROVISIONED ->
                                getString(R.string.peer_devices_provisioned_format).format(report.readiness.name.lowercase())
                            ProvisioningStatus.NEEDS_USER ->
                                getString(R.string.peer_devices_provision_needs_user_format).format(report.headline())
                            else ->
                                getString(R.string.peer_devices_provision_failed) + " " + report.headline()
                        },
                )
            }
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
            readiness = readiness.name.lowercase(),
            trust = trust.name.lowercase(),
            transport = transportId.ifBlank { getString(R.string.peer_devices_unknown) },
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
