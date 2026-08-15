package com.pancakeworks.fridgegrub.model

data class Recipe(
    val id: Int,
    val title: String,
    val servings: Int?,
    val categories: List<String>,
    val ingredients: List<String>,
    /** Distinct canonical ingredients of this recipe the fridge can supply. */
    val matchedCount: Int,
    /** Distinct canonical ingredients the recipe calls for. */
    val totalCount: Int,
    /** How many of the user's starred ingredients this recipe uses; drives the ranking boost. */
    val prioritizedCount: Int = 0,
    /**
     * How many of this recipe's `DEFINING`-tier ingredients (its namesake, e.g. "garlic" in
     * "Garlic Chicken") the fridge supplies. New-corpus only -- always 0 for recipes from the old
     * corpus, which has no tier concept, so it's a no-op there rather than needing a branch.
     */
    val definingMatchedCount: Int = 0,
    val isFavorite: Boolean = false
)
