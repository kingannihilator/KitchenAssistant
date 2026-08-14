// The recipes.db-specific self-healing-index block removed from data/BundledDatabase.kt.
// Preserved here for reference only -- not compiled, not part of the app module.
// See porting-reference/legacy-recipe-path/README.md for why this was removed.
//
// BundledDatabase.kt itself was NOT deleted -- IngredientViewModel still uses it for
// ingredients.db. Only this recipes.db-only piece was cut out of it.

package com.example.kitchenassistant.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File

private const val TAG = "BundledDatabase"

// recipes.db ships with idx_clean_recipe / idx_clean_canonical on clean_ingredients, but
// categories, ingredients, and directions have no index on the recipe_id every lookup filters
// by — each hits a full table scan (up to 3.3M rows) instead. Self-healed here rather than in
// the corpus-build pipeline (which lives outside this repo) so it's fixed regardless of how or
// when a given recipes.db was generated.
private val RECIPE_ID_INDEXES = mapOf(
    "idx_categories_recipe_id" to "categories",
    "idx_ingredients_recipe_id" to "ingredients",
    "idx_directions_recipe_id" to "directions"
)

// Tracks which asset files have already been checked this process, so the CREATE INDEX pass
// (a writable open + a few catalog lookups) runs at most once per asset rather than on every
// openReadOnly call — recipes.db alone is opened on every search and every detail load.
private val indexesEnsured = mutableSetOf<String>()

// Called from openReadOnly as: if (assetName == "recipes.db") ensureRecipeIdIndexes(dbFile, assetName)

/**
 * Creates [RECIPE_ID_INDEXES] on [dbFile] if they're missing. `CREATE INDEX IF NOT EXISTS` is
 * a cheap catalog check once an index already exists, so re-running this every process start
 * is inexpensive — it just isn't worth repeating on every call within the same process.
 */
private fun ensureRecipeIdIndexes(dbFile: File, assetName: String) {
    synchronized(indexesEnsured) {
        if (assetName in indexesEnsured) return
        try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                for ((indexName, table) in RECIPE_ID_INDEXES) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS $indexName ON $table(recipe_id)")
                }
            }
        } catch (e: SQLiteException) {
            // Missing table, read-only filesystem, corpus build predating this schema, etc.
            // Losing the index only costs speed, not correctness, so fall through to the
            // normal (slower) read-only open rather than failing the whole database access.
            Log.w(TAG, "Could not ensure recipe_id indexes on $assetName", e)
        }
        indexesEnsured.add(assetName)
    }
}
