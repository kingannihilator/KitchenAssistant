package com.pancakeworks.fridgegrub

import com.pancakeworks.fridgegrub.model.Recipe
import com.pancakeworks.fridgegrub.viewmodel.RecipeMatch
import com.pancakeworks.fridgegrub.viewmodel.chunkIntLiterals
import com.pancakeworks.fridgegrub.viewmodel.matchOrder
import com.pancakeworks.fridgegrub.viewmodel.matchTier
import com.pancakeworks.fridgegrub.viewmodel.ratioScore
import com.pancakeworks.fridgegrub.viewmodel.recipeOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the pure scoring/ranking math in RecipeRanking.kt -- see that file's doc for why it's
 * split out of RecipeViewModel. */
class RecipeRankingTest {

    private fun recipe(
        id: Int,
        matched: Int,
        total: Int,
        prioritized: Int = 0,
        defining: Int = 0,
        definingTotal: Int = defining,
        isFavorite: Boolean = false,
        usesRealFridgeItem: Boolean = true,
        title: String = "recipe$id"
    ) = Recipe(
        id = id,
        title = title,
        servings = null,
        categories = emptyList(),
        ingredients = emptyList(),
        matchedCount = matched,
        totalCount = total,
        prioritizedCount = prioritized,
        definingMatchedCount = defining,
        definingTotalCount = definingTotal,
        usesRealFridgeItem = usesRealFridgeItem,
        isFavorite = isFavorite
    )

    // --- ratioScore ---

    @Test
    fun `ratioScore is smoothed by the ratio prior`() {
        // 2 of 2, smoothed: 2 / (2 + 2) = 0.5, not a bare 1.0.
        assertEquals(0.5f, ratioScore(2, 2), 0.0001f)
    }

    @Test
    fun `ratioScore is zero for a recipe with no ingredients`() {
        assertEquals(0f, ratioScore(0, 0), 0.0001f)
    }

    // --- matchTier ---

    @Test
    fun `matchTier buckets a perfect match as tier 2`() {
        assertEquals(2, matchTier(3, 3))
    }

    @Test
    fun `matchTier buckets a 75 percent-plus match as tier 1`() {
        assertEquals(1, matchTier(3, 4))
    }

    @Test
    fun `matchTier buckets anything below 75 percent as tier 0`() {
        assertEquals(0, matchTier(1, 2))
    }

    @Test
    fun `matchTier is zero for a recipe with no ingredients`() {
        assertEquals(0, matchTier(0, 0))
    }

    @Test
    fun `matchTier downgrades a perfect ratio to tier 1 without a direct real-fridge match`() {
        // "Breaded Chicken Livers" -- chicken liver/flour/salt/pepper, with flour/salt/pepper all
        // default-checked pantry staples and chicken liver only reachable from fridge "chicken
        // breast" via category expansion, never a direct hit. A bare 4/4 ratio must not read as a
        // genuine full match the way a real 3/3 does.
        assertEquals(1, matchTier(4, 4, usesDirectMatch = false))
    }

    @Test
    fun `matchTier keeps tier 2 for a perfect ratio with a direct real-fridge match`() {
        assertEquals(2, matchTier(3, 3, usesDirectMatch = true))
    }

    // --- chunkIntLiterals ---

    @Test
    fun `chunkIntLiterals returns a single chunk for a normal-sized set`() {
        val values = (1..100).toList()
        val chunks = chunkIntLiterals(values)
        assertEquals(1, chunks.size)
        assertEquals(values, chunks.single())
    }

    @Test
    fun `chunkIntLiterals splits values that would exceed MAX_LITERAL_CHARS into multiple chunks`() {
        // Each value renders as ~7 digits + separator; comfortably enough of them to force a
        // second chunk without needing to hardcode the exact 350k boundary here.
        val values = (100_000_000..100_060_000).toList()
        val chunks = chunkIntLiterals(values)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        // Chunks partition the input: concatenated back together, nothing lost or duplicated.
        assertEquals(values, chunks.flatten())
    }

    @Test
    fun `chunkIntLiterals returns no chunks for an empty set`() {
        assertEquals(emptyList<List<Int>>(), chunkIntLiterals(emptyList()))
    }

    // --- matchOrder / recipeOrder ---

    @Test
    fun `matchOrder ranks prioritized matches first`() {
        val plain = RecipeMatch(id = 1, matched = 3, total = 3, prioritized = 0)
        val prioritized = RecipeMatch(id = 2, matched = 2, total = 4, prioritized = 1)
        val ranked = listOf(plain, prioritized).sortedWith(matchOrder)
        assertEquals(listOf(prioritized, plain), ranked)
    }

    @Test
    fun `matchOrder never lets defining coverage override a real match-tier difference`() {
        // A fridge of just "chicken breast" once ranked "Ayam Goreng Mentega" (2 defining
        // ingredients, both matched, but only 50% of the recipe overall) above "Pan-Seared
        // Chicken Breast" (1 defining ingredient, matched, 100% of the recipe) -- a complete
        // match losing to a half match solely because the loser tagged more ingredients
        // DEFINING. Defining coverage must only ever break ties within the same tier/ratio, not
        // override a real difference in overall match quality.
        val halfMatchTwoDefiningBothMatched = RecipeMatch(
            id = 1, matched = 6, total = 12, prioritized = 0, defining = 2, definingTotal = 2
        )
        val fullMatchOneDefiningMatched = RecipeMatch(
            id = 2, matched = 5, total = 5, prioritized = 0, defining = 1, definingTotal = 1
        )
        val ranked = listOf(halfMatchTwoDefiningBothMatched, fullMatchOneDefiningMatched).sortedWith(matchOrder)
        assertEquals(fullMatchOneDefiningMatched, ranked.first())
    }

    @Test
    fun `matchOrder lets defining coverage break a tie within the same match tier`() {
        val withDefining = RecipeMatch(id = 1, matched = 2, total = 2, prioritized = 0, defining = 1, definingTotal = 1)
        val withoutDefining = RecipeMatch(id = 2, matched = 2, total = 2, prioritized = 0, defining = 0, definingTotal = 0)
        val ranked = listOf(withoutDefining, withDefining).sortedWith(matchOrder)
        assertEquals(withDefining, ranked.first())
    }

    @Test
    fun `matchOrder ranks a full match tier ahead of a merely-higher smoothed ratio`() {
        // 2/2 (tier 2, smoothed 0.5) must outrank 3/4 (tier 1, smoothed 0.5) despite tying on
        // ratioScore -- this is the exact scenario ratioScore's own doc comment calls out.
        val small = RecipeMatch(id = 1, matched = 2, total = 2, prioritized = 0)
        val large = RecipeMatch(id = 2, matched = 3, total = 4, prioritized = 0)
        assertEquals(ratioScore(2, 2), ratioScore(3, 4), 0.0001f)
        val ranked = listOf(large, small).sortedWith(matchOrder)
        assertEquals(small, ranked.first())
    }

    @Test
    fun `recipeOrder pins favorites first regardless of match quality`() {
        val bestMatch = recipe(id = 1, matched = 3, total = 3, isFavorite = false)
        val favoriteWithWorseMatch = recipe(id = 2, matched = 1, total = 3, isFavorite = true)
        val ranked = listOf(bestMatch, favoriteWithWorseMatch).sortedWith(recipeOrder)
        assertEquals(favoriteWithWorseMatch, ranked.first())
    }

    @Test
    fun `recipeOrder ranks prioritized ingredient count above raw match tier`() {
        val noPrioritized = recipe(id = 1, matched = 3, total = 3, prioritized = 0)
        val onePrioritized = recipe(id = 2, matched = 2, total = 3, prioritized = 1)
        val ranked = listOf(noPrioritized, onePrioritized).sortedWith(recipeOrder)
        assertEquals(onePrioritized, ranked.first())
    }

    @Test
    fun `matchOrder sinks a pantry-only match below a real-fridge match, even at a lower ratio`() {
        // "Garlic Salt" (2/2, fully satisfied by checked pantry items) must not outrank or tie
        // "Egg Butter" (2/2, one real fridge item) -- the exact scenario a fridge of just "egg"
        // plus a full pantry checklist produces.
        val pantryOnly = RecipeMatch(id = 1, matched = 2, total = 2, prioritized = 0, usesRealFridgeItem = false)
        val realFridgeMatch = RecipeMatch(id = 2, matched = 1, total = 3, prioritized = 0, usesRealFridgeItem = true)
        val ranked = listOf(pantryOnly, realFridgeMatch).sortedWith(matchOrder)
        assertEquals(realFridgeMatch, ranked.first())
    }

    @Test
    fun `recipeOrder sinks a pantry-only match below a real-fridge match, even at a lower ratio`() {
        val pantryOnly = recipe(id = 1, matched = 2, total = 2, usesRealFridgeItem = false, title = "Garlic Salt")
        val realFridgeMatch = recipe(id = 2, matched = 2, total = 2, usesRealFridgeItem = true, title = "Egg Butter")
        val ranked = listOf(pantryOnly, realFridgeMatch).sortedWith(recipeOrder)
        assertEquals(realFridgeMatch, ranked.first())
    }

    @Test
    fun `recipeOrder does not let a favorite with no real fridge relevance outrank a real match`() {
        // User-confirmed: a saved favorite unrelated to the current fridge (e.g. "Pan-Seared
        // Chicken Breast" staying pinned to the top of a beef search) must not outrank a recipe
        // that actually uses what's in the fridge. Favorites are still never hidden for this (see
        // RecipeViewModel's `relevant` filter) -- just no longer automatically pinned to the top.
        val favoritePantryOnly = recipe(id = 1, matched = 2, total = 2, usesRealFridgeItem = false, isFavorite = true)
        val realFridgeMatch = recipe(id = 2, matched = 3, total = 3, usesRealFridgeItem = true)
        val ranked = listOf(favoritePantryOnly, realFridgeMatch).sortedWith(recipeOrder)
        assertEquals(realFridgeMatch, ranked.first())
    }

    @Test
    fun `recipeOrder still pins a favorite above a better-matching non-favorite once both are fridge-relevant`() {
        // Unchanged by the usesRealFridgeItem reordering above: once a recipe clears the
        // real-fridge-relevance bar, an explicit save still wins outright, same as before this fix
        // (see "recipeOrder pins favorites first regardless of match quality"). Only an
        // *irrelevant* favorite -- the case above -- lost its automatic top spot.
        val favoriteRealMatch = recipe(id = 1, matched = 2, total = 3, usesRealFridgeItem = true, isFavorite = true)
        val nonFavoriteBetterMatch = recipe(id = 2, matched = 3, total = 3, usesRealFridgeItem = true, isFavorite = false)
        val ranked = listOf(nonFavoriteBetterMatch, favoriteRealMatch).sortedWith(recipeOrder)
        assertEquals(favoriteRealMatch, ranked.first())
    }

    @Test
    fun `matchOrder does not let partial defining coverage outrank full coverage of a better overall match`() {
        // "Vitumbua" tags 4 ingredients DEFINING and matches 2 of them (e.g. water, flour --
        // near-universal pantry items, not the dish's real identity) while missing most of the
        // recipe overall. "Garlic Soup" tags only its one namesake ingredient DEFINING and matches
        // it, with a fully complete recipe otherwise. A raw defining count (2 > 1) would have let
        // Vitumbua win outright; the proportion (2/4 = 50% vs 1/1 = 100%, both smoothed to the same
        // score) correctly defers to the overall match tier instead.
        val partialDefiningPoorOverall = RecipeMatch(
            id = 1, matched = 5, total = 13, prioritized = 0, defining = 2, definingTotal = 4
        )
        val fullDefiningCompleteOverall = RecipeMatch(
            id = 2, matched = 8, total = 8, prioritized = 0, defining = 1, definingTotal = 1
        )
        val ranked = listOf(partialDefiningPoorOverall, fullDefiningCompleteOverall).sortedWith(matchOrder)
        assertEquals(fullDefiningCompleteOverall, ranked.first())
    }

    @Test
    fun `matchOrder ranks a genuine full match above a pantry-inflated one at the same bare ratio`() {
        // The exact "Breaded Chicken Livers" scenario: a bare 4/4 ratio propped up by pantry
        // (flour/salt/pepper) plus one category-expansion-only ingredient must not outrank or tie
        // a recipe the fridge genuinely completes.
        val genuineFullMatch = RecipeMatch(id = 1, matched = 3, total = 3, prioritized = 0, usesDirectMatch = true)
        val pantryInflatedFullRatio = RecipeMatch(id = 2, matched = 4, total = 4, prioritized = 0, usesDirectMatch = false)
        val ranked = listOf(pantryInflatedFullRatio, genuineFullMatch).sortedWith(matchOrder)
        assertEquals(genuineFullMatch, ranked.first())
    }

    @Test
    fun `matchOrder prefers a direct match over a category-only match at the same tier and ratio`() {
        val directMatch = RecipeMatch(id = 1, matched = 2, total = 2, prioritized = 0, usesDirectMatch = true)
        val categoryOnlyMatch = RecipeMatch(id = 2, matched = 2, total = 2, prioritized = 0, usesDirectMatch = false)
        val ranked = listOf(categoryOnlyMatch, directMatch).sortedWith(matchOrder)
        assertEquals(directMatch, ranked.first())
    }

    @Test
    fun `matchOrder ranks a direct match above a category-only match even at a much better ratio`() {
        // User-confirmed: unlike definingMatchedCount (a within-tier nuance), usesDirectMatch is a
        // correctness gate, ranked above matchTier/ratio -- a category-only match (e.g. fridge
        // "chicken breast" only reaching recipe "chicken wings" via the shared Meat/Chicken
        // category) must never outrank a genuine direct match, even when the category-only recipe
        // is otherwise far more complete (here, a full 3/3 vs. a partial 1/3).
        val categoryOnlyFullMatch = RecipeMatch(id = 1, matched = 3, total = 3, prioritized = 0, usesDirectMatch = false)
        val directButPartialMatch = RecipeMatch(id = 2, matched = 1, total = 3, prioritized = 0, usesDirectMatch = true)
        val ranked = listOf(categoryOnlyFullMatch, directButPartialMatch).sortedWith(matchOrder)
        assertEquals(directButPartialMatch, ranked.first())
    }

    @Test
    fun `recipeOrder breaks remaining ties alphabetically by title`() {
        val zebra = recipe(id = 1, matched = 2, total = 2, title = "Zebra Stew")
        val apple = recipe(id = 2, matched = 2, total = 2, title = "Apple Pie")
        val ranked = listOf(zebra, apple).sortedWith(recipeOrder)
        assertEquals(listOf(apple, zebra), ranked)
    }
}
