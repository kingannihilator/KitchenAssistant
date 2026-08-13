package com.example.kitchenassistant.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

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
 *
 * "Differs" is decided by a SHA-256 content hash, not file length. Length is what this checked
 * originally, and it missed exactly this kind of change: a hand-patch that added four rows to
 * `ingredients.db` (see `porting-reference/patch_missing_ingredients.py`) landed at the *exact*
 * same byte count as before — SQLite's page-aligned writes routinely do this — so an upgrading
 * install would have silently kept serving the stale copy forever, the same failure mode as the
 * `parse_ok` incident above, just triggered by a same-size edit instead of a missing file.
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

    // Tracks which asset files have already been hash-verified (and refreshed if stale) this
    // process, for the same reason as indexesEnsured -- hashing is real work (a full read of the
    // asset, unlike the length check it replaced), and the file cannot change out from under a
    // running process, so once is enough. Cheap for `ingredients.db` (a few hundred KB); the
    // ~650MB `recipes.db` only pays this cost at all on the legacy corpus path
    // (USE_NEW_RECIPE_DATABASE = false), which isn't the default.
    private val freshnessVerified = mutableSetOf<String>()

    /**
     * Returns an open read-only handle to [assetName], copying or re-copying it from assets first
     * if needed.
     *
     * Call from a background dispatcher: a refresh rewrites the whole file, and `recipes.db` is
     * ~650MB.
     */
    fun openReadOnly(context: Context, assetName: String): SQLiteDatabase {
        val dbFile = context.getDatabasePath(assetName)
        ensureFresh(context, assetName, dbFile)

        if (assetName == "recipes.db") ensureRecipeIdIndexes(dbFile, assetName)

        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    /**
     * Copies [assetName] into [dbFile] if its SHA-256 content hash differs from the asset's —
     * including when [dbFile] doesn't exist yet, or has no cached hash to compare (an install
     * upgrading from before this hardening; the missing marker itself counts as "differs", so it
     * self-heals with one extra copy the first time this runs).
     *
     * The installed copy's hash is cached in a small sidecar file (`$dbFile.sha256`) written right
     * after each copy, so a later call that finds the asset unchanged only re-hashes the asset
     * itself, never re-reads the (potentially huge) installed copy to re-derive its hash.
     */
    private fun ensureFresh(context: Context, assetName: String, dbFile: File) {
        synchronized(freshnessVerified) {
            if (assetName in freshnessVerified) return
            freshnessVerified.add(assetName)

            val assetHash = context.assets.open(assetName).use { sha256(it) }
            val hashFile = File("${dbFile.path}.sha256")
            val installedHash = if (dbFile.exists() && hashFile.exists()) hashFile.readText() else null

            if (installedHash != assetHash) {
                dbFile.parentFile?.mkdirs()
                // Sidecars belong to the file being replaced; leaving them risks SQLite replaying a
                // stale write-ahead log against the new one.
                File("${dbFile.path}-wal").delete()
                File("${dbFile.path}-shm").delete()
                context.assets.open(assetName).use { input ->
                    dbFile.outputStream().use { output -> input.copyTo(output) }
                }
                hashFile.writeText(assetHash)
            }
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
