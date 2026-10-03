package com.mushrea.code.device.provisioning

import com.mushrea.code.core.connectivity.ConnectivityResolver
import com.mushrea.code.core.connectivity.Endpoint
import com.mushrea.code.core.connectivity.EndpointSource
import com.mushrea.code.core.connectivity.RouteCandidate
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.core.execution.ExecutionEffect
import com.mushrea.code.core.execution.ExecutionInvocation
import com.mushrea.code.core.execution.ExecutionOperation
import com.mushrea.code.core.execution.ExecutionPolicy
import com.mushrea.code.core.execution.ExecutionRequest
import com.mushrea.code.core.execution.ExecutionResult
import com.mushrea.code.core.execution.ExecutionTarget
import com.mushrea.code.core.execution.ExecutionTransport
import com.mushrea.code.core.peer.PeerDevice
import com.mushrea.code.core.peer.PeerDeviceState
import com.mushrea.code.core.peer.PeerIdentityDigest
import com.mushrea.code.core.peer.PeerTrust
import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.provisioning.DeviceReadiness
import com.mushrea.code.core.provisioning.ProvisioningFacts
import com.mushrea.code.core.provisioning.ProvisioningHost
import com.mushrea.code.core.provisioning.ProvisioningRequest
import com.mushrea.code.core.provisioning.ProvisioningStep
import com.mushrea.code.core.provisioning.ProvisioningStepKind
import com.mushrea.code.core.provisioning.StepOutcome
import com.mushrea.code.device.bridge.PeerAdbBridge
import com.mushrea.code.device.bridge.PeerAdbErrorClassifier
import com.mushrea.code.device.bridge.PeerAdbErrorCode
import com.mushrea.code.device.bridge.PeerAdbSession
import com.mushrea.code.device.bridge.PeerDeviceRegistry

/**
 * Carries provisioning steps out on this platform: mDNS, `adb pair`, `adb connect`, a probe, and the
 * settings writes that make an arrangement last.
 *
 * The split from [com.mushrea.code.core.provisioning.ProvisioningEngine] is deliberate. The engine owns
 * the *order* and the *classification*; this class owns the *plumbing*, and every piece of plumbing is
 * reused rather than re-implemented:
 *
 *  * connecting is [PeerAdbSession.connectAndVerify] - the same call the peer tools use, so a device
 *    reached by a provisioning step is verified exactly like one reached by `peer_connect`;
 *  * measuring is [PeerAdbBridge.refreshCapabilities] - one probe, one report, one place;
 *  * anything that *changes* the target - the persistence settings - is sent as an
 *    [ExecutionRequest] through the bridge, which means it passes the Permission Center and the
 *    provider like every other command. There is no second path to `adb` in this class.
 */
class DeviceProvisioningHost(
    private val registry: PeerDeviceRegistry,
    private val session: PeerAdbSession,
    private val bridge: PeerAdbBridge,
    private val resolver: ConnectivityResolver,
) : ProvisioningHost {
    override val id: String = "peer-provisioning"

    override suspend fun facts(request: ProvisioningRequest): ProvisioningFacts {
        val device = registry.find(request.targetId)
        val connectivity = resolver.report()
        val target =
            device?.remoteTarget()
                ?: com.mushrea.code.core.connectivity.RemoteTarget(
                    identityKey = request.targetId,
                    names = request.names,
                    hints = request.hints,
                )
        val catalogue = resolver.routes(target, connectivity)
        val routes = (catalogue.all + hintsAsCandidates(request)).distinctBy { it.endpoint.key }
        return ProvisioningFacts(
            targetId = request.targetId,
            readiness = readinessOf(device),
            trust = device?.trust ?: PeerTrust.UNKNOWN,
            capabilities = device?.capabilityReport() ?: CapabilityReport.unknown(),
            connectivity = connectivity,
            routes = routes,
            extra = mapOf("routes" to catalogue.summary()),
        )
    }

    /**
     * How far the device was taken, measured: a persisted level is only trusted when the thing it
     * claims can still be seen (a device that is not connected cannot be "identified" right now).
     */
    private fun readinessOf(device: PeerDevice?): DeviceReadiness {
        if (device == null) return DeviceReadiness.DISCOVERED
        val executed = device.readiness.atLeast(DeviceReadiness.EXECUTION_VERIFIED)
        val measured = device.capabilities.isNotEmpty() && device.readiness.atLeast(DeviceReadiness.CAPABILITIES_VERIFIED)
        val identified = device.identityKey.isNotBlank() && device.state != PeerDeviceState.DISCOVERED
        return DeviceReadiness.of(
            known = true,
            connected = device.connected || device.readiness.atLeast(DeviceReadiness.CONNECTED),
            identified = identified,
            measured = measured,
            executed = executed,
            trusted = device.trust.mayReconnect,
        )
    }

    private fun hintsAsCandidates(request: ProvisioningRequest): List<RouteCandidate> =
        request.hints.map { endpoint ->
            RouteCandidate(
                endpoint = endpoint,
                transportId = "peer-adb-tcp",
                reason = "given by the caller",
            )
        }

    override suspend fun run(
        step: ProvisioningStep,
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): StepOutcome =
        when (step.kind) {
            ProvisioningStepKind.DISCOVER -> StepOutcome.Completed(discoverDetail(facts))
            ProvisioningStepKind.PAIR -> pair(request, facts)
            ProvisioningStepKind.CONNECT -> connect(step, request, facts)
            ProvisioningStepKind.VERIFY -> verify(request)
            ProvisioningStepKind.CAPABILITIES -> capabilities(request)
            ProvisioningStepKind.ENABLE_REMOTE_ACCESS -> StepOutcome.NeedsUser(step.detail, step.instruction)
            ProvisioningStepKind.PERSIST -> persist(step, request)
            ProvisioningStepKind.TEST_EXECUTION -> testExecution(request)
            ProvisioningStepKind.REGISTER -> register(request)
        }

    private fun discoverDetail(facts: ProvisioningFacts): String {
        val routes = facts.routes
        return if (routes.isEmpty()) {
            "nothing announces ${facts.targetId} and no remembered address survives"
        } else {
            routes.joinToString("; ") { candidate -> "${candidate.endpoint} via ${candidate.transportId} - ${candidate.reason}" }
        }
    }

    /** Pairing with a code the user read off the target screen. The QR path is the UI's job, not this one. */
    private suspend fun pair(
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): StepOutcome {
        val code = request.pairingCode?.trim().orEmpty()
        if (code.isEmpty()) {
            return StepOutcome.NeedsUser("a pairing code is required the first time", "")
        }
        val candidate = pairingEndpoint(request, facts)
            ?: return StepOutcome.Failed("no pairing address/port was supplied with the code")
        val result = session.pairWithCode(candidate.address, candidate.port, code)
        val device = result.getOrNull()
        return if (device == null) {
            StepOutcome.Failed(result.exceptionOrNull()?.message ?: "pairing was refused", PeerAdbErrorCode.PAIRING_FAILED.name)
        } else {
            registry.trust(device.serial, PeerTrust.TOFU)
            registry.rememberEndpoint(device.serial, candidate.copy(source = EndpointSource.HANDOVER))
            StepOutcome.Completed("the phone accepted our key", evidence = "paired as ${device.serial}")
        }
    }

    /**
     * Pairing needs the *pairing* port, which is a different mDNS service than the connect one, so the
     * caller's hints are the only reliable source: that is exactly why the flow asks the user for the
     * address shown next to the code.
     */
    private fun pairingEndpoint(
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): Endpoint? = request.hints.firstOrNull { it.usable } ?: facts.routes.firstOrNull()?.endpoint

    /**
     * Opens the channel, trying the best route first and the remembered one after it.
     *
     * A connect that returns without a command having answered is not a connect: [PeerAdbSession]
     * already enforces that, so success here means a probe came back - and only then is the route
     * written to the registry as one that works.
     */
    private suspend fun connect(
        step: ProvisioningStep,
        request: ProvisioningRequest,
        facts: ProvisioningFacts,
    ): StepOutcome {
        val failures = mutableListOf<String>()
        val attempts = (facts.routes.map { it.endpoint } + request.hints).distinctBy(Endpoint::key)
        for (endpoint in attempts) {
            val result = session.connectAndVerify(endpoint.address, endpoint.port)
            val device = result.getOrNull()
            if (device != null) {
                registry.rememberEndpoint(device.serial, endpoint)
                registry.readiness(device.serial, DeviceReadiness.CONNECTED)
                return StepOutcome.Completed(
                    "opened ${endpoint.address}:${endpoint.port} and a command answered",
                    evidence = "${device.serial} (${device.model.ifBlank { "unknown model" }})",
                )
            }
            failures += result.exceptionOrNull()?.message ?: "connect failed"
        }
        // Nothing announced or remembered worked; let the session do its own discovery round before
        // giving up, which is where a changed port is noticed.
        val viaDiscovery = session.reconnect(request.targetId)
        val device = viaDiscovery.getOrNull()
        if (device != null) {
            registry.readiness(device.serial, DeviceReadiness.CONNECTED)
            return StepOutcome.Completed("rediscovered the device and a command answered", evidence = device.serial)
        }
        failures += viaDiscovery.exceptionOrNull()?.message ?: "discovery found nothing"
        return if (attempts.isEmpty() && failures.size <= 1) {
            StepOutcome.Unsupported("no route to ${request.targetId} exists yet; nothing was attempted")
        } else {
            StepOutcome.Failed(failures.joinToString(" | "), PeerAdbErrorCode.ADB_CONNECT_FAILED.name)
        }
    }

    /**
     * The identity of the device, proved by a command that answered.
     *
     * This is the rung that keeps a socket from being called a device: a probe that comes back carries
     * the serial, the model and the build, and the digest over them becomes the identity the registry
     * remembers - so the next session recognises the phone even if its address changed.
     */
    private suspend fun verify(request: ProvisioningRequest): StepOutcome {
        val device = registry.find(request.targetId)
            ?: return StepOutcome.Failed("no device is registered as '${request.targetId}'", PeerAdbErrorCode.DEVICE_OFFLINE.name)
        val probe = runCatching { session.probe(device.serial) }.getOrElse { throwable ->
            return StepOutcome.Failed(
                throwable.message ?: "the device did not answer",
                PeerAdbErrorClassifier.classify(throwable.message.orEmpty()).code.name,
            )
        }
        if (!probe.reachable) {
            return StepOutcome.Failed("${device.serial} is listed but no command came back", PeerAdbErrorCode.ADB_CONNECT_FAILED.name)
        }
        val identityKey = PeerIdentityDigest.of(device.serial, probe.identity)
        registry.remember(
            device.copy(
                identityKey = identityKey,
                model = probe.identity.model,
                manufacturer = probe.identity.manufacturer,
                androidVersion = probe.identity.androidVersion,
                sdk = probe.identity.sdk,
                abi = probe.identity.abi,
            ),
        )
        registry.readiness(device.serial, DeviceReadiness.IDENTIFIED)
        return StepOutcome.Completed(
            "the device answered as ${probe.identity.model.ifBlank { device.serial }}",
            evidence = "identity $identityKey",
        )
    }

    private suspend fun capabilities(request: ProvisioningRequest): StepOutcome {
        val measured = bridge.refreshCapabilities(request.targetId).getOrNull()
            ?: return StepOutcome.Failed("the capability probe did not complete", PeerAdbErrorCode.UNKNOWN_FAILURE.name)
        registry.readiness(request.targetId, DeviceReadiness.CAPABILITIES_VERIFIED)
        val shell = measured.status(com.mushrea.code.core.execution.CapabilityNames.SHELL)
        return if (shell == CapabilityStatus.MISSING) {
            StepOutcome.Failed("the device reported no shell, so nothing can be run on it", PeerAdbErrorCode.UNKNOWN_FAILURE.name)
        } else {
            StepOutcome.Completed("measured the device", evidence = measured.summary())
        }
    }

    /**
     * One command that changes the target, sent the only way this app changes a target: as an
     * [ExecutionRequest] through the bridge, which means the Permission Center sees it first.
     *
     * A step whose program does not exist on the device is [StepOutcome.Unsupported] - a fact about the
     * device - while a step that was refused or failed is [StepOutcome.Failed] with the reason.
     */
    private suspend fun persist(
        step: ProvisioningStep,
        request: ProvisioningRequest,
    ): StepOutcome {
        val command =
            when (step.id) {
                "persist-wifi" -> "settings put global wifi_sleep_policy 2"
                "persist-awake" -> "settings put global stay_on_while_plugged_in 3"
                "persist-port" -> "tcpip 5555"
                else -> return StepOutcome.Skipped("nothing to write for ${step.id}")
            }
        val result = bridge.execute(
            ExecutionRequest(
                operation = ExecutionOperation.SHELL,
                target = ExecutionTarget(request.targetId, ExecutionTransport.PEER_ADB),
                invocation = ExecutionInvocation(command),
                effect = ExecutionEffect(mutatesTarget = true, risk = PermissionRisk.MEDIUM),
                policy = ExecutionPolicy(reason = "provisioning: ${step.title}"),
            ),
        )
        return classify(result, step)
    }

    private fun classify(
        result: ExecutionResult,
        step: ProvisioningStep,
    ): StepOutcome {
        val text = (result.stderr + " " + result.message + " " + result.failureReason).lowercase()
        return when {
            result.ok -> StepOutcome.Completed("${step.title} applied", evidence = result.stdout.trim().take(200))
            result.rejected -> StepOutcome.Failed(result.message.ifBlank { "refused by policy" }, result.errorCode.orEmpty())
            text.contains("not found") || text.contains("inaccessible") || text.contains("not allowed") ->
                StepOutcome.Unsupported("the device refused: ${result.stderr.trim().ifBlank { result.message }}")
            else -> {
                val reason = result.failureReason.ifBlank { result.message.ifBlank { "${step.id} failed" } }
                StepOutcome.Failed(reason, result.errorCode.orEmpty())
            }
        }
    }

    /** One read-only command through the whole path: policy, provider, adb - and a verified stage. */
    private suspend fun testExecution(request: ProvisioningRequest): StepOutcome {
        val result = bridge.execute(
            ExecutionRequest(
                operation = ExecutionOperation.PROBE,
                target = ExecutionTarget(request.targetId, ExecutionTransport.PEER_ADB),
                invocation = ExecutionInvocation("getprop", listOf("ro.product.model")),
                effect = ExecutionEffect(mutatesTarget = false),
                policy = ExecutionPolicy(reason = "provisioning: prove execution", verify = true),
            ),
        )
        return if (result.ok) {
            registry.readiness(request.targetId, DeviceReadiness.EXECUTION_VERIFIED)
            StepOutcome.Completed(
                "a command ran end to end",
                evidence = "stage=${result.stage} provider=${result.providerId} model=${result.stdout.trim()}",
            )
        } else {
            StepOutcome.Failed(
                result.failureReason.ifBlank { result.message.ifBlank { "the command did not complete" } },
                result.errorCode.orEmpty(),
            )
        }
    }

    /**
     * Writes the row the next session starts from: identity, trust, transport, the route that worked,
     * and the level that was actually proven.
     */
    private suspend fun register(request: ProvisioningRequest): StepOutcome {
        val device = registry.find(request.targetId)
            ?: return StepOutcome.Skipped("nothing to remember")
        val identityKey = device.identityKey.ifBlank { PeerIdentityDigest.of(device.serial) }
        val measured = readinessOf(device)
        val level = if (measured.ordinal > device.readiness.ordinal) measured else device.readiness
        val remembered =
            registry.remember(
                device.copy(
                    identityKey = identityKey,
                    readiness = level,
                    transportId = device.transportId.ifBlank { "peer-adb-tcp" },
                    trust = device.trust.takeIf { it != PeerTrust.UNKNOWN } ?: PeerTrust.TOFU,
                ),
            )
        val known = remembered.knownEndpoints.size
        val levelName = remembered.readiness.name.lowercase()
        return StepOutcome.Completed(
            "remembered ${remembered.label}",
            evidence = "identity $identityKey, readiness $levelName, routes $known",
        )
    }
}
