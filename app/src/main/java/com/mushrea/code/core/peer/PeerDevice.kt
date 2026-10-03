package com.mushrea.code.core.peer

import com.mushrea.code.core.execution.Capability
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.core.execution.ExecutionTarget
import com.mushrea.code.core.execution.ExecutionTransport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How far a peer device has been taken - the states the UI and the agent both read.
 *
 * The order is the pairing flow, and each state means something strictly stronger than the one before
 * it:
 *  * [DISCOVERED] - an mDNS announcement was seen; nothing is trusted yet;
 *  * [PAIRED] - the phone accepted our key (`adb pair` succeeded); it has not been talked to since;
 *  * [CONNECTED] - `adb connect` reported a device; the channel exists but is unproven;
 *  * [VERIFIED] - a real command ran on it and came back (`getprop` of its identity);
 *  * [DISCONNECTED] - it was connected before and is not now.
 *
 * Only [VERIFIED] may be shown as ready for execution: "connected" is a claim about a socket, which
 * is exactly the claim the spec forbids making before the device has answered.
 */
enum class PeerDeviceState {
    DISCOVERED,
    PAIRED,
    CONNECTED,
    VERIFIED,
    DISCONNECTED,
    ;

    val readyForExecution: Boolean get() = this == VERIFIED
}

/**
 * One Android phone reached over wireless debugging, as the app remembers it.
 *
 * Everything here is either what mDNS announced ([instanceName], [host], [port]) or what the device
 * itself answered ([serial], [model], [androidVersion]…). The port is *not* an identity - Android
 * picks a new one every time wireless debugging is switched on - so reconnection is by [serial] and
 * [instanceName] and the port is refreshed each time.
 *
 * The ADB keys never appear here: they live in the runtime's `adb_keys`, and this record only says
 * whether the pairing succeeded.
 */
@Serializable
data class PeerDevice(
    @SerialName("serial") val serial: String,
    @SerialName("instanceName") val instanceName: String = "",
    @SerialName("host") val host: String = "",
    @SerialName("port") val port: Int = 0,
    @SerialName("state") val state: PeerDeviceState = PeerDeviceState.DISCOVERED,
    @SerialName("model") val model: String = "",
    @SerialName("manufacturer") val manufacturer: String = "",
    @SerialName("androidVersion") val androidVersion: String = "",
    @SerialName("sdk") val sdk: Int = 0,
    @SerialName("abi") val abi: String = "",
    @SerialName("lastSeenMillis") val lastSeenMillis: Long = 0,
    @SerialName("capabilities") val capabilities: Map<String, String> = emptyMap(),
) {
    val label: String get() = model.ifBlank { serial }

    val connected: Boolean get() = state == PeerDeviceState.CONNECTED || state == PeerDeviceState.VERIFIED

    /** The target an execution request names; there is no implicit device anywhere in the path. */
    fun target(): ExecutionTarget =
        ExecutionTarget(
            id = serial,
            transport = ExecutionTransport.PEER_ADB,
            label = label,
        )

    /** The stored capability map as the platform's report (unknown when the device was never probed). */
    fun capabilityReport(): CapabilityReport =
        CapabilityReport.of(
            capabilities.map { (name, value) ->
                if (value.isBlank()) {
                    Capability(name, CapabilityStatus.MISSING)
                } else {
                    CapabilityReport.available(name, value)
                }
            },
        )

    fun withCapabilities(report: CapabilityReport): PeerDevice =
        copy(
            capabilities =
                report.all.associate { capability ->
                    capability.name to
                        (if (capability.status == CapabilityStatus.MISSING) "" else capability.detail.ifBlank { "ok" })
                },
        )

    fun withState(newState: PeerDeviceState): PeerDevice = copy(state = newState)
}
