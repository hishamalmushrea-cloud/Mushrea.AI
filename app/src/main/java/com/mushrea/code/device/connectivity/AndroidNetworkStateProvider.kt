package com.mushrea.code.device.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.mushrea.code.core.connectivity.ConnectivityFacility
import com.mushrea.code.core.connectivity.ConnectivityProvider
import com.mushrea.code.core.connectivity.ConnectivityReport
import com.mushrea.code.core.connectivity.LocalAddress
import com.mushrea.code.core.connectivity.NetworkScope

/**
 * This phone's own networking, read from the platform (no extra permission: `ACCESS_NETWORK_STATE` is
 * already declared and `LinkProperties` needs nothing more).
 *
 * One provider reports several facilities. That is deliberate rather than a modelling mistake: Android
 * answers all of these questions from the same manager, and asking it once per facility would multiply
 * a system call for no benefit. The provider's own [facility] is the one it is *named* for; whatever
 * else it sees is merged by the resolver.
 *
 * Two facts matter most here, and both are about honesty:
 *
 *  * **a tunnel interface is named, not guessed.** `tun0`/`wg0`/`tailscale0` make the facility
 *    [ConnectivityFacility.PRIVATE_OVERLAY] - which is what lets a plan say "reach it over the private
 *    network" instead of "public address, refused".
 *  * **the address carries its scope.** A `100.64/10` address is reported as
 *    [NetworkScope.PRIVATE_OVERLAY] even before the interface name is considered, because that range
 *    is *routed by a tunnel* by construction.
 */
class AndroidNetworkStateProvider(
    private val context: Context,
) : ConnectivityProvider {
    override val id: String = "android-network"
    override val facility: ConnectivityFacility = ConnectivityFacility.WIFI

    override suspend fun report(): ConnectivityReport {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ConnectivityReport.of(emptySet(), detail = mapOf(facility to "$id: no ConnectivityManager"))
        return runCatching { read(manager) }.getOrElse { throwable ->
            ConnectivityReport.of(emptySet(), detail = mapOf(facility to "$id: ${throwable.message ?: "read failed"}"))
        }
    }

    private fun read(manager: ConnectivityManager): ConnectivityReport {
        val facilities = linkedSetOf<ConnectivityFacility>()
        val addresses = mutableListOf<LocalAddress>()
        val detail = mutableMapOf<ConnectivityFacility, String>()

        manager.allNetworks.forEach { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@forEach
            val properties = manager.getLinkProperties(network)
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) facilities += ConnectivityFacility.WIFI
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) facilities += ConnectivityFacility.ETHERNET
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) facilities += ConnectivityFacility.CELLULAR
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) facilities += ConnectivityFacility.VPN
            properties?.let { link ->
                addresses += link.addressesExcluding(NetworkScope.LOOPBACK)
                val scope = overlayScope(link)
                if (scope != null) facilities += scope
            }
        }
        // The loopback interface is always there, and it is where a USB-bootstrapped listener lands.
        facilities += ConnectivityFacility.LOOPBACK
        facilities += ConnectivityFacility.MDNS
        detail[facility] =
            if (facilities.isEmpty()) "$id: down" else "$id: " + facilities.sortedBy { it.name }.joinToString(", ") { it.name.lowercase() }
        detail[ConnectivityFacility.PRIVATE_OVERLAY] =
            if (ConnectivityFacility.PRIVATE_OVERLAY in facilities) {
                "a tunnel interface is up"
            } else {
                "no tunnel interface is up"
            }
        return ConnectivityReport(facilities, addresses.distinct(), detail)
    }

    /**
     * Whether a link is an overlay, from its interface names.
     *
     * Android names tunnel interfaces by their owner (`tun0`, `wg0`, `tailscale0`), and naming the
     * facility is what allows a plan to prefer it over a public route - or to tell the user that the
     * private network is what has to come up first.
     */
    private fun overlayScope(link: LinkProperties): ConnectivityFacility? {
        val names = link.interfaceName.orEmpty().lowercase()
        if (names.isBlank()) return null
        return if (names.startsWith("tun") || names.startsWith("wg") || names.startsWith("tailscale")) {
            ConnectivityFacility.PRIVATE_OVERLAY
        } else {
            null
        }
    }

    private fun LinkProperties.addressesExcluding(excluded: NetworkScope): List<LocalAddress> =
        this.linkAddresses
            .map { it.address.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
            .map { host -> LocalAddress(interfaceName.orEmpty(), host, NetworkScope.of(host, interfaceName.orEmpty())) }
            .filter { it.scope != excluded }
}
