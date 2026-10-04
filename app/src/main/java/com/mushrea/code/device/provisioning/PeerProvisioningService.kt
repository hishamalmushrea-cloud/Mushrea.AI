package com.mushrea.code.device.provisioning

import com.mushrea.code.core.connectivity.ConnectivityReport
import com.mushrea.code.core.connectivity.ConnectivityResolver
import com.mushrea.code.core.connectivity.Endpoint
import com.mushrea.code.core.connectivity.EndpointProvider
import com.mushrea.code.core.connectivity.EndpointSource
import com.mushrea.code.core.connectivity.RemoteTarget
import com.mushrea.code.core.connectivity.RouteCandidate
import com.mushrea.code.core.peer.PeerDevice
import com.mushrea.code.core.provisioning.DeviceReadiness
import com.mushrea.code.core.provisioning.ProvisioningEngine
import com.mushrea.code.core.provisioning.ProvisioningPlanner
import com.mushrea.code.core.provisioning.ProvisioningReport
import com.mushrea.code.core.provisioning.ProvisioningRequest
import com.mushrea.code.core.provisioning.ProvisioningStatus
import com.mushrea.code.core.provisioning.ProvisioningStepKind
import com.mushrea.code.core.provisioning.StepOutcome
import com.mushrea.code.device.bridge.PeerAdbBridge
import com.mushrea.code.device.bridge.PeerAdbSession
import com.mushrea.code.device.bridge.PeerDeviceRegistry
import com.mushrea.code.device.bridge.PeerReconnectionManager
import com.mushrea.code.device.bridge.ReconnectPolicy
import com.mushrea.code.device.bridge.ReconnectionReport

/**
 * The one entry point for "set this phone up for remote work" and "get it back".
 *
 * It exists so the tool layer never has to assemble the flow itself: the engine, the host that carries
 * the steps out, and the reconnection ladder are wired here once, with the same [PeerAdbSession] and
 * [PeerAdbBridge] the rest of the peer platform uses. A second entry point would be a second policy
 * path, which is exactly what this architecture forbids - every command a provisioning step runs goes
 * through [PeerAdbBridge], and therefore through the Permission Center.
 */
class PeerProvisioningService(
    private val registry: PeerDeviceRegistry,
    private val session: PeerAdbSession,
    private val bridge: PeerAdbBridge,
    private val resolver: ConnectivityResolver,
) {
    private val host = DeviceProvisioningHost(registry, session, bridge, resolver)

    private val reconnection =
        PeerReconnectionManager(
            device = { serial -> registry.find(serial) },
            // Discovery here is cheap on purpose: what `adb` already knows is a live route, and the
            // expensive mDNS round belongs inside the connect attempt, not in a polling loop.
            discover = { device -> liveEndpoints(device) },
            connect = { device, endpoint -> connectOnce(device, endpoint) },
        )

    /** The live routes `adb` itself reports, as an endpoint source the resolver can use. */
    val liveEndpointProvider: EndpointProvider =
        object : EndpointProvider {
            override val id: String = "adb-live"
            override val transportId: String = "peer-adb-tcp"

            override suspend fun endpoints(target: RemoteTarget): List<Endpoint> =
                liveEndpoints(registry.find(target.identityKey))
        }

    /** Runs the full flow: discover, pair if needed, connect, verify, measure, persist, prove, remember. */
    suspend fun provision(request: ProvisioningRequest): ProvisioningReport =
        ProvisioningEngine(host).provision(request)

    /**
     * Tries to get a known device back, without pairing again.
     *
     * [endpoints] are extra routes the caller knows (a hand-over from another transport, an address the
     * user typed); the manager still prefers what worked before, then discovery, with bounded waits.
     */
    suspend fun reconnect(
        serial: String,
        policy: ReconnectPolicy = ReconnectPolicy.default,
        endpoints: List<Endpoint> = emptyList(),
    ): ReconnectionReport = reconnection.reconnect(serial, policy, endpoints)

    /**
     * What the platform knows about how this device can be reached - the read-only half of the flow,
     * useful to the agent before it decides anything.
     */
    suspend fun endpoints(serial: String): EndpointCatalogue {
        val device = registry.find(serial)
        val target = device?.remoteTarget() ?: RemoteTarget(identityKey = serial)
        val report = resolver.report()
        val catalogue = resolver.routes(target, report)
        val live = liveEndpoints(device)
        return EndpointCatalogue(
            serial = serial,
            candidates = catalogue.all,
            summary = catalogue.summary(),
            connectivity = report,
            known = device?.knownEndpoints.orEmpty(),
            live = live,
            readiness = device?.readiness ?: DeviceReadiness.DISCOVERED,
        )
    }

    /**
     * One connect attempt over one route.
     *
     * `adb connect` alone is not enough - [PeerAdbSession.connectAndVerify] is the same call the peer
     * tools use, so a route that is adopted here has been proven by a command. When the remembered
     * address is stale (Android hands out a new port every time the feature is toggled) the session's
     * own mDNS round is the one thing that can find the new one, so it runs as the last resort inside
     * this attempt rather than in a discovery loop of its own.
     */
    private suspend fun connectOnce(
        device: PeerDevice,
        endpoint: Endpoint,
    ): Result<PeerDevice> {
        val direct = session.connectAndVerify(endpoint.address, endpoint.port)
        if (direct.isSuccess) return direct.onSuccess { connected -> adopt(connected, endpoint) }
        return session.reconnect(device.serial).onSuccess { connected ->
            adopt(connected, Endpoint(connected.host, connected.port, EndpointSource.ANNOUNCED))
        }
    }

    private fun adopt(
        connected: PeerDevice,
        endpoint: Endpoint,
    ) {
        registry.rememberEndpoint(connected.serial, endpoint)
        registry.readiness(connected.serial, DeviceReadiness.CONNECTED)
    }

    /** The endpoints `adb devices -l` is currently holding, parsed from the `host:port` serials. */
    private suspend fun liveEndpoints(device: PeerDevice?): List<Endpoint> {
        val lines = runCatching { session.adbDevices() }.getOrDefault(emptyList())
        val parsed = lines.mapNotNull { line -> Endpoint.parse(line.serial, EndpointSource.ANNOUNCED) }
        if (device == null) return parsed
        // A remembered route that adb is holding right now is the strongest evidence there is.
        val known = device.endpoints().map { remembered -> remembered.key }
        return parsed.map { endpoint -> if (endpoint.key in known) endpoint.copy(source = EndpointSource.HANDOVER) else endpoint }
    }

    /** True when the last provisioning run ended with everything proven. */
    fun isProvisioned(report: ProvisioningReport): Boolean = report.status == ProvisioningStatus.PROVISIONED

    /** The step a report stopped at, for the agent's one-sentence answer. */
    fun blockedAt(report: ProvisioningReport): String {
        val step = report.needsUser ?: report.failure ?: return ""
        val outcome = step.outcome
        return when (outcome) {
            is StepOutcome.NeedsUser -> "${step.step.kind.name.lowercase()}: ${outcome.instruction}"
            is StepOutcome.Failed -> "${step.step.kind.name.lowercase()}: ${outcome.detail}"
            else -> step.step.kind.name.lowercase()
        }
    }

    /** The steps a run would attempt, so the UI can show them before anything is executed. */
    suspend fun preview(serial: String): List<ProvisioningStepKind> {
        val request = ProvisioningRequest(targetId = serial)
        return ProvisioningPlanner().plan(request, host.facts(request)).steps.map { it.kind }
    }
}

/** Everything the read-only endpoint query answers with. */
data class EndpointCatalogue(
    val serial: String,
    val candidates: List<RouteCandidate>,
    val summary: String,
    val connectivity: ConnectivityReport,
    val known: List<String>,
    val live: List<Endpoint>,
    val readiness: DeviceReadiness,
)
