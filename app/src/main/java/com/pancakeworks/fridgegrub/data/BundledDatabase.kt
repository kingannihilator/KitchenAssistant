package com.pancakeworks.fridgegrub.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Opens one of the read-only SQLite databases bundled in `assets/` — currently just
 * `ingredients.db` (the recipe corpus is opened separately, through Room).
 *
 * `SQLiteDatabase` needs a real file path and cannot read a stream out of the APK, so the asset is
 * copied into the app's internal `databases/` directory and opened from there.
 *
 * The copy is refreshed whenever the bundled asset differs from it — **not** merely when it's
 * missing. `ingredients.db` is regenerated outside this repo, so a "copy once, never again" rule
 * pins an install to whatever schema shipped the day it was first run. That is not hypothetical:
 * a device that copied a since-removed legacy recipe corpus before a schema column existed kept
 * using the old file across every later build, and every query against it failed with "no such
 * column" and silently returned no results.
 *
 * "Differs" is decided by a SHA-256 content hash, not file length. Length is what this checked
 * originally, and it missed exactly this kind of change: a hand-patch that added four rows to
 * `ingredients.db` (see `porting-reference/patch_missing_ingredients.py`) landed at the *exact*
 * same byte count as before — SQLite's page-aligned writes routinely do this — so an upgrading
 * install would have silently kept serving the stale copy forever, the same failure mode as the
 * schema-drift incident above, just triggered by a same-size edit instead of a missing file.
 */
object BundledDatabase {

    // Tracks which asset files have already been hash-verified (and refreshed if stale) this
    // process -- hashing is real work (a full read of the asset, unlike the length check it
    // replaced), and the file cannot change out from under a running process, so once is enough.
    private val freshnessVerified = mutableSetOf<String>()

    /**
     * Returns an open read-only handle to [assetName], copying or re-copying it from assets first
     * if needed.
     *
     * Call from a background dispatcher: a refresh rewrites the whole file.
     */
    fun openReadOnly(context: Context, assetName: String): SQLiteDatabase {
        val dbFile = context.getDatabasePath(assetName)
        ensureFresh(context, assetName, dbFile)

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
}
