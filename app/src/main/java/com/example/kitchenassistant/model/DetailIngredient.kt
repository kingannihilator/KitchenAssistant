package com.example.kitchenassistant.model

/**
 * One ingredient line on the recipe detail screen.
 *
 * [line] is the raw display text with quantity and unit ("1/4 c Seasoned bread crumbs; fine"),
 * while [canonical] is the normalized name the recipe search actually scored against
 * ("breadcrumb"). Keeping both is what lets the detail screen's checkmarks agree with the card's
 * "X/Y ingredients" — they are now derived from the same string.
 *
 * @property canonical `null` when the line has no `clean_ingredients` row: section headings such
 *   as "MARINADE-----" and lines the corpus could not parse. Those cannot be matched either way,
 *   so the UI shows no icon rather than a misleading red X.
 * @property matched New-corpus rows only: whether this line's `ingredient_id` is in the same
 *   category-expanded matched set [com.example.kitchenassistant.data.NewIngredientIndex.matching]
 *   produces for the card's ratio (see [com.example.kitchenassistant.viewmodel.RecipeViewModel]),
 *   precomputed there since the screen has no route to that index on its own. `null` for the
 *   legacy corpus, which has no category expansion — [RecipeDetailScreen] falls back to computing
 *   the checkmark live from [canonical] via plain [com.example.kitchenassistant.data.IngredientMatcher.matches],
 *   exactly as before. Deliberately NOT consulted for cook mode: a category-only match (no shared
 *   words) doesn't identify *which* fridge item to deduct from, so cook mode still only offers
 *   rows with a real, specific fridge match.
 */
data class DetailIngredient(
    val line: String,
    val canonical: String?,
    val matched: Boolean? = null
)
