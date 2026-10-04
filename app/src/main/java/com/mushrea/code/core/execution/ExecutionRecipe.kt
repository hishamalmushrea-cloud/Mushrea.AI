package com.mushrea.code.core.execution

/**
 * A **recipe**: one objective expressed as the ways this platform knows how to reach it.
 *
 * This is the answer to "the agent must not need a new tool for every request". A recipe is data -
 * an id, its parameters, and an ordered list of candidates - and each candidate says which
 * capabilities it needs and what it would run. The [ExecutionPlanner] picks the first candidate whose
 * capabilities the target actually reported, so:
 *
 *  * a device with `pm` lists packages one way and a device with only `cmd` another, from the same
 *    recipe, with no `if` in the bridge and no new tool in the catalog;
 *  * a new way to do something is a new candidate (a line of data) - and a wholly new objective is a
 *    new recipe registered by [RecipeRegistry.with], not a change to the planner;
 *  * an objective nobody wrote a recipe for is still reachable by naming an operation and a command
 *    directly, which is what makes the recipe set a convenience rather than a ceiling.
 *
 * Recipes are pure: no IO, no device, no policy. Everything that touches a phone happens later, in a
 * provider that the Permission Center has already blessed.
 */
data class RecipeParameter(
    val name: String,
    val description: String,
    val required: Boolean = true,
    val default: String = "",
    val example: String = "",
)

/** One step of a candidate: what to run, and what it costs. */
data class RecipeAction(
    val operation: ExecutionOperation,
    val invocation: ExecutionInvocation,
    val effect: ExecutionEffect,
    /** The capability this action uses, for the plan and the record. */
    val capability: String = "",
    /** A read-only follow-up line that proves the effect when the caller asks for verification. */
    val verify: String = "",
    val description: String = "",
)

/**
 * One way to reach a recipe's objective, in preference order.
 *
 * [requires] is what the target must have *measured*: a requirement the probe reported missing makes
 * the planner skip this candidate and try the next; one nothing measured (`UNKNOWN`) is attempted,
 * because "we did not look" is not evidence the device cannot do it.
 */
data class RecipeCandidate(
    val id: String,
    val actions: List<RecipeAction>,
    val requires: List<CapabilityRequirement> = emptyList(),
    val note: String = "",
) {
    val primaryCapability: String get() = actions.firstOrNull()?.capability.orEmpty()

    val description: String get() = note.ifBlank { actions.joinToString(" then ") { it.description.ifBlank { it.operation.name } } }
}

/** Builds the candidates for one parameter set. Pure by contract. */
fun interface RecipeBuilder {
    fun candidates(parameters: Map<String, String>): List<RecipeCandidate>
}

/** One objective, its parameters and its candidates. */
data class ExecutionRecipe(
    val id: String,
    val title: String,
    val description: String,
    val parameters: List<RecipeParameter> = emptyList(),
    val keywords: List<String> = emptyList(),
    val builder: RecipeBuilder,
) {
    fun candidates(parameters: Map<String, String>): List<RecipeCandidate> = builder.candidates(parameters)

    /** The required parameters this call did not supply - a blocker the agent can fix by re-sending. */
    fun missingParameters(supplied: Map<String, String>): List<RecipeParameter> =
        parameters.filter { parameter -> parameter.required && supplied[parameter.name].isNullOrBlank() }

    /** Loose keyword match, for when the agent describes a goal instead of naming a recipe. */
    fun matches(goal: String): Boolean {
        val text = goal.lowercase()
        if (text.isBlank()) return false
        if (id.substringAfterLast('.').replace('_', ' ') in text || id.replace('.', ' ') in text) return true
        return keywords.any { keyword -> matchesKeyword(text, keyword) }
    }

    /** How well the goal matches, so [RecipeRegistry.match] can pick the closest recipe. */
    fun score(goal: String): Int {
        val text = goal.lowercase()
        return keywords.count { keyword -> matchesKeyword(text, keyword) }
    }
}

/** The recipes the platform knows, plus whatever a provider contributes. */
class RecipeRegistry(private val recipes: List<ExecutionRecipe>) {
    val all: List<ExecutionRecipe> get() = recipes

    fun byId(id: String): ExecutionRecipe? = recipes.firstOrNull { it.id == id }

    /** The recipe a free-text goal names, or null when nothing matches (not an error: it is a hint). */
    fun match(goal: String): ExecutionRecipe? =
        recipes.filter { it.matches(goal) }.maxByOrNull { recipe -> recipe.score(goal) }

    fun with(vararg extra: ExecutionRecipe): RecipeRegistry {
        val merged = recipes.associateBy { it.id }.toMutableMap()
        extra.forEach { recipe -> merged[recipe.id] = recipe }
        return RecipeRegistry(merged.values.toList())
    }

    companion object {
        val empty: RecipeRegistry = RecipeRegistry(emptyList())
    }
}

/**
 * Whether one keyword is present in a goal sentence.
 *
 * A multi-word keyword matches when every one of its words appears in the sentence, so "run script"
 * matches "run this script" while a one-word keyword still has to be there as a substring. Loose by
 * design: a wrong match costs a look at the candidate list, a missed match costs the agent a tool.
 */
private fun matchesKeyword(
    text: String,
    keyword: String,
): Boolean {
    val words = keyword.lowercase().trim().split(' ').filter(String::isNotBlank)
    if (words.isEmpty()) return false
    if (text.contains(keyword.lowercase().trim())) return true
    return words.size >= 2 && words.all { word -> text.contains(word) }
}

/**
 * What the agent wants to achieve, before anything decided whether it is possible.
 *
 * [recipeId] is optional on purpose: an agent that knows the recipe names it, and an agent that was
 * asked in words hands over the words. Either way the planner answers with steps or with a named
 * blocker - never with "unsupported".
 */
data class ExecutionGoal(
    val description: String,
    val transport: ExecutionTransport,
    /** Which device this goal is about - there is no implicit target anywhere in the platform. */
    val targetId: String = "",
    val recipeId: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val providerId: String? = null,
    /** Ask for the effect to be confirmed after the command, not just for the command to succeed. */
    val verify: Boolean = false,
)
