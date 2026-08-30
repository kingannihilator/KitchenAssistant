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
    val unmatchedSeasoningCount: Int = 0,
    /** See [Recipe.realFridgeMatchedCount]. Defaults 0/0 so a caller without pantry/fridge context
     * in mind (tests, any future caller) never qualifies for [isEffectivelyFullMatch] -- a promotion
     * that requires "uses at least 2 real fridge items" can't fire from a default of zero. */
    val realFridgeMatchedCount: Int = 0,
    /** See [Recipe.realFridgeItemCount]. */
    val realFridgeItemCount: Int = 0
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
internal fun matchTier(
    matched: Int,
    total: Int,
    usesDirectMatch: Boolean = true,
    effectivelyFullMatch: Boolean = false
): Int {
    if (total <= 0) return 0
    val ratio = matched.toFloat() / total
    return when {
        (ratio >= 1f || effectivelyFullMatch) && usesDirectMatch -> 2   // genuinely fridge-backed — green
        ratio >= 0.75f -> 1 // partial match, or a full/promoted ratio that didn't clear the direct-match bar — blue
        else -> 0
    }
}

/**
 * Whether a not-quite-100% recipe should still be treated as [matchTier] 2 ("effectively
 * complete") in "Best Match" mode -- see `RecipeMetadata.kt`'s `MatchMode` doc for the mode this
 * belongs to, and `RecipeViewModel.matchOrder`/[recipeOrder]'s real-fridge-utilization tiebreak
 * that works alongside this. User-confirmed real-world case: a fridge of "ground beef, tomato,
 * potato" plus the default pantry once ranked "Tomato Salad" (6/6, but 5 of those 6 lines are
 * generic pantry staples plus one shared fridge item) above "Mediterranean Beef Stew" (5/6,
 * missing only "green olives" -- a minor garnish -- while genuinely using both ground beef and
 * tomato from the fridge). By the literal ratio alone the salad is "more complete," but the stew is
 * the better real-world answer to "what can I actually cook with what I have."
 *
 * Only ever promotes an already-good match (tier 1, ratio >= 0.75); never rescues a poor one. All
 * of the following must hold:
 * - missing 1 or 2 ingredient lines, not more -- an absolute cap, not just a percentage, so a large
 *   recipe (e.g. 20 ingredients at 90%, missing 2) can still qualify, but one missing several
 *   things can't, no matter how strong its fridge usage looks
 * - every DEFINING ingredient is matched -- the dish's namesake is never the missing piece
 * - uses at least 2 distinct real fridge items, AND at least half of everything in the fridge --
 *   both together, so a large fridge doesn't let "uses 3 of 12 items" (a small fraction) qualify,
 *   and the floor of 2 keeps this from ever firing on a single-item fridge search (already covered
 *   by [Recipe.usesRealFridgeItem])
 */
internal fun isEffectivelyFullMatch(
    matched: Int,
    total: Int,
    definingMatched: Int,
    definingTotal: Int,
    realFridgeMatched: Int,
    realFridgeItemCount: Int
): Boolean {
    if (total <= 0 || realFridgeItemCount <= 0) return false
    val ratio = matched.toFloat() / total
    val missing = total - matched
    val definingComplete = definingMatched >= definingTotal
    val usesEnoughFridge = realFridgeMatched >= 2 &&
        realFridgeMatched.toFloat() / realFridgeItemCount >= 0.5f
    return ratio >= 0.75f && missing in 1..2 && definingComplete && usesEnoughFridge
}

/**
 * Display ordering, and the counterpart to [matchOrder] — the two must agree, since the cut to
 * MAX_RESULTS happens before favorites are known and the final sort happens after.
 *
 * Starred ingredients boost rather than filter: a recipe using two of them outranks one using a
 * single starred ingredient, but recipes using none are still shown.
 *
 * `usesRealFridgeItem` is ranked first, above even `isFavorite` -- user-confirmed: a saved
 * favorite that has nothing to do with the current fridge (e.g. "Pan-Seared Chicken Breast"
 * staying pinned to the top of a search for beef) must not outrank a recipe that actually uses
 * what's in the fridge. Favorites are never hidden for this (see `RecipeViewModel.searchRecipesNew`'s
 * `relevant` filter, which exempts them same as the `MAX_RESULTS` cut -- a saved favorite should
 * never silently vanish from search just because the fridge changed), only deprioritized: an
 * irrelevant favorite still shows up, just wherever its real match quality (usually low, at the
 * very bottom) puts it, instead of automatically topping the list. Also stops a recipe satisfied
 * entirely by checked pantry staples (e.g. "Garlic Salt" when the fridge holds only "egg") from
 * outranking or tying one that uses something the user actually just told the app they have.
 * Every other key (`isFavorite`, `usesDirectMatch`, `prioritizedCount`, `definingMatchedCount`,
 * tier, ratio) only means anything once a recipe has cleared this bar.
 *
 * `isFavorite` is ranked right after `usesRealFridgeItem` -- among recipes that are already
 * genuinely fridge-relevant, an explicit save still wins the tiebreak and tops the list.
 *
 * `usesDirectMatch` is ranked right after `isFavorite`, above `prioritizedCount`/`matchTier`/ratio
 * -- user-confirmed: a recipe whose only connection to the fridge is a category-expansion sibling
 * (fridge "chicken breast" only reaching recipe "chicken wings" via the shared Meat/Chicken
 * category -- see `NewIngredientIndex.matchOrigins`'s doc) must never outrank a recipe with a
 * genuine direct hit (fridge "chicken breast" satisfying recipe "chicken breast"), *even if* the
 * direct match is missing several supporting/seasoning ingredients the category-only recipe
 * happens to have covered by pantry. This is a deliberate exception to the "tier/ratio decides
 * first" principle `definingMatchedCount` follows below -- unlike defining coverage (a within-tier
 * nuance), directness is being used here as a correctness gate on what counts as a trustworthy
 * match at all, the same role `usesRealFridgeItem` already plays one level up. It also still gates
 * [matchTier]'s tier 2 (see that function's doc): a category-only match that reaches a bare 100%
 * ratio via pantry still displays as tier 1 (blue), not a false green.
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
 * [matchTier]'s `effectivelyFullMatch` parameter (fed by [isEffectivelyFullMatch] -- see that
 * function's doc) can promote a not-quite-100% recipe to tier 2. Right after tier, a real-fridge-
 * utilization ratio ([Recipe.realFridgeMatchedCount] over [Recipe.realFridgeItemCount], smoothed
 * the same way as the other ratios here) breaks ties among same-tier recipes in favor of whichever
 * one uses more of the fridge -- this is what lets a promoted recipe (e.g. "Mediterranean Beef
 * Stew," using 2 of 3 real fridge items) actually outrank a trivial 100% match that barely touches
 * the fridge (e.g. "Tomato Salad," using only 1 of those 3) once both reach the same tier, rather
 * than just tying and falling back to the main ratio (which would still favor the salad's literal
 * 6/6 over the stew's 5/6). Ranked below tier, same principle as every other key here: it only ever
 * breaks ties within a tier, never overrides one.
 *
 * This is "Best Match" mode (`RecipeMetadata.kt`'s `MatchMode.BEST_MATCH`, the default). See
 * [recipeOrderMostComplete] for the strict, literal-ratio alternative ("Most Complete" mode) with
 * neither of these two keys.
 */
internal val recipeOrder = compareByDescending<Recipe> { it.usesRealFridgeItem }
    .thenByDescending { it.isFavorite }
    .thenByDescending { it.usesDirectMatch }
    .thenByDescending { it.prioritizedCount }
    .thenByDescending {
        matchTier(
            it.matchedCount, it.totalCount, it.usesDirectMatch,
            isEffectivelyFullMatch(
                it.matchedCount, it.totalCount, it.definingMatchedCount, it.definingTotalCount,
                it.realFridgeMatchedCount, it.realFridgeItemCount
            )
        )
    }
    .thenByDescending { ratioScore(it.realFridgeMatchedCount, it.realFridgeItemCount) }
    .thenByDescending { ratioScore(it.matchedCount, it.totalCount) }
    .thenByDescending { ratioScore(it.definingMatchedCount, it.definingTotalCount) }
    .thenByDescending { it.matchedCount }
    .thenBy { it.title }

/**
 * "Most Complete" mode (`RecipeMetadata.kt`'s `MatchMode.MOST_COMPLETE`) -- [recipeOrder] without
 * the [isEffectivelyFullMatch] tier promotion or the real-fridge-utilization tiebreak, for a user
 * who specifically wants the unadorned "what percentage of this recipe do I have" ordering with no
 * further nuance. Every other key (favorites, real-fridge/direct-match gates, starred ingredients,
 * defining coverage) still applies -- only the two "Best Match"-specific keys are removed.
 */
internal val recipeOrderMostComplete = compareByDescending<Recipe> { it.usesRealFridgeItem }
    .thenByDescending { it.isFavorite }
    .thenByDescending { it.usesDirectMatch }
    .thenByDescending { it.prioritizedCount }
    .thenByDescending { matchTier(it.matchedCount, it.totalCount, it.usesDirectMatch) }
    .thenByDescending { ratioScore(it.matchedCount, it.totalCount) }
    .thenByDescending { ratioScore(it.definingMatchedCount, it.definingTotalCount) }
    .thenByDescending { it.matchedCount }
    .thenBy { it.title }

/** [recipeOrder] applied to raw scores, for the cut to MAX_RESULTS -- always "Best Match" style,
 * independent of the display-time `MatchMode` the user later picks: the promotion in [matchTier]
 * only ever adds recipes to tier 2, never removes any, so cutting with the more inclusive ordering
 * can't drop a recipe "Most Complete" mode would have wanted to show. */
internal val matchOrder = compareByDescending<RecipeMatch> { it.usesRealFridgeItem }
    .thenByDescending { it.usesDirectMatch }
    .thenByDescending { it.prioritized }
    .thenByDescending {
        matchTier(
            it.matched, it.total, it.usesDirectMatch,
            isEffectivelyFullMatch(
                it.matched, it.total, it.defining, it.definingTotal,
                it.realFridgeMatchedCount, it.realFridgeItemCount
            )
        )
    }
    .thenByDescending { ratioScore(it.realFridgeMatchedCount, it.realFridgeItemCount) }
    .thenByDescending { ratioScore(it.matched, it.total) }
    .thenByDescending { ratioScore(it.defining, it.definingTotal) }
    .thenByDescending { it.matched }
