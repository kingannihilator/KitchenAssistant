package com.pancakeworks.fridgegrub.data

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
 * Pantry entries ([matching]/[matchOrigins]'s `pantryNames` parameter) participate in direct
 * string-matching exactly like fridge entries, but never *seed* category expansion -- user-
 * confirmed: the pantry checklist (`data/PantryRepository.kt`) is a coarse, literal "do you
 * generally have this" signal, not fridge inventory, and stretching it through the category
 * taxonomy overreaches in a way a real fridge item doesn't. Checking pantry "onion" satisfying
 * recipe "onion"/"onions" is exactly the intended literal signal; letting that same check silently
 * also credit "scallions"/"leeks"/"shallots" (all filed under the same `Onion` category) is not --
 * concretely, this is why "Champ (Irish Mashed Potato with Scallion)" once showed a false 6/6 full
 * match for a fridge of just "potato" plus the default pantry, with "scallions" credited off
 * pantry "onion" alone. A real fridge "onion" still expands into all of those siblings as before;
 * only the pantry-sourced trigger is narrowed.
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
     * Every ingredient_id satisfied by at least one of [fridgeNames]/[pantryNames], including the
     * category expansion described in the class doc (pantry-sourced matches excepted -- see
     * [pantryNames]'s doc there). Feeds straight into the scoring query's `ingredient_id IN (...)`
     * list, so it is the full set, not a ranked one.
     */
    fun matching(fridgeNames: List<String>, pantryNames: List<String> = emptyList()): Set<Int> =
        matchOrigins(fridgeNames, pantryNames).keys

    /**
     * Which fridge entry satisfied a matched ingredient (see [NewIngredientIndex.matchOrigins]'s
     * doc), and whether that match is [direct] -- the recipe ingredient names the same thing as
     * the fridge item, or a *more specific* variant of it (`IngredientMatcher.isSpecificVariantOf`)
     * -- as opposed to only being satisfiable via the more-general direction of
     * [IngredientMatcher.matches] or via category expansion (pass 2). This is what lets ranking
     * (`RecipeMatch.usesDirectMatch`) and the "Exact match only" filter tell "fridge chicken
     * breast satisfies recipe chicken breast" apart from two other cases that both look like a
     * match but aren't what the user actually has: fridge chicken breast satisfying a recipe's
     * *bare* "chicken" (`IngredientMatcher.matches` allows the fridge side to be the more specific
     * one -- correct for search, since a fridge item should satisfy a more general recipe
     * requirement, but not what "exact" means here), and fridge chicken breast reaching recipe
     * "chicken wings"/"chicken drumsticks" only via the shared Meat/Chicken category. All three are
     * valid, real matches; only the first is [direct].
     */
    data class MatchOrigin(val fridgeKey: String, val direct: Boolean)

    /**
     * Like [matching], but also tags each matched ingredient_id with the fridge/pantry entry that
     * satisfied it -- a stable string built from that term's own words, so two different rows that
     * happen to normalize the same way collapse to one key, and two genuinely distinct rows never
     * share a key.
     *
     * This is what lets scoring collapse "cheddar cheese" and "mozzarella cheese" in the same
     * recipe down to a single matched credit when the fridge only holds one generic "cheese" --
     * both trace back to that one fridge entry -- while still crediting both separately when the
     * fridge actually lists "cheddar cheese" and "mozzarella cheese" as their own entries. Terms
     * are processed most-specific-first (by word count) so a specific entry claims its own exact
     * ingredient before a broader one (e.g. plain "cheese") sweeps in and swallows the claim; a
     * category-expansion sibling (no shared words with any term at all, e.g. "ribeye" via fridge
     * "beef") inherits the origin of whichever matched ingredient first pulled its category in,
     * tagged `direct = false`.
     *
     * [pantryNames] matches the same way as [fridgeNames] for direct string-matching, but never
     * seeds category expansion -- see the class doc's "Pantry entries" section for why.
     */
    fun matchOrigins(fridgeNames: List<String>, pantryNames: List<String> = emptyList()): Map<Int, MatchOrigin> {
        data class TaggedTerm(val term: IngredientMatcher.Term, val expandable: Boolean)

        val taggedTerms = fridgeNames.map { TaggedTerm(IngredientMatcher.parseFridge(it), expandable = true) } +
            pantryNames.map { TaggedTerm(IngredientMatcher.parseFridge(it), expandable = false) }
        // Same-head lookup for the category-expansion guard below -- a candidate can only be
        // "explicitly rejected" by a fridge/pantry item whose head it shares. Includes pantry
        // terms too: this guard is about correctness (don't re-admit a substance direct matching
        // already rejected), unrelated to which terms are allowed to trigger expansion.
        val fridgeTermsByHead = taggedTerms.map { it.term }.filter { it.head != null }.groupBy { it.head!! }

        // Indices into the parallel arrays, not ingredient_ids yet -- resolved at the end.
        val matchedIndices = LinkedHashSet<Int>()
        val origin = HashMap<Int, String>()
        // Indices whose match is allowed to seed category expansion -- i.e. traces to a fridge
        // term, not a pantry-only one.
        val expandableIndices = HashSet<Int>()
        // Pass-1 matches where the recipe ingredient is the same thing or more specific than the
        // fridge item -- see MatchOrigin.direct's doc for why this is narrower than "matched in
        // pass 1": IngredientMatcher.matches also allows the fridge side to be the more specific
        // one (fridge "chicken breast" legitimately satisfying a recipe's bare "chicken"), which
        // is correct for search but not what "exact" should mean.
        val exactIndices = HashSet<Int>()
        for (tagged in taggedTerms.sortedByDescending { it.term.words.size }) {
            val fridgeTerm = tagged.term
            val head = fridgeTerm.head ?: continue
            val bucket = byHead[head] ?: continue
            val key = fridgeTerm.words.sorted().joinToString(" ")
            for (i in bucket) {
                if (i in matchedIndices) continue
                val candidateTerm = IngredientMatcher.parseRecipe(normalizedNames[i])
                if (IngredientMatcher.matches(fridgeTerm, candidateTerm)) {
                    matchedIndices.add(i)
                    origin[i] = key
                    if (tagged.expandable) expandableIndices.add(i)
                    if (IngredientMatcher.isSpecificVariantOf(fridgeTerm, candidateTerm)) {
                        exactIndices.add(i)
                    }
                }
            }
        }

        // Representative origin per category, so every sibling pulled in by that category's
        // expansion traces back to the same fridge entry that triggered it. Pantry-only matches
        // (not in expandableIndices) are skipped here -- they still count as matched themselves,
        // they just don't pull in siblings.
        val categoryOrigin = HashMap<Int, String>()
        for (i in matchedIndices) {
            if (i !in expandableIndices) continue
            val categoryId = categoryIds[i] ?: continue
            categoryOrigin.putIfAbsent(categoryId, origin.getValue(i))
        }
        for (categoryId in categoryOrigin.keys) {
            val siblings = byCategory[categoryId] ?: continue
            for (i in siblings) {
                if (i in matchedIndices) continue
                val candidateTerm = IngredientMatcher.parseRecipe(normalizedNames[i])
                val sameHeadFridgeTerms = candidateTerm.head?.let { fridgeTermsByHead[it] }
                val explicitlyRejected = sameHeadFridgeTerms?.any {
                    IngredientMatcher.isDifferentSubstance(it, candidateTerm)
                } ?: false
                if (!explicitlyRejected) {
                    matchedIndices.add(i)
                    origin[i] = categoryOrigin.getValue(categoryId)
                }
            }
        }

        val result = LinkedHashMap<Int, MatchOrigin>(matchedIndices.size)
        for (i in matchedIndices) {
            result[ingredientIds[i]] = MatchOrigin(origin.getValue(i), direct = i in exactIndices)
        }
        return result
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
