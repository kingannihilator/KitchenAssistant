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
     * How many `DEFINING`-tier ingredients this recipe calls for in total. Paired with
     * [definingMatchedCount] so [com.pancakeworks.fridgegrub.viewmodel.recipeOrder] can rank by
     * the *proportion* of defining ingredients covered, not the raw count -- otherwise a recipe
     * tagging several ingredients `DEFINING` gets more chances to accumulate defining-matches than
     * one naming just its single namesake ingredient, even when the multi-defining recipe is
     * missing most of what it actually needs. Defaults to [definingMatchedCount] so a caller that
     * only ever set the matched count (tests, any future caller) gets the same "fully covers
     * however many defining ingredients it named" behavior as before this field existed.
     */
    val definingTotalCount: Int = definingMatchedCount,
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
    /** Bare 1-4 scale from the corpus, no source-documented meaning -- see `RecipeEntity
     * .difficulty`'s doc for the empirical basis of the app's Easy/Everyday/"A Bit of Work"/
     * "Go For It!" labels. Null for the ~26% of recipes the source doesn't rate. */
    val difficulty: Int? = null,
    /** Human-readable total time ("1 hour 30 minutes"), preferred for display over reformatting
     * [cookMinutesMin]/[cookMinutesMax] by hand. Null for the ~75% of recipes without a value. */
    val timeText: String? = null,
    /** Numeric total time in minutes, for filtering (`RecipeViewModel`'s time-bucket filter) --
     * display prefers [timeText]. Usually equal to each other (a single value, not a real range). */
    val cookMinutesMin: Int? = null,
    val cookMinutesMax: Int? = null,
    val isFavorite: Boolean = false
) {
    /** Total ingredient lines this recipe calls for, independent of what the fridge/pantry can
     * supply -- unlike [matchedCount]/[totalCount], which are fridge-relative. */
    val ingredientCount: Int get() = ingredients.size
}
