package com.example.kitchenassistant.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for the new recipe corpus (`recipe_database.sqlite`, odunola/foodie,
 * bundled as `assets/database/recipe_database.sqlite`).
 *
 * `recipe_ingredients` is deliberately NOT modeled here as a Room `@Entity`: its real
 * composite primary key `(recipe_id, ingredient_id, position)` has `position` declared
 * without `NOT NULL` (confirmed via `PRAGMA table_info` -- SQLite doesn't require composite
 * primary-key columns to be NOT NULL unless stated), but Room requires every field in a
 * composite `primaryKeys` list to be a non-nullable Kotlin type. Since this app only ever
 * reads from `recipe_ingredients` (never inserts/updates through Room), there is no need to
 * force that mismatch -- [NewRecipeDao] queries it directly via `@Query`/`@RawQuery` into
 * plain result classes, which Room maps by column name with no schema validation against the
 * table. `cuisines`/`recipe_cuisines`/the FTS tables don't exist at all anymore -- along with
 * `source_datasets` and a set of always-NULL `recipes`/`recipe_steps` columns, they were dropped
 * by `porting-reference/cleanup_unused_schema.py` after an audit found them 100% empty and unread
 * by any app code (see that script's docstring for the full list and reasoning).
 *
 * IMPORTANT: Room's runtime schema validation (confirmed via a real on-device crash, not
 * theorized -- `IllegalStateException: Pre-packaged database has an invalid schema`) compares
 * every declared entity's columns, indices, AND foreign keys against `PRAGMA table_info`/
 * `PRAGMA index_list`/`PRAGMA foreign_key_list` on the actual bundled file at first open. Every
 * `@Index`/`@ForeignKey` below exists because the real table genuinely has it (verified via
 * those pragmas against `porting-reference/recipe_database.sqlite`) -- don't add one "for
 * safety" without checking, and don't remove one without checking either. `recipes.source_id`
 * used to reference `source_datasets(source_id)` in the shipped file; that table isn't modeled
 * here (unused metadata), so the reference was stripped from the actual database instead of
 * modeling a whole entity just to satisfy it -- see `porting-reference/fix_primary_key_notnull.py`'s
 * companion migration in the same session's history for both fixes.
 */

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey @ColumnInfo(name = "recipe_id") val recipeId: Int,
    val title: String,
    @ColumnInfo(name = "source_id") val sourceId: Int?
)

/**
 * @property categoryId Nullable FK into [CategoryEntity] -- NULL for both blob (unparsed) names
 *   and heads not yet covered by the hand-curated taxonomy. Either way, matching still works via
 *   [IngredientMatcher] string logic; a NULL category just means no cross-head boost. See
 *   `porting-reference/NEW_CORPUS_DATA_QUALITY.md` and `INGREDIENT_MATCHING_CONCEPTS.md`.
 */
@Entity(
    tableName = "ingredients",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["category_id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["category_id"], name = "idx_ingredients_category"),
        Index(value = ["normalized_name"], name = "idx_ingredients_normalized")
    ]
)
data class NewIngredientEntity(
    @PrimaryKey @ColumnInfo(name = "ingredient_id") val ingredientId: Int,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "category_id") val categoryId: Int?
)

@Entity(
    tableName = "recipe_steps",
    primaryKeys = ["recipe_id", "step_no"],
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["recipe_id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION
        )
    ]
)
data class RecipeStepEntity(
    @ColumnInfo(name = "recipe_id") val recipeId: Int,
    @ColumnInfo(name = "step_no") val stepNo: Int,
    val instruction: String
)

/** Self-referencing tree, built by `porting-reference/apply_categories.py` from the
 * hand-curated `head_categories.json` taxonomy -- see INGREDIENT_MATCHING_CONCEPTS.md. */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["category_id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION
        )
    ]
)
data class CategoryEntity(
    @PrimaryKey @ColumnInfo(name = "category_id") val categoryId: Int,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: Int?
)
