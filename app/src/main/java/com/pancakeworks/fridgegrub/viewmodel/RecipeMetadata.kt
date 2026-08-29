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
 * Whether [recipe] passes the difficulty/cook-time filters currently selected in `RecipeScreen`.
 *
 * A recipe with no value for a field the filter is actively narrowing on always passes (see the
 * user-confirmed decision in this feature's plan) rather than being hidden for lack of data --
 * difficulty is only ~74% populated and cook time ~24%, so excluding unknowns would silently drop
 * most of an otherwise-good result set. [selectedDifficulties] empty means "no difficulty filter
 * active" (everything passes); [maxCookMinutes] null means "no time filter active".
 */
internal fun matchesFilters(
    recipe: Recipe,
    selectedDifficulties: Set<Int>,
    maxCookMinutes: Int?
): Boolean {
    val difficultyOk = selectedDifficulties.isEmpty() ||
        recipe.difficulty == null ||
        recipe.difficulty in selectedDifficulties
    val cookTimeOk = maxCookMinutes == null ||
        recipe.cookMinutesMin == null ||
        recipe.cookMinutesMin <= maxCookMinutes
    return difficultyOk && cookTimeOk
}

/**
 * Whether [recipe]'s difficulty is a *genuine* match for [selectedDifficulties], as opposed to
 * merely passing [matchesFilters] via its unknown-data exemption -- true whenever the filter
 * isn't active at all, since there's nothing to genuinely match against. User-confirmed tiebreak,
 * not a filter: an unrated recipe still isn't hidden (matchesFilters is unchanged), it just
 * doesn't get to outrank a recipe that actually carries the difficulty the user asked for. See
 * [matchesKnownCookTime] for the same treatment on the time filter.
 */
internal fun matchesKnownDifficulty(recipe: Recipe, selectedDifficulties: Set<Int>): Boolean =
    selectedDifficulties.isEmpty() || recipe.difficulty in selectedDifficulties

/** See [matchesKnownDifficulty] -- same tiebreak, for the cook-time filter. */
internal fun matchesKnownCookTime(recipe: Recipe, maxCookMinutes: Int?): Boolean =
    maxCookMinutes == null || (recipe.cookMinutesMin != null && recipe.cookMinutesMin <= maxCookMinutes)
