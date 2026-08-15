package com.pancakeworks.fridgegrub.viewmodel

import com.pancakeworks.fridgegrub.model.Recipe

/**
 * Pure recipe scoring/ranking math, split out of [RecipeViewModel] (which owns everything
 * DB/coroutine-shaped) so it can be unit tested under plain JUnit with no `NewRecipeDao` or
 * `Application` needed -- same reasoning as [IngredientViewModel.rankSuggestions] and
 * [com.pancakeworks.fridgegrub.data.IngredientMatcher]. `internal`, not `private`: visible to
 * [RecipeViewModel] (same package) and to tests, but not part of the app's public surface.
 */

// Matched ingredient ids go into the scoring query as inline literals rather than bound
// parameters — SQLite's host-parameter limit is 999 and a full fridge can match thousands of
// them. SQLITE_MAX_SQL_LENGTH is 1,000,000 on Android; the prioritized list is a subset of the
// matched one, so budgeting the matched literal at 350k keeps the whole statement comfortably
// under half the limit. Fridges large enough to exceed it fall back to scoring in chunks (see
// chunkIntLiterals).
internal const val MAX_LITERAL_CHARS = 350_000

/** Splits [values] into groups whose rendered literal stays under [MAX_LITERAL_CHARS] (no
 * quoting needed — the scoring path's matched set is ingredient_ids, not canonical names).
 * Returns a single group for any realistic fridge. */
internal fun chunkIntLiterals(values: Collection<Int>): List<List<Int>> {
    val chunks = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    var length = 0
    for (value in values) {
        val cost = value.toString().length + 1 // separator
        if (current.isNotEmpty() && length + cost > MAX_LITERAL_CHARS) {
            chunks.add(current)
            current = mutableListOf()
            length = 0
        }
        current.add(value)
        length += cost
    }
    if (current.isNotEmpty()) chunks.add(current)
    return chunks
}

internal data class RecipeMatch(
    val id: Int,
    val matched: Int,
    val total: Int,
    val prioritized: Int,
    /** Defining-tier ingredients matched -- see [Recipe.definingMatchedCount]. */
    val defining: Int = 0
)

/** Smoothing prior on the match ratio; see [recipeOrder]. */
private const val RATIO_PRIOR = 2

/**
 * A recipe's match ratio, smoothed so recipe size counts for something.
 *
 * A bare `matched / total` puts every trivial one-ingredient recipe at a perfect 1.0, ahead of a
 * genuine 9-of-10 match. Adding a prior to the denominator measured a drop from 142 to 25 trivial
 * recipes in the first 200 results for a three-item fridge, and from 28 to 2 garbled ones.
 *
 * Deliberately NOT what [matchTier] ranks by: the smoothing that fixes trivial-recipe inflation
 * also lets a large recipe missing several ingredients (e.g. 3/4, smoothed 0.50) outscore a small
 * complete one (e.g. 2/2, smoothed 0.50, tied, or worse for very small totals) — which would rank
 * an "you're missing an ingredient" card above a "you have everything" one. [matchTier] is ranked
 * first specifically to rule that out; this smoothed score only breaks ties within a tier.
 */
internal fun ratioScore(matched: Int, total: Int): Float =
    if (total <= 0) 0f else matched.toFloat() / (total + RATIO_PRIOR)

/**
 * The match-tier bucket a recipe falls into, on the same *unsmoothed* ratio the recipe card uses
 * to pick its color in [com.pancakeworks.fridgegrub.ui.RecipeScreen] (`RecipeCard`) — so a card
 * can never be colored green while a blue one sits above it in the list.
 */
internal fun matchTier(matched: Int, total: Int): Int {
    if (total <= 0) return 0
    val ratio = matched.toFloat() / total
    return when {
        ratio >= 1f -> 2   // 100% match — green
        ratio >= 0.75f -> 1 // partial match — blue
        else -> 0
    }
}

/**
 * Display ordering, and the counterpart to [matchOrder] — the two must agree, since the cut to
 * MAX_RESULTS happens before favorites are known and the final sort happens after.
 *
 * Starred ingredients boost rather than filter: a recipe using two of them outranks one using a
 * single starred ingredient, but recipes using none are still shown.
 *
 * `definingMatchedCount` boosts the same way, right after starred ingredients: a recipe where the
 * fridge covers the dish's namesake ingredient (e.g. garlic in "Garlic Chicken") outranks one at
 * the same match tier/ratio where it doesn't. Ranked above `matchTier` on purpose, matching how
 * `prioritizedCount` already outranks it: both are deliberate boosts, not filters, so they get
 * first say over the raw ratio.
 */
internal val recipeOrder = compareByDescending<Recipe> { it.isFavorite }
    .thenByDescending { it.prioritizedCount }
    .thenByDescending { it.definingMatchedCount }
    .thenByDescending { matchTier(it.matchedCount, it.totalCount) }
    .thenByDescending { ratioScore(it.matchedCount, it.totalCount) }
    .thenByDescending { it.matchedCount }
    .thenBy { it.title }

/** [recipeOrder] applied to raw scores, for the cut to MAX_RESULTS. */
internal val matchOrder = compareByDescending<RecipeMatch> { it.prioritized }
    .thenByDescending { it.defining }
    .thenByDescending { matchTier(it.matched, it.total) }
    .thenByDescending { ratioScore(it.matched, it.total) }
    .thenByDescending { it.matched }
