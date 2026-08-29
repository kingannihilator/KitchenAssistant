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
    /**
     * Whether at least one matched ingredient traces back to a real fridge item, as opposed to
     * being satisfied purely by checked pantry staples (see `data/PantryRepository.kt`). Ranked
     * above every other match-quality key in [com.pancakeworks.fridgegrub.viewmodel.recipeOrder]
     * so a recipe like "Garlic Salt" -- fully matched by pantry alone -- can't outrank or tie a
     * recipe that actually uses something the user just added to their fridge. Defaults `true`:
     * favorites loaded by id (see `RecipeViewModel.loadFavoriteRecipesNew`) never compute a real
     * match at all and shouldn't be penalized for it.
     */
    val usesRealFridgeItem: Boolean = true,
    /**
     * How many `SEASONING`-tier ingredients this recipe calls for that neither the fridge nor
     * pantry supply. `SEASONING`-tier ingredients count toward [matchedCount]/[totalCount] like
     * any other tier (see `RecipeViewModel.scoreRecipesNew`'s doc for why that changed once
     * pantry made seasoning availability real, user-confirmed data instead of noise), so this
     * doesn't affect the ratio -- it's purely a small card indicator (see `RecipeScreen.kt`'s
     * `RecipeCard`) calling out that the only gap is a seasoning, a much lower bar to clear than
     * a missing Supportive/Defining ingredient.
     */
    val unmatchedSeasoningCount: Int = 0,
    val isFavorite: Boolean = false
)
