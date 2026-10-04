package com.mushrea.code.core.peer

import com.mushrea.code.core.connectivity.Endpoint
import com.mushrea.code.core.connectivity.EndpointSource
import com.mushrea.code.core.connectivity.RemoteTarget
import com.mushrea.code.core.execution.CapabilityAliases
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.ExecutionTarget
import com.mushrea.code.core.execution.ExecutionTransport
import com.mushrea.code.core.provisioning.DeviceReadiness
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
    /**
     * The stable identity of this device, as far as the platform can derive one.
     *
     * It is *not* the serial and *not* the address: it is a digest of the serial plus the identity
     * fields the device itself reported, so a rename does not create a second device and a new address
     * does not hide the first one. Empty until the device has answered at least once.
     */
    @SerialName("identityKey") val identityKey: String = "",
    @SerialName("trust") val trust: PeerTrust = PeerTrust.UNKNOWN,
    /** Which transport last carried a command to this device, for the report and for reconnection. */
    @SerialName("transportId") val transportId: String = "",
    /** How far the device was taken, as proven by measurements (see [DeviceReadiness]). */
    @SerialName("readiness") val readiness: DeviceReadiness = DeviceReadiness.DISCOVERED,
    /**
     * Every route that has ever reached this device, as `host:port`.
     *
     * A list, not one address: Android randomises the wireless-debugging port and a phone moves between
     * networks, so the useful thing to remember is "these are the addresses that worked", and the
     * route that is announced now is compared against them rather than replacing them.
     */
    @SerialName("endpoints") val knownEndpoints: List<String> = emptyList(),
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

    /**
     * The stored capability map as the platform's report.
     *
     * The map is the persisted shape and the report is the working one, so the translation lives in
     * [CapabilityAliases]: an entry the probe wrote (`bin:pm=/system/bin/pm`) and an entry an older
     * release wrote (`pm=/system/bin/pm`) both read back as the same capability.
     */
    fun capabilityReport(): CapabilityReport = CapabilityAliases.report(capabilities)

    fun withCapabilities(report: CapabilityReport): PeerDevice =
        copy(capabilities = CapabilityAliases.store(report))

    fun withState(newState: PeerDeviceState): PeerDevice = copy(state = newState)

    /** The device as the connectivity resolver sees it: an identity and the routes that used to work. */
    fun remoteTarget(): RemoteTarget =
        RemoteTarget(
            identityKey = identityKey.ifBlank { serial },
            names =
                setOfNotNull(
                    serial.takeIf(String::isNotBlank),
                    instanceName.takeIf(String::isNotBlank),
                    model.takeIf(String::isNotBlank),
                ),
            hints = endpoints(),
        )

    /**
     * The known routes, strongest evidence last: the remembered ones first, then the address of the
     * last session. The current session's address is the strongest hint there is, so it is not
     * "remembered" - it is what the caller passes in as a hint when it knows it.
     */
    fun endpoints(): List<Endpoint> {
        val remembered = knownEndpoints.mapNotNull { value -> Endpoint.parse(value, EndpointSource.REMEMBERED) }
        val current =
            if (host.isNotBlank() && port > 0) {
                listOf(Endpoint(host, port, EndpointSource.REMEMBERED))
            } else {
                emptyList()
            }
        return (current + remembered).distinctBy(Endpoint::key)
    }
}

/** What the device said about itself, filled from one `getprop` batch. */
data class PeerIdentity(
    val model: String = "",
    val manufacturer: String = "",
    val androidVersion: String = "",
    val sdk: Int = 0,
    val abi: String = "",
) {
    val known: Boolean get() = model.isNotBlank() || androidVersion.isNotBlank()
}
