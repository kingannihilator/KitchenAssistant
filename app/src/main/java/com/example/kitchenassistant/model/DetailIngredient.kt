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
 */
data class DetailIngredient(
    val line: String,
    val canonical: String?
)
