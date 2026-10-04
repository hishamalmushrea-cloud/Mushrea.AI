package com.mushrea.code.device.bridge

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [PeerServiceDiscovery] over Android's own `NsdManager`.
 *
 * The platform's mDNS client is enough here - the three service types are plain DNS-SD, and the only
 * thing Android adds is its own lifecycle. That is also the reason this class is so thin: everything
 * worth testing about wireless debugging (what an announcement means, which one belongs to our
 * pairing session, which serial a device will get) sits in [PeerAdbService] and [AdbOutputParser],
 * where it is unit-testable without a radio.
 *
 * Two Android details are handled deliberately:
 *  * **one resolve at a time**: `NsdManager` refuses more than a handful of concurrent resolves
 *    (`ERROR_MAX_LIMIT`), so a burst of announcements must not launch a burst of resolves. The flow
 *    drops an announcement it has already seen rather than queueing a resolve for it.
 *  * **the resolve callback is deprecated but alive**: `resolveService` is the only API that yields
 *    the host/port on every supported version, so it is used with the suppression the rest of the
 *    codebase already uses.
 */
class NsdPeerServiceDiscovery(
    private val nsdManagerProvider: () -> NsdManager?,
) : PeerServiceDiscovery {
    override fun browse(type: PeerAdbServiceType): Flow<PeerAdbService> =
        callbackFlow {
            val nsdManager = nsdManagerProvider()
            if (nsdManager == null) {
                close()
                return@callbackFlow
            }
            val resolved = mutableSetOf<String>()
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        close()
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) = Unit

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val instanceName = serviceInfo.serviceName.orEmpty()
                        if (instanceName.isBlank() || !resolved.add(instanceName)) return
                        resolve(nsdManager, serviceInfo, type) { service -> trySend(service) }
                    }
                }
            runCatching { nsdManager.discoverServices(type.dnsType, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { close() }
            awaitClose { runCatching { nsdManager.stopServiceDiscovery(listener) } }
        }

    private fun resolve(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        type: PeerAdbServiceType,
        onResolved: (PeerAdbService) -> Unit,
    ) {
        val resolveListener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) = Unit

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    val port = info.port
                    if (port <= 0) return
                    onResolved(
                        PeerAdbService(
                            type = type,
                            instanceName = info.serviceName.orEmpty(),
                            host = host,
                            port = port,
                        ),
                    )
                }
            }
        runCatching {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, resolveListener)
        }
    }
}
