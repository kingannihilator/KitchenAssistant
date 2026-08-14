package com.example.kitchenassistant.data

/**
 * An in-memory index of the new corpus's matchable ingredients (`ingredients.normalized_name`,
 * excluding blob-length names -- see [NewRecipeDao.getMatchableIngredients]), bucketed both by
 * [IngredientMatcher] head word and by category, for [RecipeViewModel]'s new-corpus search path.
 *
 * For the string-matching half: recipe scoring needs to know which ingredients a fridge satisfies,
 * and since [IngredientMatcher] requires equal heads, only a fridge item's own head bucket can
 * possibly contain a hit.
 *
 * What's new here is the category expansion pass: once string-matching confirms a fridge item
 * satisfies some ingredient (e.g. fridge "beef" matching ingredient "beef"), every *other*
 * ingredient sharing that ingredient's `category_id` is pulled in too (e.g. "ribeye", "chuck",
 * "sirloin" -- all filed under the same `Meat/Beef` node by `porting-reference/apply_categories.py`,
 * despite sharing no string with "beef" at all). This is the mechanism that fixes the cross-head
 * gap documented in `porting-reference/INGREDIENT_MATCHING_CONCEPTS.md` -- it only ever *adds*
 * matches on top of what string-matching already finds, never removes one, and an ingredient with
 * no category (NULL `category_id` -- blob or not-yet-categorized) simply isn't expanded, falling
 * back to exactly today's string-matching behavior.
 *
 * The expansion pass has one exception: a candidate is skipped, not added, when some fridge item
 * shares its head and [IngredientMatcher.isDifferentSubstance] says direct matching rejected it
 * specifically as a different substance. Categories in this corpus routinely group a plain
 * ingredient with a [IngredientMatcher] `BLOCK_MODIFIERS`-blocked variant of it -- `Dairy Milk`
 * holds both `milk` and `powdered milk` -- so without this check, string-matching plain `milk`
 * would expand straight through the block and mark `powdered milk` satisfied too. A candidate
 * whose head no fridge item shares (the actual "ribeye is beef" case) is untouched by this check
 * and expands exactly as before.
 *
 * Built once per process and held for the app's lifetime.
 */
class NewIngredientIndex private constructor(
    private val ingredientIds: IntArray,
    private val normalizedNames: Array<String>,
    private val categoryIds: Array<Int?>,
    private val byHead: Map<String, IntArray>,
    private val byCategory: Map<Int, IntArray>
) {

    /** Number of matchable (non-blob) ingredients indexed. */
    val size: Int get() = ingredientIds.size

    /**
     * Every ingredient_id satisfied by at least one of [fridgeNames], including the category
     * expansion described in the class doc. Feeds straight into the scoring query's
     * `ingredient_id IN (...)` list, so it is the full set, not a ranked one.
     */
    fun matching(fridgeNames: List<String>): Set<Int> {
        val fridgeTerms = fridgeNames.map { IngredientMatcher.parseFridge(it) }
        // Same-head lookup for the category-expansion guard below -- a candidate can only be
        // "explicitly rejected" by a fridge item whose head it shares.
        val fridgeTermsByHead = fridgeTerms.filter { it.head != null }.groupBy { it.head!! }

        // Indices into the parallel arrays, not ingredient_ids yet -- resolved at the end.
        val matchedIndices = LinkedHashSet<Int>()
        for (fridgeTerm in fridgeTerms) {
            val head = fridgeTerm.head ?: continue
            val bucket = byHead[head] ?: continue
            for (i in bucket) {
                if (i in matchedIndices) continue
                if (IngredientMatcher.matches(fridgeTerm, IngredientMatcher.parseRecipe(normalizedNames[i]))) {
                    matchedIndices.add(i)
                }
            }
        }

        val categoriesToExpand = matchedIndices.mapNotNull { categoryIds[it] }.toSet()
        for (categoryId in categoriesToExpand) {
            val siblings = byCategory[categoryId] ?: continue
            for (i in siblings) {
                if (i in matchedIndices) continue
                val candidateTerm = IngredientMatcher.parseRecipe(normalizedNames[i])
                val sameHeadFridgeTerms = candidateTerm.head?.let { fridgeTermsByHead[it] }
                val explicitlyRejected = sameHeadFridgeTerms?.any {
                    IngredientMatcher.isDifferentSubstance(it, candidateTerm)
                } ?: false
                if (!explicitlyRejected) matchedIndices.add(i)
            }
        }

        return matchedIndices.mapTo(LinkedHashSet()) { ingredientIds[it] }
    }

    companion object {
        @Volatile
        private var instance: NewIngredientIndex? = null

        /**
         * Returns the shared index, building it from [dao] on first call.
         *
         * [blobNameLengthThreshold] is threaded through from `RecipeViewModel`'s companion object
         * (the single source of truth for that constant) rather than duplicated here.
         *
         * Call from a background dispatcher -- the first call queries every matchable ingredient.
         */
        suspend fun get(dao: NewRecipeDao, blobNameLengthThreshold: Int): NewIngredientIndex {
            instance?.let { return it }
            val built = build(dao, blobNameLengthThreshold)
            synchronized(this) {
                instance?.let { return it }
                instance = built
                return built
            }
        }

        private suspend fun build(dao: NewRecipeDao, blobNameLengthThreshold: Int): NewIngredientIndex {
            val rows = dao.getMatchableIngredients(blobNameLengthThreshold)

            val ingredientIds = IntArray(rows.size)
            val normalizedNames = Array(rows.size) { "" }
            val categoryIds = arrayOfNulls<Int>(rows.size)
            val byHead = HashMap<String, MutableList<Int>>(4_096)
            val byCategory = HashMap<Int, MutableList<Int>>(1_024)

            for (i in rows.indices) {
                val row = rows[i]
                ingredientIds[i] = row.ingredientId
                normalizedNames[i] = row.normalizedName
                categoryIds[i] = row.categoryId

                // A name that normalizes to nothing (a stray fragment, a bare adjective) has no
                // head and can never match, so it is left out of the head index entirely -- it can
                // still be reached via category expansion if it has a category_id, though.
                val head = IngredientMatcher.parseRecipe(row.normalizedName).head
                if (head != null) {
                    byHead.getOrPut(head) { mutableListOf() }.add(i)
                }
                row.categoryId?.let { categoryId ->
                    byCategory.getOrPut(categoryId) { mutableListOf() }.add(i)
                }
            }

            return NewIngredientIndex(
                ingredientIds = ingredientIds,
                normalizedNames = normalizedNames,
                categoryIds = categoryIds,
                byHead = byHead.mapValues { it.value.toIntArray() },
                byCategory = byCategory.mapValues { it.value.toIntArray() }
            )
        }
    }
}
