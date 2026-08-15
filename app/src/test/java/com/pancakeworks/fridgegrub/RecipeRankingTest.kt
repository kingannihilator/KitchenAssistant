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
        isFavorite: Boolean = false,
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
    fun `matchOrder ranks defining-tier matches ahead of a higher raw ratio without one`() {
        val higherRatioNoDefining = RecipeMatch(id = 1, matched = 3, total = 3, prioritized = 0, defining = 0)
        val lowerRatioWithDefining = RecipeMatch(id = 2, matched = 2, total = 3, prioritized = 0, defining = 1)
        val ranked = listOf(higherRatioNoDefining, lowerRatioWithDefining).sortedWith(matchOrder)
        assertEquals(lowerRatioWithDefining, ranked.first())
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
    fun `recipeOrder breaks remaining ties alphabetically by title`() {
        val zebra = recipe(id = 1, matched = 2, total = 2, title = "Zebra Stew")
        val apple = recipe(id = 2, matched = 2, total = 2, title = "Apple Pie")
        val ranked = listOf(zebra, apple).sortedWith(recipeOrder)
        assertEquals(listOf(apple, zebra), ranked)
    }
}
