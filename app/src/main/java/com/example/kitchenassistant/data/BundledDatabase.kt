package com.example.kitchenassistant.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }
}
