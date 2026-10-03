package com.mushrea.code.device.connectivity

import com.mushrea.code.core.connectivity.Endpoint
import com.mushrea.code.core.connectivity.EndpointProvider
import com.mushrea.code.core.connectivity.EndpointSource
import com.mushrea.code.core.connectivity.RemoteTarget
import com.mushrea.code.device.bridge.PeerAdbSession
import com.mushrea.code.device.bridge.PeerDeviceRegistry

/**
 * The routes this app already remembers for a device.
 *
 * Wireless debugging hands out a new port every time the feature is switched on, so "remembered" is
 * not a promise - but it *is* the cheapest first attempt, and it is right often enough (a phone that
 * merely slept keeps its port). Later attempts ask discovery instead; that ordering lives in the
 * reconnection manager, not here.
 */
class RememberedEndpointProvider(
    private val registry: PeerDeviceRegistry,
) : EndpointProvider {
    override val id: String = "remembered"
    override val transportId: String = "peer-adb-tcp"

    override suspend fun endpoints(target: RemoteTarget): List<Endpoint> {
        val device = registry.find(target.identityKey) ?: return emptyList()
        val current =
            if (device.host.isNotBlank() && device.port > 0) {
                listOf(Endpoint(device.host, device.port, EndpointSource.REMEMBERED))
            } else {
                emptyList()
            }
        return current + device.endpoints()
    }
}

/**
 * The routes `adb` is holding *right now*.
 *
 * `adb devices -l` lists a TCP device as `host:port`, which is the only place the ephemeral
 * wireless-debugging port is visible at all (Android's API does not expose it - see the architecture
 * note on `mPort`). An address adb is already connected to is the strongest evidence there is, which is
 * why it is reported as a hand-over rather than as an announcement.
 */
class AdbLiveEndpointProvider(
    private val session: PeerAdbSession,
) : EndpointProvider {
    override val id: String = "adb-live"
    override val transportId: String = "peer-adb-tcp"

    override suspend fun endpoints(target: RemoteTarget): List<Endpoint> {
        val remembered = target.hints.map { it.key }.toSet()
        return runCatching { session.adbDevices() }
            .getOrDefault(emptyList())
            .mapNotNull { line -> Endpoint.parse(line.serial, EndpointSource.ANNOUNCED) }
            .map { endpoint ->
                if (endpoint.key in remembered) endpoint.copy(source = EndpointSource.HANDOVER) else endpoint
            }
    }
}
