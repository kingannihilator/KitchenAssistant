package com.pancakeworks.fridgegrub.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** One ingredient row as loaded for [NewIngredientIndex]'s head-bucketing + category expansion. */
data class IngredientForIndexRow(
    @ColumnInfo(name = "ingredient_id") val ingredientId: Int,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "category_id") val categoryId: Int?
)

/** One ingredient line for the detail screen, joined against its [NewIngredientEntity] for the
 * normalized name the search actually scored against. [ingredientId] lets the caller check this
 * row against the same category-expanded matched set [NewIngredientIndex.matching] produces for
 * scoring, so the detail screen's checkmarks can agree with the card's ratio even for a
 * category-only match (no shared words at all, e.g. "ribeye" satisfied by fridge "beef"). */
data class RecipeIngredientLineRow(
    @ColumnInfo(name = "original_text") val originalText: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Int
)

/** One ingredient line for the free-text filter's haystack -- no join needed, just the raw text
 * grouped by recipe. */
data class RecipeIngredientTextRow(
    @ColumnInfo(name = "recipe_id") val recipeId: Int,
    @ColumnInfo(name = "original_text") val originalText: String
)

/** One recipe_steps row, trimmed to what the detail screen needs. */
data class RecipeStepRow(val instruction: String)

/** Per-recipe score from [NewRecipeDao.scoreChunk] -- mirrors RecipeMatch in RecipeViewModel.
 * [matchedIds]/[definingIds] are comma-separated lists of matched ingredient_ids (not just a
 * count) so [com.pancakeworks.fridgegrub.viewmodel.RecipeViewModel.scoreRecipesNew] can collapse
 * ids that trace back to the same fridge entry -- e.g. several kinds of cheese matched by one
 * generic fridge "cheese" -- into a single credit instead of counting each separately. */
data class NewRecipeMatchRow(
    @ColumnInfo(name = "recipe_id") val recipeId: Int,
    val total: Int,
    @ColumnInfo(name = "matched_ids") val matchedIds: String?,
    val prioritized: Int,
    @ColumnInfo(name = "defining_ids") val definingIds: String?,
    /** How many DEFINING-tier ingredients this recipe calls for in total -- not chunk-dependent,
     * same rationale as [total]. Feeds [com.pancakeworks.fridgegrub.model.Recipe.definingTotalCount],
     * so the defining-ingredient ranking boost can compare a *proportion* covered rather than a
     * raw count -- see `RecipeRanking.kt`'s doc for why that matters. */
    @ColumnInfo(name = "defining_total") val definingTotal: Int,
    /** How many SEASONING-tier ingredients this recipe calls for in total -- not chunk-dependent,
     * same rationale as [total]. Paired with [seasoningMatchedIds] so
     * [com.pancakeworks.fridgegrub.viewmodel.RecipeViewModel.scoreRecipesNew] can compute
     * [com.pancakeworks.fridgegrub.model.Recipe.unmatchedSeasoningCount] -- SEASONING rows count
     * toward [total]/[matchedIds] like any other tier now, but a small card indicator still calls
     * out when the only thing standing between a fridge and a recipe is a seasoning, since that's
     * a much lower bar to clear than a missing Supportive/Defining ingredient. */
    @ColumnInfo(name = "seasoning_total") val seasoningTotal: Int,
    @ColumnInfo(name = "seasoning_matched_ids") val seasoningMatchedIds: String?
)

data class RecipeIdRow(@ColumnInfo(name = "recipe_id") val recipeId: Int)

/** One ingredient's real-world frequency in the recipe corpus, for
 * [com.pancakeworks.fridgegrub.data.IngredientPopularityIndex] -- see its class doc. */
data class IngredientFrequencyRow(
    @ColumnInfo(name = "ingredient_id") val ingredientId: Int,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    val frequency: Int
)

@Dao
interface NewRecipeDao {

    /**
     * Every ingredient whose normalized_name isn't blob-length text, for building
     * [NewIngredientIndex]. [blobLengthThreshold] mirrors the Python `BLOB_NAME_LENGTH_THRESHOLD`
     * (40) in `porting-reference/extract_ingredient_heads.py` -- passed as a parameter rather
     * than hardcoded so the one true value lives in RecipeViewModel next to the other switches.
     */
    @Query("SELECT ingredient_id, normalized_name, category_id FROM ingredients WHERE LENGTH(normalized_name) <= :blobLengthThreshold")
    suspend fun getMatchableIngredients(blobLengthThreshold: Int): List<IngredientForIndexRow>

    @Query("SELECT instruction FROM recipe_steps WHERE recipe_id = :recipeId ORDER BY step_no")
    suspend fun getSteps(recipeId: Int): List<RecipeStepRow>

    // --- Everything below touches recipe_ingredients, so it's all @RawQuery, not @Query. ---
    // recipe_ingredients is deliberately not a declared Room @Entity (see NewRecipeEntities.kt's
    // docstring -- its real composite primary key has a nullable column Room can't model). A
    // @Query method's SQL is parsed and validated against the declared schema at KSP compile
    // time, so any @Query touching this table fails to compile ("no such table"). @RawQuery
    // methods take their SQL as a runtime SupportSQLiteQuery value instead, so they're never
    // statically validated. Recipe-id lists are inlined as literals rather than bound params for
    // the same reason as the old corpus's hydrate/scoreRecipes: MAX_RESULTS plus the favorites
    // rescue can exceed SQLite's 999 bound-parameter limit.

    @RawQuery
    suspend fun getRecipesByIdsRaw(query: SupportSQLiteQuery): List<RecipeEntity>

    suspend fun getRecipesByIds(recipeIds: List<Int>): List<RecipeEntity> {
        if (recipeIds.isEmpty()) return emptyList()
        return getRecipesByIdsRaw(
            SimpleSQLiteQuery("SELECT * FROM recipes WHERE recipe_id IN (${recipeIds.joinToString(",")})")
        )
    }

    @RawQuery
    suspend fun getIngredientLinesRaw(query: SupportSQLiteQuery): List<RecipeIngredientLineRow>

    /** Full ingredient lines (with normalized name for the checkmark logic) for one recipe's
     * detail screen. */
    suspend fun getIngredientLines(recipeId: Int): List<RecipeIngredientLineRow> =
        getIngredientLinesRaw(
            SimpleSQLiteQuery(
                """SELECT ri.original_text AS original_text, i.normalized_name AS normalized_name,
                          ri.ingredient_id AS ingredient_id
                   FROM recipe_ingredients ri JOIN ingredients i ON i.ingredient_id = ri.ingredient_id
                   WHERE ri.recipe_id = ? ORDER BY ri.position""",
                arrayOf(recipeId)
            )
        )

    @RawQuery
    suspend fun getIngredientTextForRecipesRaw(query: SupportSQLiteQuery): List<RecipeIngredientTextRow>

    /** Raw ingredient text (no join, no ordering) for many recipes at once -- only the haystack
     * for the free-text filter box, where the un-lemmatized wording is what a user would type. */
    suspend fun getIngredientTextForRecipes(recipeIds: List<Int>): List<RecipeIngredientTextRow> {
        if (recipeIds.isEmpty()) return emptyList()
        return getIngredientTextForRecipesRaw(
            SimpleSQLiteQuery(
                "SELECT recipe_id AS recipe_id, original_text AS original_text FROM recipe_ingredients WHERE recipe_id IN (${recipeIds.joinToString(",")})"
            )
        )
    }

    @RawQuery
    suspend fun getBlobRecipeIdsRaw(query: SupportSQLiteQuery): List<RecipeIdRow>

    /**
     * Recipes with a blob (unparsed) ingredient row -- mirrors `loadUnparseableRecipeIds`'s role
     * for the old corpus. Checked against `normalized_name`, matching the threshold used to build
     * the taxonomy in the first place (see `porting-reference/NEW_CORPUS_DATA_QUALITY.md`).
     */
    suspend fun getBlobRecipeIds(blobLengthThreshold: Int): List<Int> =
        getBlobRecipeIdsRaw(
            SimpleSQLiteQuery(
                """SELECT DISTINCT ri.recipe_id AS recipe_id FROM recipe_ingredients ri
                   JOIN ingredients i ON i.ingredient_id = ri.ingredient_id
                   WHERE LENGTH(i.normalized_name) > ?""",
                arrayOf(blobLengthThreshold)
            )
        ).map { it.recipeId }

    /**
     * Tier-aware scoring aggregate for one chunk of matched ingredient ids -- mirrors
     * `RecipeViewModel.queryChunk`'s role for the old corpus. `SEASONING` tier rows are excluded
     * from both numerator and denominator, per the tier-folding design (see
     * `porting-reference/INGREDIENT_MATCHING_CONCEPTS.md`). [query] must be built with inline
     * integer literals for the matched/prioritized `IN` lists, not bound params -- the ids are
     * trusted (sourced from our own index, never user text), and a well-stocked fridge can exceed
     * SQLite's 999 bound-parameter limit.
     */
    @RawQuery
    suspend fun scoreChunk(query: SupportSQLiteQuery): List<NewRecipeMatchRow>

    @RawQuery
    suspend fun getIngredientFrequenciesRaw(query: SupportSQLiteQuery): List<IngredientFrequencyRow>

    /**
     * How many `recipe_ingredients` rows reference each matchable ingredient -- the real-world
     * popularity signal `ingredients.db` (the fridge-autocomplete taxonomy) has no room for, since
     * its bundled schema is stripped to just `id, name_en` (see
     * `porting-reference/INGREDIENT_MATCHING_CONCEPTS.md`). [blobLengthThreshold] mirrors
     * [getMatchableIngredients]'s.
     */
    suspend fun getIngredientFrequencies(blobLengthThreshold: Int): List<IngredientFrequencyRow> =
        getIngredientFrequenciesRaw(
            SimpleSQLiteQuery(
                """SELECT i.ingredient_id AS ingredient_id, i.normalized_name AS normalized_name,
                          COUNT(*) AS frequency
                   FROM ingredients i JOIN recipe_ingredients ri ON ri.ingredient_id = i.ingredient_id
                   WHERE LENGTH(i.normalized_name) <= ?
                   GROUP BY i.ingredient_id""",
                arrayOf(blobLengthThreshold)
            )
        )
}
