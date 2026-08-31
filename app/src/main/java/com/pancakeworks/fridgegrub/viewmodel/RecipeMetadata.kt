package com.pancakeworks.fridgegrub.viewmodel

import com.pancakeworks.fridgegrub.model.Recipe

/**
 * Pure parsing/filtering helpers for the recipe metadata added alongside difficulty/cook-time
 * filters -- split out for the same reason as `RecipeRanking.kt` (unit-testable under plain JUnit,
 * no `Application`/DAO/Compose needed).
 */

/**
 * Best-effort extraction of a serving count from the corpus's free-text `servings` field, which is
 * inconsistent on purpose (see `RecipeEntity.servings`'s doc): `"12"`, `"About 6"`,
 * `"2 servings"` all appear. Takes the first run of digits; `null` if there isn't one (e.g. a
 * missing value, or free text with no number at all) rather than guessing.
 */
internal fun parseServings(raw: String?): Int? =
    raw?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

/**
 * One difficulty filter chip: a human label plus every raw corpus [difficulty] value (1-4, see
 * `RecipeEntity.difficulty`'s doc) it covers. Collapsed from 4 raw levels to 3 labeled buckets --
 * raw levels 1 and 2 turned out too close to reliably tell apart from the corpus data alone, so
 * they're folded into one "Easy" bucket for now rather than presenting a distinction the data
 * doesn't clearly support, and it's one fewer chip to scroll past.
 */
internal data class DifficultyBucket(val label: String, val rawValues: Set<Int>)

internal val DIFFICULTY_BUCKETS = listOf(
    DifficultyBucket("Easy", setOf(1, 2)),
    DifficultyBucket("Medium", setOf(3)),
    DifficultyBucket("Hard", setOf(4))
)

/** Which [DIFFICULTY_BUCKETS] label a raw corpus difficulty value falls into, or `null` for an
 * unrated recipe or a value outside 1-4. */
internal fun difficultyLabel(difficulty: Int?): String? =
    difficulty?.let { d -> DIFFICULTY_BUCKETS.firstOrNull { d in it.rawValues }?.label }

/**
 * The recipe list's single "Match" dropdown (`RecipeScreen.kt`), replacing what used to be a
 * separate ranking-mode toggle and an "Exact match only" filter chip -- user-confirmed: one
 * dropdown reads more clearly than two controls for a fairly subtle distinction, at the cost of
 * losing the (rarely wanted) combination of strict-ratio ordering *and* hiding category-expansion
 * matches at the same time.
 *
 * [BEST_MATCH] (default) uses `recipeOrder`'s tier-promotion + real-fridge-utilization tiebreak
 * (see `isEffectivelyFullMatch`'s doc for the real-world case this covers). [MOST_COMPLETE] uses
 * `recipeOrderMostComplete` -- the literal ratio/tier ordering with neither of those two keys, for
 * someone who specifically wants "what percentage of this recipe do I have" with no further
 * nuance. [EXACT_MATCH_ONLY] is the one mode that's also a real filter, not just an ordering: a
 * recipe whose match is *only* a category-expansion sibling (fridge "chicken breast" reaching
 * recipe "chicken pieces" via the shared Meat/Chicken taxonomy node, not a direct word match) is
 * dropped entirely, ordered the same as [BEST_MATCH] otherwise.
 */
// Not `internal` like the other declarations in this file: RecipeViewModel exposes it via a
// public StateFlow<MatchMode> for RecipeScreen (Compose UI) to collect, and a public property
// can't expose an internal type argument.
enum class MatchMode { BEST_MATCH, MOST_COMPLETE, EXACT_MATCH_ONLY }

/**
 * Whether [recipe] passes the difficulty/cook-time/match-mode filters currently selected in
 * `RecipeScreen`.
 *
 * A recipe with no value for a field the filter is actively narrowing on always passes (see the
 * user-confirmed decision in this feature's plan) rather than being hidden for lack of data --
 * difficulty is only ~74% populated and cook time ~24%, so excluding unknowns would silently drop
 * most of an otherwise-good result set. [selectedDifficultyLabels] empty means "no difficulty
 * filter active" (everything passes); [maxCookMinutes] null means "no time filter active".
 *
 * [matchMode] only ever filters when it's [MatchMode.EXACT_MATCH_ONLY] -- see that entry's doc.
 * [MatchMode.BEST_MATCH]/[MatchMode.MOST_COMPLETE] only affect ordering (`RecipeViewModel`'s
 * `sortedRecipes`), not inclusion, so they're not checked here at all.
 */
internal fun matchesFilters(
    recipe: Recipe,
    selectedDifficultyLabels: Set<String>,
    maxCookMinutes: Int?,
    matchMode: MatchMode = MatchMode.BEST_MATCH
): Boolean {
    val difficultyOk = selectedDifficultyLabels.isEmpty() ||
        recipe.difficulty == null ||
        difficultyLabel(recipe.difficulty) in selectedDifficultyLabels
    val cookTimeOk = maxCookMinutes == null ||
        recipe.cookMinutesMin == null ||
        recipe.cookMinutesMin <= maxCookMinutes
    val exactMatchOk = matchMode != MatchMode.EXACT_MATCH_ONLY || recipe.usesDirectMatch
    return difficultyOk && cookTimeOk && exactMatchOk
}

/**
 * Whether [recipe]'s difficulty is a *genuine* match for [selectedDifficultyLabels], as opposed to
 * merely passing [matchesFilters] via its unknown-data exemption -- true whenever the filter
 * isn't active at all, since there's nothing to genuinely match against. User-confirmed tiebreak,
 * not a filter: an unrated recipe still isn't hidden (matchesFilters is unchanged), it just
 * doesn't get to outrank a recipe that actually carries the difficulty the user asked for. See
 * [matchesKnownCookTime] for the same treatment on the time filter.
 */
internal fun matchesKnownDifficulty(recipe: Recipe, selectedDifficultyLabels: Set<String>): Boolean =
    selectedDifficultyLabels.isEmpty() || difficultyLabel(recipe.difficulty) in selectedDifficultyLabels

/** See [matchesKnownDifficulty] -- same tiebreak, for the cook-time filter. */
internal fun matchesKnownCookTime(recipe: Recipe, maxCookMinutes: Int?): Boolean =
    maxCookMinutes == null || (recipe.cookMinutesMin != null && recipe.cookMinutesMin <= maxCookMinutes)
