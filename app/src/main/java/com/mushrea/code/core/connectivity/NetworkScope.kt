package com.mushrea.code.core.connectivity

/**
 * Where an address lives, from the point of view of "is it safe and sensible to run adb against it?".
 *
 * This exists because a transport is not an identity and an address is not a network: the same phone
 * can be reachable as `192.168.1.20:37123` on the sitting-room Wi-Fi, as `100.101.102.103:37123` over
 * a private overlay from another country, or as `127.0.0.1:5555` through a USB-bootstrapped loopback
 * - and those three are not equally good places to send a shell command. The platform has to *know*
 * which one it is using, and has to be able to say why it chose it.
 *
 * The classification is deliberately literal: it reads the address, not the interface name, because
 * the address is the only thing the transport actually gets. A provider that *does* know its interface
 * (`tailscale0`, `tun0`, `wlan0`) can pass its own verdict - that is the `interfaceName` overload - and
 * the two answers are reconciled by taking the more specific one.
 */
enum class NetworkScope {
    /**
     * Addresses on the device itself. Reached without leaving the machine, so nothing on the network
     * can see the traffic. This is where a USB-bootstrapped `tcpip:` listener lands.
     */
    LOOPBACK,

    /** The neighbour subnet that is not routed elsewhere: `169.254/16`, `fe80::/10`. */
    LOCAL_LINK,

    /** A private network we are attached to: RFC1918, IPv6 ULA. Encrypted only by the link itself. */
    LAN,

    /**
     * A private *overlay* network (WireGuard-class: Tailscale, Headscale, an enterprise VPN). Private
     * addresses that are routed across the public internet by an encrypted tunnel, which is why they
     * rank above [PUBLIC_INTERNET] but below a direct [LAN].
     */
    PRIVATE_OVERLAY,

    /** A globally routable address. Sending adb there is what this architecture refuses by default. */
    PUBLIC_INTERNET,

    /** Nothing we can classify - a hostname, a malformed literal, an empty string. */
    UNKNOWN,
    ;

    /** Lower is preferred. Order: loopback, link-local, LAN, overlay, public, unknown. */
    val rank: Int
        get() =
            when (this) {
                LOOPBACK -> 0
                LOCAL_LINK -> 1
                LAN -> 2
                PRIVATE_OVERLAY -> 3
                PUBLIC_INTERNET -> 4
                UNKNOWN -> 5
            }

    /** True when the address is not reachable from the open internet. */
    val private: Boolean get() = this == LOOPBACK || this == LOCAL_LINK || this == LAN || this == PRIVATE_OVERLAY

    /**
     * True when reaching it means the packet crosses a network we do not control.
     *
     * It is not a refusal by itself - the overlay case is exactly that, and it is supported - but it is
     * the fact a plan has to name when it decides a route is acceptable ("over a tunnel the user
     * already trusts") or not ("that is a public address; enable the private network first").
     */
    val crossesPublicNetwork: Boolean get() = this == PRIVATE_OVERLAY || this == PUBLIC_INTERNET

    companion object {
        /**
         * The scope of an address literal, or [UNKNOWN] when it is a name we cannot classify.
         *
         * A hostname is deliberately *not* resolved here: DNS is a network operation with its own
         * failure modes and its own privacy weight, and a resolver answer would be a guess about a
         * network we have not looked at. The provider that owns the route does the resolving and passes
         * the resulting literal back through this function.
         */
        fun of(address: String): NetworkScope = of(address, interfaceName = "")

        /**
         * The scope of an address, with what the provider knows about the interface it came from.
         *
         * The interface name breaks the ties the address cannot: `tailscale0` and `tun0` carry a
         * private-looking address that actually reaches across the internet, while `wlan0` with the
         * same-looking address is the sitting-room network.
         */
        fun of(
            address: String,
            interfaceName: String,
        ): NetworkScope {
            val literal = normalize(address)
            val byAddress = byAddress(literal)
            val byInterface = byInterface(interfaceName)
            // An interface that is *known* to be a tunnel wins over a private literal; an interface we
            // cannot name changes nothing, and a literal we understand wins over an unknown interface.
            return when {
                byInterface == PRIVATE_OVERLAY -> PRIVATE_OVERLAY
                byAddress != UNKNOWN -> byAddress
                else -> byInterface
            }
        }

        /**
         * Strips the decorations a socket address arrives with: `[::1]:5555`, `fe80::1%wlan0`,
         * `192.168.1.20:37123`. A name that is not a literal comes back as it is.
         */
        fun normalize(address: String): String {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.startsWith("[")) {
                val end = trimmed.indexOf(']')
                if (end > 0) return trimmed.substring(1, end).substringBefore('%')
            }
            val withoutZone = trimmed.substringBefore('%')
            val colon = withoutZone.lastIndexOf(':')
            // Only `host:port` when there is exactly one colon and what follows it is a port; an IPv6
            // literal has more colons, and splitting it would destroy the address.
            val singleColon = colon > 0 && withoutZone.indexOf(':') == colon
            if (singleColon) {
                val port = withoutZone.substring(colon + 1)
                if (port.toIntOrNull() != null) return withoutZone.substring(0, colon)
            }
            return withoutZone
        }

        private fun byAddress(literal: String): NetworkScope {
            val lowered = literal.lowercase()
            // An IPv4-mapped IPv6 address is an IPv4 address, whichever spelling arrived.
            if (lowered.startsWith("::ffff:")) return byAddress(lowered.removePrefix("::ffff:"))
            val groups = literal.split('.')
            if (groups.size == 4 && groups.all { it.toIntOrNull() in 0..255 }) {
                val first = groups[0].toInt()
                val second = groups[1].toInt()
                return when {
                    first == 127 -> LOOPBACK
                    first == 169 && second == 254 -> LOCAL_LINK
                    first == 10 -> LAN
                    first == 192 && second == 168 -> LAN
                    first == 172 && second in 16..31 -> LAN
                    // 100.64.0.0/10 is what Tailscale and Headscale hand out; it is private, but it is
                    // private *because a tunnel routes it*, and calling it LAN would hide that.
                    first == 100 && second in 64..127 -> PRIVATE_OVERLAY
                    else -> PUBLIC_INTERNET
                }
            }
            return when {
                lowered == "::1" -> LOOPBACK
                lowered.startsWith("fe8") || lowered.startsWith("fe9") -> LOCAL_LINK
                lowered.startsWith("fea") || lowered.startsWith("feb") -> LOCAL_LINK
                // Tailscale's IPv6 ULA, the v6 twin of 100.64/10.
                lowered.startsWith("fd7a:115c:a1e0") -> PRIVATE_OVERLAY
                lowered.startsWith("fc") || lowered.startsWith("fd") -> LAN
                lowered.contains(':') -> PUBLIC_INTERNET
                else -> UNKNOWN
            }
        }

        private fun byInterface(interfaceName: String): NetworkScope {
            val lowered = interfaceName.lowercase()
            return when {
                lowered.isBlank() -> UNKNOWN
                lowered == "lo" -> LOOPBACK
                lowered.startsWith("tun") || lowered.startsWith("tap") || lowered.startsWith("wg") -> PRIVATE_OVERLAY
                lowered.startsWith("tailscale") -> PRIVATE_OVERLAY
                lowered.startsWith("wlan") || lowered.startsWith("eth") || lowered.startsWith("ap") -> LAN
                lowered.startsWith("rmnet") || lowered.startsWith("ccmni") || lowered.startsWith("pdp") -> PUBLIC_INTERNET
                else -> UNKNOWN
            }
        }
    }
}
