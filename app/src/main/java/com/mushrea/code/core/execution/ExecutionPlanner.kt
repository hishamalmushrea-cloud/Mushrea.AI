package com.mushrea.code.core.execution

import java.util.UUID

/** What the caller wants done, before anything has decided whether it is possible. */
data class PlanIntent(
    val operation: ExecutionOperation,
    val transport: ExecutionTransport,
    val description: String = "",
    val verify: Boolean = false,
    val providerId: String? = null,
    /** The command or file pair the step carries; empty when the caller only wants the route. */
    val invocation: ExecutionInvocation = ExecutionInvocation(""),
    val effect: ExecutionEffect = ExecutionEffect(mutatesTarget = false),
    val reason: String = "",
    val timeoutMillis: Long = 30_000,
)

/**
 * One resolved step: a provider that exists, an operation it supports, and what runs.
 *
 * The step carries its own [invocation] and [effect] so the executor never has to re-derive what the
 * plan already decided: policy weighs the step's effect, the provider turns the step's invocation
 * into a transport line, and neither of them needs to know where the step came from.
 */
data class PlanStep(
    val providerId: String,
    val transport: ExecutionTransport,
    val operation: ExecutionOperation,
    val description: String,
    val requirements: Set<String>,
    val verify: Boolean,
    val rewrittenFrom: ExecutionOperation? = null,
    /** Round 2: what runs, what it costs, and which capability it answers with. */
    val invocation: ExecutionInvocation = ExecutionInvocation(""),
    val effect: ExecutionEffect = ExecutionEffect(mutatesTarget = false),
    val capability: String = "",
    val candidateId: String = "",
    val recipeId: String = "",
    val capabilityRequirements: List<CapabilityRequirement> = emptyList(),
    /** Requirements nothing measured: attempted, and reported as attempted. */
    val unproven: List<String> = emptyList(),
    /** The read-only follow-up this step wants run to prove its effect, when it has one. */
    val verifyCommand: String = "",
    /** Why this route was chosen over another one (a skipped candidate, a rewritten operation). */
    val fallbackReason: String = "",
    val reason: String = "",
    val timeoutMillis: Long = 30_000,
) {
    /** This step as a request. Policy is the caller's, because only the caller knows the user's answer. */
    fun request(
        target: ExecutionTarget,
        policy: ExecutionPolicy = ExecutionPolicy(),
        correlationId: String = UUID.randomUUID().toString(),
    ): ExecutionRequest =
        ExecutionRequest(
            operation = operation,
            target = target,
            invocation = invocation,
            effect = effect,
            policy =
                policy.copy(
                    reason = policy.reason.ifBlank { reason },
                    timeoutMillis = timeoutMillis,
                    verify = policy.verify || verify,
                ),
            correlationId = correlationId,
            providerId = providerId,
            // The route the plan resolved travels with the request: it is what the decision names and
            // what the result reports, so a planned `bin:pm` step cannot come back as a plain `shell`.
            capabilityHint = capability,
        )
}

/** Why a step could not be planned, in the words the caller has to act on. */
data class PlanBlocker(
    val operation: ExecutionOperation,
    val transport: ExecutionTransport,
    val capability: String?,
    val reason: String,
    /** What the caller can do about it, when the planner can tell (a parameter, another recipe). */
    val hint: String = "",
)

/**
 * A plan is feasible only when every step resolved; a partial plan is never executed silently.
 *
 * [candidates] is the reasoning trail: every route the planner considered, with the reason it was
 * taken or skipped. That is what lets the agent explain "I listed packages with `pm` because this
 * phone has no `cmd`" instead of presenting a command out of nowhere.
 */
data class ExecutionPlan(
    val steps: List<PlanStep>,
    val blockers: List<PlanBlocker>,
    val goal: String = "",
    val recipeId: String = "",
    val providerId: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val candidates: List<String> = emptyList(),
) {
    val feasible: Boolean get() = blockers.isEmpty() && steps.isNotEmpty()

    /** One line naming what stopped the plan, for the agent and for the user. */
    fun blockedReason(): String =
        blockers.joinToString("; ") { blocker ->
            blocker.capability?.let { "$it: ${blocker.reason}" } ?: "${blocker.operation}: ${blocker.reason}"
        }

    /** The plan as the agent sees it: the route, the steps, and what was rejected on the way. */
    fun summary(): String {
        if (!feasible) return "blocked - ${blockedReason()}"
        val route = steps.joinToString(" then ") { step -> step.description.ifBlank { step.operation.name } }
        return "$route${if (providerId.isNotBlank()) " via $providerId" else ""}"
    }
}

/**
 * Turns intents and goals into steps a provider can actually run.
 *
 * The planner is deliberately not an AI. Deciding *what* to achieve is the agent's job; deciding
 * whether the chosen path exists on this device is a fact question, and answering it with facts is
 * what keeps the agent from inventing commands a phone without `cmd` or `python3` cannot run. So the
 * planner does four things and no more:
 *
 *  1. **finds the route** - an explicit [ExecutionGoal.recipeId], or the recipe whose keywords match
 *     the goal in words, or the provider that serves a bare operation;
 *  2. **picks the candidate** whose required capabilities the target actually reported - a capability
 *     reported missing skips that route and the next one is tried, with the skip recorded;
 *  3. **checks the provider** serves every operation the candidate needs, and rewrites the operation
 *     when a provider knows a fallback (a device without `exec-out` still runs `sh`);
 *  4. **blocks honestly** when nothing is left, naming the capability that is missing - which is the
 *     difference between "unsupported" and "this phone has no `uiautomator`".
 *
 * A plan with any blocker is not feasible; the caller decides whether to drop the step, ask the user,
 * or choose another goal. Nothing here executes, prompts or persists, and the planner keeps no state:
 * the same goal and the same report always give the same plan. Nothing here is limited to the tools
 * the product ships either - a recipe is data, and an operation with no recipe at all still resolves
 * through the provider that serves its operation.
 */
class ExecutionPlanner(
    private val providers: ExecutionProviderRegistry,
    private val recipes: RecipeRegistry = ExecutionRecipes.registry,
) {
    constructor(providers: List<ExecutionProvider>) : this(ExecutionProviderRegistry(providers))

    /** A provider list plus a catalogue: what a test (or a plugin host) uses to extend the recipes. */
    constructor(
        providers: List<ExecutionProvider>,
        recipes: RecipeRegistry,
    ) : this(ExecutionProviderRegistry(providers), recipes)

    /** Every provider, for a listing or a test. */
    val registeredProviders: List<ExecutionProvider> get() = providers.all

    /** The recipe catalogue, as the agent's tool result prints it. */
    fun catalogue(): List<ExecutionRecipe> = recipes.all

    /** The recipe a goal names or describes, if any. */
    fun recipeFor(goal: ExecutionGoal): ExecutionRecipe? =
        goal.recipeId?.let(recipes::byId) ?: recipes.match(goal.description)

    /** The provider that would serve [intent], or null when none does. */
    fun providerFor(intent: PlanIntent): ExecutionProvider? =
        providers.select(intent.transport, intent.operation, intent.providerId).provider

    /** Resolves one intent, or explains why it cannot be resolved. */
    fun step(
        intent: PlanIntent,
        capabilities: CapabilityReport,
    ): Result<PlanStep> {
        val choice = providers.select(intent.transport, intent.operation, intent.providerId)
        val provider = choice.provider ?: return Result.failure(IllegalStateException(choice.reason))
        return resolveStep(intent, provider, capabilities, choice.reason)
    }

    /**
     * The capability half of [step]: what the target can do decides the operation that is planned.
     *
     * Only a *measured* missing capability blocks or rewrites a step. `UNKNOWN` means the probe never
     * answered, which is not evidence against the device - the provider then reports the real failure
     * instead of the platform inventing an impossibility.
     */
    private fun resolveStep(
        intent: PlanIntent,
        provider: ExecutionProvider,
        capabilities: CapabilityReport,
        providerReason: String = "",
    ): Result<PlanStep> {
        val required = provider.requirements(intent.operation)
        val missing = required.firstOrNull { capabilities.missing(it) }
        val alternative = missing?.let { provider.alternative(intent.operation, it) }?.takeIf(provider::supports)
        if (missing != null && alternative == null) {
            return Result.failure(MissingCapabilityException(intent.operation, missing))
        }
        val operation = alternative ?: intent.operation
        val effective = provider.requirements(operation)
        return Result.success(
            PlanStep(
                providerId = provider.id,
                transport = provider.transport,
                operation = operation,
                description = intent.description,
                requirements = effective,
                verify = intent.verify,
                rewrittenFrom = alternative?.let { intent.operation },
                invocation = intent.invocation,
                effect = intent.effect,
                capability = effective.firstOrNull().orEmpty(),
                unproven = effective.filter { capabilities.unproven(it) },
                fallbackReason =
                    if (alternative == null) {
                        ""
                    } else {
                        "$missing is missing on this target; $alternative reaches the same result"
                    },
                reason = intent.reason.ifBlank { providerReason },
                timeoutMillis = intent.timeoutMillis,
            ),
        )
    }

    /** Resolves every intent; blockers do not stop the others from resolving. */
    fun plan(
        intents: List<PlanIntent>,
        capabilities: CapabilityReport,
    ): ExecutionPlan {
        val steps = mutableListOf<PlanStep>()
        val blockers = mutableListOf<PlanBlocker>()
        intents.forEach { intent ->
            step(intent, capabilities).fold(
                onSuccess = { steps.add(it) },
                onFailure = { failure ->
                    val missing = (failure as? MissingCapabilityException)?.capability
                    blockers.add(
                        PlanBlocker(
                            operation = intent.operation,
                            transport = intent.transport,
                            capability = missing,
                            reason = failure.message ?: "provider refused the step",
                        ),
                    )
                },
            )
        }
        return ExecutionPlan(steps = steps, blockers = blockers, providerId = steps.firstOrNull()?.providerId.orEmpty())
    }

    /**
     * Resolves a **goal** - in words, or naming a recipe - against what the target reported.
     *
     * This is the general path: the agent says what it wants (or which recipe), the planner finds the
     * first route the device can actually take, and every route it skipped is recorded with a reason.
     * An objective nobody wrote a recipe for is not a dead end: the blocker says which recipes are
     * close and that `shell.run` reaches anything the target's shell can do.
     */
    fun plan(
        goal: ExecutionGoal,
        capabilities: CapabilityReport,
    ): ExecutionPlan {
        val recipe = recipeFor(goal)
        if (recipe == null) {
            return blockedPlan(
                goal = goal,
                recipeId = "",
                parameters = goal.parameters,
                operation = ExecutionOperation.SHELL,
                capability = null,
                reason = "no recipe matches \"${goal.description}\"",
                hint =
                    "send recipe_id for one of: " +
                        recipes.all.take(8).joinToString(", ") { it.id } +
                        " - or use shell.run, which reaches anything this target's shell can do",
            )
        }
        return plan(recipe, goal, capabilities)
    }

    /**
     * Every **feasible** route for one recipe, best first.
     *
     * A caller that executes wants the fallbacks too: the first candidate can pass the capability
     * check and still fail on the device (a locked screen, a full disk, an OEM that renamed the
     * output), and the honest answer then is the *next* route with the first one's failure recorded -
     * not "unsupported". The trail travels with each plan, so whichever one runs, the agent can say
     * what was rejected on the way.
     */
    fun plans(
        recipe: ExecutionRecipe,
        goal: ExecutionGoal,
        capabilities: CapabilityReport,
    ): List<ExecutionPlan> = evaluate(recipe, goal, capabilities).plans

    /**
     * Resolves one recipe for one target: the best route, or the reason there is none.
     *
     * This is the form a caller that wants one answer uses, and the form that names the missing
     * capability when the device cannot take any route.
     */
    fun plan(
        recipe: ExecutionRecipe,
        goal: ExecutionGoal,
        capabilities: CapabilityReport,
    ): ExecutionPlan {
        val evaluation = evaluate(recipe, goal, capabilities)
        evaluation.missingParameters.firstOrNull()?.let { parameter ->
            return blockedPlan(
                goal = goal,
                recipeId = recipe.id,
                parameters = evaluation.parameters,
                operation = ExecutionOperation.SHELL,
                capability = null,
                reason = "recipe ${recipe.id} needs ${evaluation.missingParameters.joinToString { it.name }}",
                hint = "${parameter.name}: ${parameter.description}",
            )
        }
        evaluation.plans.firstOrNull()?.let { return it }
        evaluation.refusal?.let { refusal ->
            return blockedPlan(
                goal = goal,
                recipeId = recipe.id,
                parameters = evaluation.parameters,
                operation = ExecutionOperation.SHELL,
                capability = null,
                reason = refusal,
                hint = recipe.parameters.joinToString("; ") { parameter -> "${parameter.name}: ${parameter.description}" },
            )
        }
        val blockedRoutes =
            evaluation.candidates.mapNotNull { candidate ->
                capabilities.check(candidate.requires).missing.firstOrNull()?.let { requirement ->
                    "${candidate.id} needs ${requirement.name}"
                }
            }
        return ExecutionPlan(
            steps = emptyList(),
            blockers =
                listOf(
                    PlanBlocker(
                        operation = evaluation.candidates.firstOrNull()?.actions?.firstOrNull()?.operation ?: ExecutionOperation.SHELL,
                        transport = goal.transport,
                        capability = capabilities.check(evaluation.candidates.flatMap { it.requires }).missing.firstOrNull()?.name,
                        reason =
                            if (blockedRoutes.isEmpty()) {
                                "no route for ${recipe.id} over ${goal.transport}"
                            } else {
                                "this target cannot take any route for ${recipe.id}: ${blockedRoutes.joinToString()}"
                            },
                        hint = "re-probe the target, or ask for something its reported capabilities support",
                    ),
                ),
            goal = goal.description.ifBlank { recipe.title },
            recipeId = recipe.id,
            parameters = evaluation.parameters,
            candidates = evaluation.trail,
        )
    }

    /** One pass over a recipe: the routes that work, the trail, and why the rest did not. */
    private fun evaluate(
        recipe: ExecutionRecipe,
        goal: ExecutionGoal,
        capabilities: CapabilityReport,
    ): RecipeEvaluation {
        val missingParameters = recipe.missingParameters(goal.parameters)
        val parameters =
            recipe.parameters.associate { parameter ->
                val supplied = goal.parameters[parameter.name]?.trim().orEmpty()
                parameter.name to supplied.ifEmpty { parameter.default }
            }
        if (missingParameters.isNotEmpty()) {
            return RecipeEvaluation(emptyList(), emptyList(), null, parameters, missingParameters, emptyList())
        }
        val candidates =
            try {
                recipe.candidates(parameters)
            } catch (refusal: IllegalArgumentException) {
                return RecipeEvaluation(
                    emptyList(),
                    emptyList(),
                    "recipe ${recipe.id} refused the parameters: ${refusal.message}",
                    parameters,
                    emptyList(),
                    emptyList(),
                )
            }
        val trail = mutableListOf<String>()
        val feasible = mutableListOf<ExecutionPlan>()
        candidates.forEach { candidate ->
            val check = capabilities.check(candidate.requires)
            if (check.blocked) {
                trail += "${candidate.id}: skipped, ${check.reason()}"
                return@forEach
            }
            val operations = candidate.actions.map { it.operation }.distinct()
            val choice = providers.select(goal.transport, operations.first(), goal.providerId)
            val servesEveryStep = operations.all { operation -> providers.select(goal.transport, operation).available }
            if (!choice.available || !servesEveryStep) {
                // The provider's own words, so a pinned id that does not exist reads differently from a
                // transport nobody serves.
                trail += "${candidate.id}: skipped, ${choice.reason}"
                return@forEach
            }
            val provider = choice.provider ?: return@forEach
            // The route is feasible only if the *device* can do it and the *provider* can carry every
            // step of it. A provider requirement the target measured as missing skips this route too,
            // so a plan never promises a step the transport cannot run.
            val providerRequirements =
                operations.flatMap { operation -> provider.requirements(operation).map { name -> CapabilityRequirement.of(name) } }
            val fullCheck = capabilities.check(candidate.requires + providerRequirements)
            if (fullCheck.blocked) {
                trail += "${candidate.id}: skipped, ${provider.id} needs ${fullCheck.reason()}"
                return@forEach
            }
            val skipped = trail.toList()
            trail += "${candidate.id}: chosen, ${choice.reason}"
            val steps =
                candidate.actions.map { action ->
                    stepFor(action, candidate, recipe, provider, capabilities, fullCheck, goal, skipped)
                }
            feasible +=
                ExecutionPlan(
                    steps = steps,
                    blockers = emptyList(),
                    goal = goal.description.ifBlank { recipe.title },
                    recipeId = recipe.id,
                    providerId = provider.id,
                    parameters = parameters,
                    candidates = trail.toList(),
                )
        }
        return RecipeEvaluation(feasible, trail.toList(), null, parameters, emptyList(), candidates)
    }

    /** One step of a chosen candidate: what runs, which capability answers it, and what was skipped. */
    private fun stepFor(
        action: RecipeAction,
        candidate: RecipeCandidate,
        recipe: ExecutionRecipe,
        provider: ExecutionProvider,
        capabilities: CapabilityReport,
        check: CapabilityCheck,
        goal: ExecutionGoal,
        skipped: List<String>,
    ): PlanStep {
        val requirement = candidate.requires.firstOrNull { candidateRequirement -> action.capability in candidateRequirement.names }
        return PlanStep(
            providerId = provider.id,
            transport = provider.transport,
            operation = action.operation,
            description = action.description,
            requirements = candidate.requires.filterNot { it.optional }.map { it.name }.toSet(),
            verify = goal.verify,
            invocation = action.invocation,
            effect = action.effect,
            capability = requirement?.let { capabilities.preferredName(it) }.orEmpty().ifEmpty { action.capability },
            candidateId = candidate.id,
            recipeId = recipe.id,
            capabilityRequirements = candidate.requires,
            unproven = check.unproven.map { it.name },
            verifyCommand = action.verify,
            fallbackReason = skipped.joinToString(" | "),
            reason = candidate.note,
        )
    }

    /** A plan that could not resolve, with the reason and the way out. One place, so it reads the same. */
    private fun blockedPlan(
        goal: ExecutionGoal,
        recipeId: String,
        parameters: Map<String, String>,
        operation: ExecutionOperation,
        capability: String?,
        reason: String,
        hint: String,
    ): ExecutionPlan =
        ExecutionPlan(
            steps = emptyList(),
            blockers = listOf(PlanBlocker(operation, goal.transport, capability, reason, hint)),
            goal = goal.description,
            recipeId = recipeId,
            parameters = parameters,
        )

    /** The result of one evaluation pass: what is feasible, what was considered, and what was missing. */
    private data class RecipeEvaluation(
        val plans: List<ExecutionPlan>,
        val trail: List<String>,
        val refusal: String?,
        val parameters: Map<String, String>,
        val missingParameters: List<RecipeParameter>,
        val candidates: List<RecipeCandidate>,
    )

    /** Raised when the target *reported* a capability as missing (an unproven one does not block). */
    class MissingCapabilityException(
        val operation: ExecutionOperation,
        val capability: String,
    ) : Exception("$operation needs $capability")
}
