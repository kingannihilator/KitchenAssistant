package com.example.kitchenassistant.data

import android.database.sqlite.SQLiteDatabase

/**
 * An in-memory index of every distinct `clean_ingredients.canonical` value in `recipes.db`,
 * bucketed by [IngredientMatcher] head word.
 *
 * Recipe scoring needs to know which of the corpus's 93,203 canonical ingredient names a fridge
 * satisfies. Testing all of them per fridge item would be wasteful, but [IngredientMatcher]
 * requires equal heads to match, so only the fridge item's own head bucket can possibly contain a
 * hit. The largest bucket is `sauce` (2,347 entries), which makes each lookup sub-millisecond
 * instead of a 93k scan.
 *
 * Built once per process (~0.3s, an index-only scan of `idx_clean_canonical`) and held for the
 * app's lifetime — roughly 9MB: 7MB of strings plus 1.5MB of index. Parsed [IngredientMatcher.Term]
 * objects are deliberately *not* cached; that would roughly double the footprint to save parsing
 * at most a couple of thousand short strings per search.
 */
class CanonicalIndex private constructor(
    private val canonicals: Array<String>,
    private val byHead: Map<String, IntArray>
) {

    /** Number of distinct canonical ingredient names indexed. */
    val size: Int get() = canonicals.size

    /**
     * Every canonical ingredient name satisfied by at least one of [fridgeNames].
     *
     * The result feeds straight into the scoring query's `canonical IN (...)` list, so it is the
     * full set, not a ranked one.
     */
    fun matching(fridgeNames: List<String>): Set<String> {
        val matched = LinkedHashSet<String>()
        for (name in fridgeNames) {
            val fridgeTerm = IngredientMatcher.parseFridge(name)
            val head = fridgeTerm.head ?: continue
            val bucket = byHead[head] ?: continue
            for (i in bucket) {
                val canonical = canonicals[i]
                // An earlier fridge item may already have claimed this one.
                if (canonical in matched) continue
                if (IngredientMatcher.matches(fridgeTerm, IngredientMatcher.parseRecipe(canonical))) {
                    matched.add(canonical)
                }
            }
        }
        return matched
    }

    companion object {
        @Volatile
        private var instance: CanonicalIndex? = null

        /**
         * Returns the shared index, building it from [db] on first call.
         *
         * [db] must be an open handle to `recipes.db`. Call from a background dispatcher — the
         * first call runs a query over 3.1M rows.
         */
        fun get(db: SQLiteDatabase): CanonicalIndex {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(db).also { instance = it }
            }
        }

        private fun build(db: SQLiteDatabase): CanonicalIndex {
            val names = ArrayList<String>(96_000)
            db.rawQuery("SELECT DISTINCT canonical FROM clean_ingredients", null).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
            }

            val buckets = HashMap<String, MutableList<Int>>(16_384)
            for (i in names.indices) {
                // A canonical that normalizes to nothing (a stray "-----", a bare adjective) has no
                // head and can never match, so it is left out of the index entirely.
                val head = IngredientMatcher.parseRecipe(names[i]).head ?: continue
                buckets.getOrPut(head) { ArrayList() }.add(i)
            }

            return CanonicalIndex(
                canonicals = names.toTypedArray(),
                byHead = buckets.mapValues { (_, indices) -> indices.toIntArray() }
            )
        }
    }
}
