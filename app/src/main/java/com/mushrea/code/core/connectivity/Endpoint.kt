package com.mushrea.code.core.connectivity

/**
 * Where an endpoint came from - the evidence behind it.
 *
 * The distinction matters more than it looks: an endpoint that is *announced* (mDNS), one that is
 * *remembered* (the address a previous session used), one that was *handed over* by a channel that
 * already works (a USB cable that just told us the phone's Wi-Fi address), and one the *user typed*
 * are four different levels of confidence, and a plan that cannot tell them apart cannot explain
 * itself. [confidence] is the ranking; the plan uses it, and the report prints it.
 */
enum class EndpointSource {
    /** Handed over by a channel that already answers - the strongest evidence short of a live command. */
    HANDOVER,

    /** Announced right now over mDNS (`_adb-tls-connect._tcp`). */
    ANNOUNCED,

    /** Remembered from a previous session with this identity. */
    REMEMBERED,

    /** Typed by the user, or carried in the request. */
    EXPLICIT,

    /** Anything a provider produced without saying how (never guessed by the platform itself). */
    PROVIDED,
    ;

    /** Lower is better. */
    val confidence: Int
        get() =
            when (this) {
                HANDOVER -> 0
                ANNOUNCED -> 1
                EXPLICIT -> 2
                REMEMBERED -> 3
                PROVIDED -> 4
            }

    /** The words a plan uses when it explains why it tried this route. */
    val label: String
        get() =
            when (this) {
                HANDOVER -> "handed over by a live channel"
                ANNOUNCED -> "announced on the network just now"
                EXPLICIT -> "named by the caller"
                REMEMBERED -> "remembered from the last session"
                PROVIDED -> "reported by a provider"
            }
}

/**
 * One way of reaching a target: an address, a port, and where the knowledge came from.
 *
 * There is deliberately no identity here. An endpoint is *how* to get there, never *who* is there -
 * keeping those apart is what lets the same phone be reached from a different network tomorrow under
 * the same identity, and what stops a stale address from being treated as the device itself.
 */
data class Endpoint(
    val address: String,
    val port: Int,
    val source: EndpointSource,
    val scope: NetworkScope = NetworkScope.of(address),
    /** What the provider called this route, for the report ("wlan0", "tailscale0", "usb"). */
    val via: String = "",
) {
    /** Stable key for de-duplication. Address and port only - two sources may describe one route. */
    val key: String get() = "${Endpoint.normalizeAddress(address)}:$port"

    val usable: Boolean get() = address.isNotBlank() && port in 1..65_535

    /** True when this is the same route as [other] but we learned it from a stronger source. */
    fun outranks(other: Endpoint): Boolean =
        if (key != other.key) {
            false
        } else {
            source.confidence < other.source.confidence
        }

    /** This route, remembered as something that used to work. */
    fun remembered(): Endpoint = copy(source = EndpointSource.REMEMBERED)

    override fun toString(): String = "$address:$port (${scope.name.lowercase()}, ${source.name.lowercase()})"

    companion object {
        fun normalizeAddress(address: String): String = NetworkScope.normalize(address)

        /** An endpoint parsed from `host:port`, as a stored or user-typed route looks. */
        fun parse(
            value: String,
            source: EndpointSource,
        ): Endpoint? {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return null
            val split = trimmed.lastIndexOf(':')
            if (split <= 0) return null
            val host = trimmed.substring(0, split).trim().removePrefix("[").removeSuffix("]")
            val port = trimmed.substring(split + 1).trim().toIntOrNull() ?: return null
            val endpoint = Endpoint(host, port, source)
            return endpoint.takeIf { it.usable }
        }
    }
}

/**
 * The route a plan would take, with the reasoning attached.
 *
 * [reason] is not decoration: it is what the agent prints when it says "I reached the phone over the
 * private network because its Wi-Fi address is not routable from here", and it is what makes a
 * fallback auditable after the fact.
 */
data class RouteCandidate(
    val endpoint: Endpoint,
    val transportId: String,
    val reason: String,
    /** Steps the caller has to take before this route can be attempted, if any. */
    val requirement: String = "",
) {
    val scope: NetworkScope get() = endpoint.scope
}

/**
 * The catalogue a plan chooses from: every route anyone could offer, de-duplicated and ranked.
 *
 * Ranking is by (scope, then evidence): the closest private route first, and within one scope the one
 * we have the best reason to believe in. Ties are broken by the order the providers were asked, so the
 * ordering is deterministic and a test can pin it.
 */
class RouteCatalogue(candidates: List<RouteCandidate>) {
    private val ordered = candidates.sortedWith(comparator)

    val all: List<RouteCandidate> get() = ordered

    val isEmpty: Boolean get() = ordered.isEmpty()

    fun best(): RouteCandidate? = ordered.firstOrNull()

    /** Everything the plan is willing to try after [used] failed, in order. */
    fun after(used: Collection<String>): List<RouteCandidate> = ordered.filterNot { it.endpoint.key in used }

    /** Routes that need the user to do something first, so a plan can ask for exactly those. */
    fun needingAction(): List<RouteCandidate> = ordered.filter { it.requirement.isNotBlank() }

    fun summary(): String =
        if (ordered.isEmpty()) {
            "no route to the target"
        } else {
            ordered.joinToString("; ") { candidate ->
                val needs = candidate.requirement.takeIf(String::isNotBlank)?.let { " (needs: $it)" }.orEmpty()
                "${candidate.endpoint} via ${candidate.transportId}$needs"
            }
        }

    companion object {
        private val comparator: Comparator<RouteCandidate> =
            compareBy<RouteCandidate> { it.endpoint.scope.rank }
                .thenBy { it.endpoint.source.confidence }
                .thenBy { it.transportId }

        fun of(candidates: List<RouteCandidate>): RouteCatalogue = RouteCatalogue(candidates)
    }
}

/**
 * What we are looking for, in the terms a discovery source needs.
 *
 * This is the interface between "which phone" and "how to reach it": the identity is the stable part
 * ([identityKey], plus the names a network announcement would carry), and the endpoints are hints that
 * may or may not still be true.
 */
data class RemoteTarget(
    val identityKey: String,
    val names: Set<String> = emptySet(),
    val hints: List<Endpoint> = emptyList(),
) {
    /** True when a discovery result could plausibly be this target. */
    fun matchesName(
        candidate: String,
        ignoreCase: Boolean = true,
    ): Boolean = names.any { name -> name.equals(candidate, ignoreCase = ignoreCase) }
}
