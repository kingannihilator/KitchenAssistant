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
    val defining: Int = 0,
    /** Defining-tier ingredients this recipe calls for in total -- see
     * [Recipe.definingTotalCount]. Defaults to [defining] so existing/test callers that only ever
     * cared about "matched some defining ingredient" (a boolean-ish 0-or-positive count) keep
     * behaving exactly as before: matching 100% of however many defining ingredients they named. */
    val definingTotal: Int = defining,
    /** See [Recipe.usesRealFridgeItem]. Defaults `true` so anything constructed without pantry
     * context in mind (tests, any future caller) ranks as if fully fridge-backed rather than
     * being silently sunk. */
    val usesRealFridgeItem: Boolean = true,
    /** See [Recipe.usesDirectMatch]. Defaults `true` for the same reason as [usesRealFridgeItem]:
     * anything constructed without a real `NewIngredientIndex.MatchOrigin` in mind (tests, any
     * future caller) ranks as if fully directly matched rather than being silently sunk. */
    val usesDirectMatch: Boolean = true,
    /** See [Recipe.unmatchedSeasoningCount]. Display-only -- not read by [recipeOrder]/[matchOrder]
     * (it's already reflected in [total]/[matched] like any other tier), just carried along so
     * [com.pancakeworks.fridgegrub.viewmodel.RecipeViewModel.hydrateNew] can forward it. */
    val unmatchedSeasoningCount: Int = 0
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
 *
 * [usesDirectMatch] gates tier 2: a 100% ratio is only a genuine full match if it's backed by at
 * least one direct hit on a real fridge item (see [Recipe.usesDirectMatch]'s doc). Without this, a
 * small recipe where the pantry checklist covers every ingredient except one reached only via
 * category expansion (e.g. "Breaded Chicken Livers" -- chicken liver/flour/salt/pepper, with
 * flour/salt/pepper all default-checked pantry staples and "chicken liver" only reachable from
 * fridge "chicken breast" via the shared Meat/Chicken category, never a direct word match) hits a
 * bare 4/4 ratio and displays identically to a recipe the fridge actually completes — confirmed
 * against the real corpus, where that recipe ranked in the top 3 results for a fridge of just
 * "chicken breast" with the default pantry checked. A 100% match failing this check falls through
 * to tier 1 (still counts everything reachable, still ranks well) rather than being demoted
 * further or hidden -- it's a real, complete match, just not one the ratio alone can tell apart
 * from "pantry did all the work."
 */
internal fun matchTier(matched: Int, total: Int, usesDirectMatch: Boolean = true): Int {
    if (total <= 0) return 0
    val ratio = matched.toFloat() / total
    return when {
        ratio >= 1f && usesDirectMatch -> 2   // 100% match, genuinely fridge-backed — green
        ratio >= 0.75f || ratio >= 1f -> 1 // partial match, or a full ratio that didn't clear the direct-match bar — blue
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
 * `definingMatchedCount` boosts *within* a match tier -- a recipe where the fridge covers the
 * dish's namesake ingredient (e.g. garlic in "Garlic Chicken") outranks one at the same match
 * tier/ratio where it doesn't. Ranked below `matchTier`/the main ratio on purpose: it's a
 * tiebreaker among recipes that are already equally good overall matches, not a key that can
 * override tier. It used to sit above tier, which looked equivalent to a raw-count boost but
 * wasn't -- and even after being changed to a ratio (see below), still let a recipe with *more*
 * defining ingredients beat a fully-matched recipe with fewer, purely because the smoothing
 * formula (`matched / (total + 2)`) scores a higher raw `total` more favorably at the *same*
 * coverage percentage (e.g. 2-of-2 smooths to 0.50, but a genuinely equal 1-of-1 only smooths to
 * 0.33). Concretely: a fridge holding just "chicken breast" once ranked "Ayam Goreng Mentega"
 * (2 defining ingredients, both matched, but only 50% of the recipe overall) above "Pan-Seared
 * Chicken Breast" (1 defining ingredient, matched, 100% of the recipe) -- a complete match losing
 * to a half match, solely because the loser tagged more ingredients `DEFINING`. Ranking this
 * below tier/ratio instead means a recipe's overall completeness always decides first; defining
 * coverage only ever chooses between recipes that are already tied on that.
 *
 * The boost is still compared as [ratioScore] of `definingMatchedCount`/`definingTotalCount`, not
 * the raw count -- a recipe tagging several ingredients `DEFINING` (e.g. a pancake recipe naming
 * rice flour, coconut milk, coconut flakes, *and* water as defining) otherwise gets more chances
 * to rack up defining-matches than one naming just its one namesake ingredient, even when tied on
 * tier/ratio.
 *
 * `usesRealFridgeItem` is ranked right after favorites, above every other key -- a recipe
 * satisfied entirely by checked pantry staples (e.g. "Garlic Salt" when the fridge holds only
 * "egg") is always makeable regardless of what's actually in the fridge, so it must never
 * outrank or tie a recipe that uses something the user actually just told the app they have. It
 * doesn't need to outrank favorites (an explicit save) or sit any lower than this: every other
 * key (`prioritizedCount`, `definingMatchedCount`, tier, ratio) only means anything once a recipe
 * has cleared this bar, since starring is fridge-only (see
 * `IngredientViewModel.togglePantryItem`'s doc) and so already implies `usesRealFridgeItem`.
 *
 * `usesDirectMatch` is ranked right after `usesRealFridgeItem`, above `prioritizedCount`/
 * `matchTier`/ratio -- user-confirmed: a recipe whose only connection to the fridge is a
 * category-expansion sibling (fridge "chicken breast" only reaching recipe "chicken wings" via the
 * shared Meat/Chicken category -- see `NewIngredientIndex.matchOrigins`'s doc) must never outrank
 * a recipe with a genuine direct hit (fridge "chicken breast" satisfying recipe "chicken breast"),
 * *even if* the direct match is missing several supporting/seasoning ingredients the
 * category-only recipe happens to have covered by pantry. This is a deliberate exception to the
 * "tier/ratio decides first" principle `definingMatchedCount` follows below -- unlike defining
 * coverage (a within-tier nuance), directness is being used here as a correctness gate on what
 * counts as a trustworthy match at all, the same role `usesRealFridgeItem` already plays one level
 * up. It also still gates [matchTier]'s tier 2 (see that function's doc): a category-only match
 * that reaches a bare 100% ratio via pantry still displays as tier 1 (blue), not a false green.
 *
 * `definingMatchedCount` boosts *within* a match tier -- a recipe where the fridge covers the
 * dish's namesake ingredient (e.g. garlic in "Garlic Chicken") outranks one at the same match
 * tier/ratio where it doesn't. Ranked below `matchTier`/the main ratio on purpose: it's a
 * tiebreaker among recipes that are already equally good overall matches, not a key that can
 * override tier. It used to sit above tier, which looked equivalent to a raw-count boost but
 * wasn't -- and even after being changed to a ratio (see below), still let a recipe with *more*
 * defining ingredients beat a fully-matched recipe with fewer, purely because the smoothing
 * formula (`matched / (total + 2)`) scores a higher raw `total` more favorably at the *same*
 * coverage percentage (e.g. 2-of-2 smooths to 0.50, but a genuinely equal 1-of-1 only smooths to
 * 0.33). Concretely: a fridge holding just "chicken breast" once ranked "Ayam Goreng Mentega"
 * (2 defining ingredients, both matched, but only 50% of the recipe overall) above "Pan-Seared
 * Chicken Breast" (1 defining ingredient, matched, 100% of the recipe) -- a complete match losing
 * to a half match, solely because the loser tagged more ingredients `DEFINING`. Ranking this
 * below tier/ratio instead means a recipe's overall completeness always decides first; defining
 * coverage only ever chooses between recipes that are already tied on that.
 *
 * The boost is still compared as [ratioScore] of `definingMatchedCount`/`definingTotalCount`, not
 * the raw count -- a recipe tagging several ingredients `DEFINING` (e.g. a pancake recipe naming
 * rice flour, coconut milk, coconut flakes, *and* water as defining) otherwise gets more chances
 * to rack up defining-matches than one naming just its one namesake ingredient, even when tied on
 * tier/ratio.
 */
internal val recipeOrder = compareByDescending<Recipe> { it.isFavorite }
    .thenByDescending { it.usesRealFridgeItem }
    .thenByDescending { it.usesDirectMatch }
    .thenByDescending { it.prioritizedCount }
    .thenByDescending { matchTier(it.matchedCount, it.totalCount, it.usesDirectMatch) }
    .thenByDescending { ratioScore(it.matchedCount, it.totalCount) }
    .thenByDescending { ratioScore(it.definingMatchedCount, it.definingTotalCount) }
    .thenByDescending { it.matchedCount }
    .thenBy { it.title }

/** [recipeOrder] applied to raw scores, for the cut to MAX_RESULTS. */
internal val matchOrder = compareByDescending<RecipeMatch> { it.usesRealFridgeItem }
    .thenByDescending { it.usesDirectMatch }
    .thenByDescending { it.prioritized }
    .thenByDescending { matchTier(it.matched, it.total, it.usesDirectMatch) }
    .thenByDescending { ratioScore(it.matched, it.total) }
    .thenByDescending { ratioScore(it.defining, it.definingTotal) }
    .thenByDescending { it.matched }
