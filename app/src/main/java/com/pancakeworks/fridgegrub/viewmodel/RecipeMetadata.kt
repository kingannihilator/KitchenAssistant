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
 * levels 1 ("Easy") and 2 ("Everyday") turned out too close to reliably tell apart from the corpus
 * data alone, so they're folded into one "Everyday" bucket for now rather than presenting a
 * distinction the data doesn't clearly support, and it's one fewer chip to scroll past.
 */
internal data class DifficultyBucket(val label: String, val rawValues: Set<Int>)

internal val DIFFICULTY_BUCKETS = listOf(
    DifficultyBucket("Everyday", setOf(1, 2)),
    DifficultyBucket("A Bit of Work", setOf(3)),
    DifficultyBucket("Go For It!", setOf(4))
)

/** Which [DIFFICULTY_BUCKETS] label a raw corpus difficulty value falls into, or `null` for an
 * unrated recipe or a value outside 1-4. */
internal fun difficultyLabel(difficulty: Int?): String? =
    difficulty?.let { d -> DIFFICULTY_BUCKETS.firstOrNull { d in it.rawValues }?.label }

/**
 * Whether [recipe] passes the difficulty/cook-time/exact-match filters currently selected in
 * `RecipeScreen`.
 *
 * A recipe with no value for a field the filter is actively narrowing on always passes (see the
 * user-confirmed decision in this feature's plan) rather than being hidden for lack of data --
 * difficulty is only ~74% populated and cook time ~24%, so excluding unknowns would silently drop
 * most of an otherwise-good result set. [selectedDifficultyLabels] empty means "no difficulty
 * filter active" (everything passes); [maxCookMinutes] null means "no time filter active".
 *
 * [exactMatchOnly] is a real filter, not a soft exemption like the other two -- it's the
 * safeguard for [Recipe.usesDirectMatch]: when on, a recipe whose match is *only* a category-
 * expansion sibling (fridge "chicken breast" reaching recipe "chicken pieces" via the shared
 * Meat/Chicken taxonomy node, not a direct word match) is dropped entirely. Off by default so the
 * (generally desirable) cross-cut matching stays in results unless the user explicitly wants the
 * stricter view.
 */
internal fun matchesFilters(
    recipe: Recipe,
    selectedDifficultyLabels: Set<String>,
    maxCookMinutes: Int?,
    exactMatchOnly: Boolean = false
): Boolean {
    val difficultyOk = selectedDifficultyLabels.isEmpty() ||
        recipe.difficulty == null ||
        difficultyLabel(recipe.difficulty) in selectedDifficultyLabels
    val cookTimeOk = maxCookMinutes == null ||
        recipe.cookMinutesMin == null ||
        recipe.cookMinutesMin <= maxCookMinutes
    val exactMatchOk = !exactMatchOnly || recipe.usesDirectMatch
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
