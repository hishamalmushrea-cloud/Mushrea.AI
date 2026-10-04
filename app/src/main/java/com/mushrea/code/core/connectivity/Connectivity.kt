package com.mushrea.code.core.connectivity

/**
 * A network facility this device has - the vocabulary a plan and the agent print.
 *
 * Facilities are what the *host* can do, not what the target is: "we are on Wi-Fi", "we have a private
 * overlay up", "a USB device is attached", "mDNS answers". A provider declares them, so a facility
 * nobody implements (a Bluetooth PAN, a serial link) is absent rather than wrongly assumed, and adding
 * one is adding a provider.
 */
enum class ConnectivityFacility {
    WIFI,
    ETHERNET,
    CELLULAR,

    /** A VPN or tunnel interface that is up (`tun0`, `wg0`, an enterprise profile). */
    VPN,

    /** A Tailscale/Headscale-class overlay: private addresses routed by a trusted control plane. */
    PRIVATE_OVERLAY,

    /** A USB-attached Android device that speaks the adb protocol. */
    USB,

    /** The loopback interface, where a USB-bootstrapped `tcpip:` listener lives. */
    LOOPBACK,

    /** DNS-SD answers on this network (the wireless-debugging announcement path). */
    MDNS,
    ;

    /** Facilities that mean "packets can leave this device". */
    val transportsTraffic: Boolean get() = this == WIFI || this == ETHERNET || this == CELLULAR || this == VPN || this == PRIVATE_OVERLAY
}

/** One address of one interface of this device. */
data class LocalAddress(
    val interfaceName: String,
    val address: String,
    val scope: NetworkScope = NetworkScope.of(address, interfaceName),
)

/**
 * What this device's networking looked like when it was asked.
 *
 * The report is deliberately a *snapshot with provenance*: every facility carries the sentence that
 * justified it, so a plan can say "no private overlay, because no tunnel interface is up" instead of
 * "unreachable", and the user can act on the first sentence.
 */
class ConnectivityReport(
    val facilities: Set<ConnectivityFacility>,
    val addresses: List<LocalAddress> = emptyList(),
    val detail: Map<ConnectivityFacility, String> = emptyMap(),
) {
    /** True when packets can leave this device at all (Wi-Fi, Ethernet, cellular or a tunnel). */
    val online: Boolean get() = facilities.any { it.transportsTraffic }

    fun has(facility: ConnectivityFacility): Boolean = facility in facilities

    /** The best local address inside [scope], which is what a plan tells the user to expect. */
    fun localAddress(scope: NetworkScope): LocalAddress? = addresses.filter { it.scope == scope }.minByOrNull { it.interfaceName }

    /** True when any local interface sits in a scope a route could use. */
    fun reachableScope(scope: NetworkScope): Boolean =
        when (scope) {
            NetworkScope.LOOPBACK -> has(ConnectivityFacility.LOOPBACK) || has(ConnectivityFacility.USB)
            NetworkScope.PUBLIC_INTERNET -> online
            else -> online || has(ConnectivityFacility.USB)
        }

    fun detailOf(facility: ConnectivityFacility): String = detail[facility].orEmpty()

    fun summary(): String {
        val names = facilities.sortedBy { it.name }.joinToString(", ") { it.name.lowercase() }
        return if (names.isBlank()) "no network facilities" else "network: $names"
    }

    companion object {
        /** Nothing measured: every question about the network is answered "unknown", not "no". */
        fun unknown(): ConnectivityReport = ConnectivityReport(emptySet())

        fun of(
            facilities: Iterable<ConnectivityFacility>,
            addresses: List<LocalAddress> = emptyList(),
            detail: Map<ConnectivityFacility, String> = emptyMap(),
        ): ConnectivityReport = ConnectivityReport(facilities.toSet(), addresses, detail)
    }
}

/**
 * One source of host-side network facts.
 *
 * Providers only *observe*. They never open a route, never talk to the target and never decide
 * policy - they answer "what does this device's networking look like right now", and the resolver
 * merges their answers. That keeps the Android-specific code (a `ConnectivityManager` query, an
 * `NsdManager` handle) away from the planning logic, which stays unit-testable.
 */
interface ConnectivityProvider {
    val id: String

    val facility: ConnectivityFacility

    /** The facility's state, or a report without it when the platform cannot answer (API level, permission). */
    suspend fun report(): ConnectivityReport
}

/**
 * One source of *routes to a target*.
 *
 * This is the plug-in point the architecture was missing: today the peer path knows exactly one way
 * to find a phone (mDNS), and would have to be edited to learn a second. A source that announces a
 * route - mDNS, a USB channel handing over its Wi-Fi address, a stored address, a user-typed one, a
 * future relay - is a new implementation of this interface and nothing else.
 *
 * [transportId] is which transport can actually *use* those endpoints; the resolver carries it into
 * every candidate so the plan never has to guess which channel an address belongs to.
 */
interface EndpointProvider {
    val id: String

    val transportId: String

    suspend fun endpoints(target: RemoteTarget): List<Endpoint>
}

/**
 * Facts in, routes out.
 *
 * The resolver is the whole "connection discovery" stage of the provisioning flow, and it is a pure
 * function over its providers: ask every provider, merge, de-duplicate by address/port keeping the
 * strongest evidence, rank by scope then evidence, and explain each one. No Android API is touched
 * here, which is why the ordering rules are unit-testable without a radio.
 */
class ConnectivityResolver(
    private val providers: List<ConnectivityProvider> = emptyList(),
    private val endpointProviders: List<EndpointProvider> = emptyList(),
) {
    val endpointSourceIds: List<String> get() = endpointProviders.map { it.id }

    /** Merges every provider's answer. A provider that throws is reported, not allowed to break the rest. */
    suspend fun report(): ConnectivityReport {
        val facilities = linkedSetOf<ConnectivityFacility>()
        val addresses = mutableListOf<LocalAddress>()
        val detail = mutableMapOf<ConnectivityFacility, String>()
        providers.forEach { provider ->
            val answer = runCatching { provider.report() }.getOrNull() ?: run {
                detail[provider.facility] = "${provider.id}: could not be read"
                return@forEach
            }
            if (answer.has(provider.facility)) {
                facilities += provider.facility
                detail[provider.facility] = answer.detailOf(provider.facility).ifBlank { "${provider.id}: up" }
            } else {
                detail[provider.facility] = answer.detailOf(provider.facility).ifBlank { "${provider.id}: down" }
            }
            facilities += answer.facilities
            addresses += answer.addresses
        }
        return ConnectivityReport(facilities, addresses.distinct(), detail)
    }

    /**
     * Every route anyone can offer to [target], strongest first.
     *
     * The reason string is built here rather than by the provider, so two providers describing the
     * same route are explained the same way, and a route's explanation always contains the two facts a
     * plan needs: where the knowledge came from and which network the address is on.
     */
    suspend fun routes(
        target: RemoteTarget,
        report: ConnectivityReport? = null,
    ): RouteCatalogue {
        // Address/port is the key, because two sources describing the same route are one route; the
        // best evidence wins, and the transport comes from the provider that offered it.
        val merged = linkedMapOf<String, Pair<Endpoint, String>>()
        endpointProviders.forEach { provider ->
            val offered = runCatching { provider.endpoints(target) }.getOrDefault(emptyList())
            offered.filter(Endpoint::usable).forEach { endpoint ->
                val existing = merged[endpoint.key]
                if (existing == null || endpoint.outranks(existing.first)) {
                    merged[endpoint.key] = endpoint to provider.transportId
                }
            }
        }
        val connectivity = report ?: report()
        val candidates =
            merged.values.map { (endpoint, transportId) ->
                val scope = endpoint.scope
                val reach = if (connectivity.reachableScope(scope)) "reachable" else "no local interface for it"
                RouteCandidate(
                    endpoint = endpoint,
                    transportId = transportId,
                    reason = "${endpoint.source.label} on ${scope.name.lowercase()}: $reach",
                )
            }
        return RouteCatalogue.of(candidates)
    }

    companion object {
        /** A resolver with nothing attached: it answers "unknown", never "no route". */
        fun empty(): ConnectivityResolver = ConnectivityResolver()
    }
}
