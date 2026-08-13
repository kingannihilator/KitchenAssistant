package com.example.kitchenassistant.data

/**
 * How often each recipe-corpus ingredient actually appears in `recipe_database.sqlite`, used to
 * rank the fridge-add autocomplete dropdown by real-world popularity instead of an arbitrary
 * string property.
 *
 * `ingredients.db` (the OpenFoodFacts-derived taxonomy the autocomplete field searches) carries no
 * popularity signal of its own -- its bundled schema is stripped to just `id, name_en`, with
 * nothing recoverable in this repo to rebuild it with one (see
 * `porting-reference/INGREDIENT_MATCHING_CONCEPTS.md`). The recipe corpus is the next best thing
 * bundled with the app: real ingredient lines from ~16k recipes, where "chicken breast" and
 * "chicken blood" occur at wildly different rates for exactly the reason a user would expect.
 *
 * [frequencyFor] runs a candidate name through [IngredientMatcher.isSpecificVariantOf] (not
 * [IngredientMatcher.matches]) so "chicken breast" aggregates its count across every real-world
 * phrasing in the corpus that's the same thing or more specific ("boneless chicken breast",
 * "chicken breast half", ...), without also pulling in matches that only work in the *other*
 * direction — see that function's doc for why "chicken egg" inheriting plain "egg"'s frequency
 * (in the thousands) is exactly the failure mode this avoids. Structurally this mirrors
 * [NewIngredientIndex]'s head-bucketing for the same reason: only a candidate's own head bucket
 * can possibly contain a hit.
 *
 * Built once per process and held for the app's lifetime, same rationale as [CanonicalIndex] and
 * [NewIngredientIndex].
 */
class IngredientPopularityIndex private constructor(
    private val terms: Array<IngredientMatcher.Term>,
    private val frequencies: IntArray,
    private val byHead: Map<String, IntArray>
) {

    /** Number of matchable (non-blob) corpus ingredients indexed. */
    val size: Int get() = terms.size

    /**
     * Sum of recipe-corpus frequency across every ingredient [candidateName] would satisfy as a
     * fridge item. Zero when nothing in the corpus matches -- not necessarily "unpopular", just
     * unmeasured, so callers should treat this as a ranking signal, not a verdict.
     *
     * [candidateName] is parsed with [IngredientMatcher.parseRecipe], not
     * [IngredientMatcher.parseFridge], despite candidates coming from the fridge-add field —
     * deliberately, since `parseFridge`'s truncation at the first [IngredientMatcher] connective
     * exists to keep taxonomy trailing-clause junk from causing false *matches* once an item is
     * actually in the fridge, which is not this function's job. Ranking is the one place that
     * truncation actively hurts: `ingredients.db` has entries like "tomatoes-in-tomato-juice",
     * which truncates (at "in") down to bare "tomato" and so inherited all 1,466 of *its* corpus
     * mentions — outranking the genuinely distinct, popular "tomato sauce" (333). Parsed without
     * truncating, "in" drops as an ordinary stopword instead and the head becomes "juice", landing
     * it with "tomato juice"'s real, much smaller count — where it belongs.
     */
    fun frequencyFor(candidateName: String): Int {
        val candidateTerm = IngredientMatcher.parseRecipe(candidateName)
        val head = candidateTerm.head ?: return 0
        val bucket = byHead[head] ?: return 0
        var total = 0
        for (i in bucket) {
            if (IngredientMatcher.isSpecificVariantOf(candidateTerm, terms[i])) total += frequencies[i]
        }
        return total
    }

    companion object {
        @Volatile
        private var instance: IngredientPopularityIndex? = null

        /** Returns the shared index, building it from [dao] on first call. Call from a background
         * dispatcher -- the first call queries every matchable ingredient's corpus frequency. */
        suspend fun get(dao: NewRecipeDao, blobNameLengthThreshold: Int): IngredientPopularityIndex {
            instance?.let { return it }
            val built = build(dao, blobNameLengthThreshold)
            synchronized(this) {
                instance?.let { return it }
                instance = built
                return built
            }
        }

        private suspend fun build(dao: NewRecipeDao, blobNameLengthThreshold: Int): IngredientPopularityIndex {
            val rows = dao.getIngredientFrequencies(blobNameLengthThreshold)

            val terms = Array(rows.size) { IngredientMatcher.parseRecipe(rows[it].normalizedName) }
            val frequencies = IntArray(rows.size) { rows[it].frequency }
            val byHead = HashMap<String, MutableList<Int>>(4_096)
            for (i in rows.indices) {
                val head = terms[i].head ?: continue
                byHead.getOrPut(head) { mutableListOf() }.add(i)
            }

            return IngredientPopularityIndex(
                terms = terms,
                frequencies = frequencies,
                byHead = byHead.mapValues { it.value.toIntArray() }
            )
        }
    }
}
