package com.example.kitchenassistant.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File

/**
 * Opens one of the read-only SQLite databases bundled in `assets/`.
 *
 * `SQLiteDatabase` needs a real file path and cannot read a stream out of the APK, so the asset is
 * copied into the app's internal `databases/` directory and opened from there.
 *
 * The copy is refreshed whenever the bundled asset differs from it — **not** merely when it's
 * missing. Both corpora are regenerated outside this repo, so a "copy once, never again" rule
 * pins an install to whatever schema shipped the day it was first run. That is not hypothetical:
 * a device that copied `recipes.db` before the `recipes.parse_ok` column existed kept using the
 * old file across every later build, and every recipe search failed with "no such column" and
 * silently returned no results.
 */
object BundledDatabase {

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

    /**
     * Returns an open read-only handle to [assetName], copying or re-copying it from assets first
     * if needed.
     *
     * Call from a background dispatcher: a refresh rewrites the whole file, and `recipes.db` is
     * ~650MB.
     */
    fun openReadOnly(context: Context, assetName: String): SQLiteDatabase {
        val dbFile = context.getDatabasePath(assetName)

        // available() reports an asset's uncompressed length without reading it, which is what the
        // copied file's length should equal.
        val assetSize = context.assets.open(assetName).use { it.available().toLong() }
        if (!dbFile.exists() || dbFile.length() != assetSize) {
            dbFile.parentFile?.mkdirs()
            // Sidecars belong to the file being replaced; leaving them risks SQLite replaying a
            // stale write-ahead log against the new one.
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            context.assets.open(assetName).use { input ->
                dbFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        if (assetName == "recipes.db") ensureRecipeIdIndexes(dbFile, assetName)

        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

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
}
