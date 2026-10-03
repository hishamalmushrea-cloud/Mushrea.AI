package com.mushrea.code.core.execution

/**
 * A way of running things.
 *
 * This is the extension point the architecture was missing: today every device tool is a branch in
 * `DeviceAgentBridge`, so a new capability means editing the bridge, the catalog, the firewall and
 * the agent's tool table together. A provider instead declares what it can do and answers requests;
 * adding a transport (a second phone over ADB, the local runtime, a remote shell, an HTTP endpoint,
 * a future protocol) is adding an implementation, not re-cutting the bridge.
 *
 * The contract is deliberately small, because the interesting decisions are not here:
 *  * **policy** is decided before a provider is called (the Permission Center, from the request's
 *    [ExecutionEffect] and the caller's context);
 *  * **capability** is decided by the target (a [CapabilityReport] the provider fills in), not
 *    assumed from the provider's name;
 *  * **planning** is the [ExecutionPlanner]'s job, using the two above.
 *
 * A provider therefore only has to be honest: report what it supports, report what it needs, and
 * never turn a failure into an empty success.
 */
interface ExecutionProvider {
    /** Stable id, used in plans and in the execution log. */
    val id: String

    /** A short human name for the plan and the confirmation prompt. Defaults to [id]. */
    val label: String get() = id

    /** The one channel this provider serves. */
    val transport: ExecutionTransport

    /** True when this provider can run [operation] at all (before any target is considered). */
    fun supports(operation: ExecutionOperation): Boolean

    /**
     * Capabilities the target must report before this operation is worth attempting.
     *
     * Empty by default - a provider that cannot be sure says nothing rather than guessing. The names
     * are [CapabilityNames] constants, so a planner can compare two providers without knowing either.
     */
    fun requirements(operation: ExecutionOperation): Set<String> = emptySet()

    /**
     * An operation that reaches the same result when [missing] capability is absent.
     *
     * This is what turns "the device has no `exec-out`" from a dead end into a supported path: the
     * provider knows its own fallbacks, the planner only has to ask.
     */
    fun alternative(
        operation: ExecutionOperation,
        missing: String,
    ): ExecutionOperation? = null

    /** Runs the request, or reports why it could not. Must never throw for an expected failure. */
    suspend fun execute(request: ExecutionRequest): ExecutionResult

    /**
     * Reads the target's capabilities. Providers that cannot probe anything return
     * [CapabilityReport.unknown] rather than an empty report, which would read as "nothing works".
     */
    suspend fun capabilities(target: ExecutionTarget): CapabilityReport = CapabilityReport.unknown()

    /**
     * The recipes this provider can carry out beyond [supports], if it wants to add any.
     *
     * A provider that speaks a protocol with a fixed vocabulary (an HTTP endpoint, a script host)
     * can contribute its own recipes; the planner simply merges them. The default is none: a
     * transport provider executes whatever it is handed.
     */
    fun recipes(): List<ExecutionRecipe> = emptyList()

    /** Every operation this provider serves, for the provider listing a UI or a plan shows. */
    fun operations(): Set<ExecutionOperation> = ExecutionOperation.entries.filter(::supports).toSet()

    /** The capability this provider answers [operation] with - what a result names in its route. */
    fun capabilityFor(operation: ExecutionOperation): String = requirements(operation).firstOrNull().orEmpty()
}

/**
 * The providers the platform can choose from, and why one of them was chosen.
 *
 * Keeping the selection here (instead of "first provider that matches" inside the bridge) is what
 * makes the choice explainable and testable: the reason travels with the choice, so a plan can say
 * "USB, because the peer transport has no route to this device" rather than silently picking one.
 */
class ExecutionProviderRegistry(private val providers: List<ExecutionProvider>) {
    val all: List<ExecutionProvider> get() = providers

    fun byId(id: String): ExecutionProvider? = providers.firstOrNull { it.id == id }

    fun forTransport(transport: ExecutionTransport): List<ExecutionProvider> =
        providers.filter { it.transport == transport }

    /**
     * Picks the provider for one operation on one transport.
     *
     * A pinned id wins when it can actually serve the request - a caller that names a provider gets it
     * or an explicit refusal, never a silent substitution. Otherwise the first provider that serves
     * the transport and supports the operation is chosen, and every other provider's rejection is
     * recorded so the plan can explain the choice.
     */
    fun select(
        transport: ExecutionTransport,
        operation: ExecutionOperation,
        providerId: String? = null,
    ): ProviderChoice {
        if (providerId != null) {
            val pinned = byId(providerId)
                ?: return ProviderChoice(
                    provider = null,
                    reason = "no provider '$providerId' is registered",
                    considered = providers.map { "${it.id}: registered, transport ${it.transport}" },
                )
            val rejected =
                when {
                    pinned.transport != transport -> "it serves ${pinned.transport}, not $transport"
                    !pinned.supports(operation) -> "it cannot $operation"
                    else -> null
                }
            return if (rejected == null) {
                ProviderChoice(pinned, "pinned by the caller", emptyList())
            } else {
                ProviderChoice(null, "the pinned provider $providerId cannot serve this request: $rejected", listOf(rejected))
            }
        }
        val considered = mutableListOf<String>()
        providers.forEach { provider ->
            when {
                provider.transport != transport -> considered += "${provider.id}: serves ${provider.transport}"
                provider.supports(operation) -> {
                    considered += "${provider.id}: chosen"
                    return ProviderChoice(
                        provider = provider,
                        reason = "${provider.label} serves $operation over $transport",
                        considered = considered.dropLast(1),
                    )
                }
                else -> considered += "${provider.id}: does not serve $operation"
            }
        }
        return ProviderChoice(
            provider = null,
            reason = "no provider serves $operation over $transport",
            considered = considered,
        )
    }
}

/** The outcome of [ExecutionProviderRegistry.select]: the provider, why, and what was rejected. */
data class ProviderChoice(
    val provider: ExecutionProvider?,
    val reason: String,
    val considered: List<String> = emptyList(),
) {
    val available: Boolean get() = provider != null
}
