package com.pancakeworks.fridgegrub

import com.pancakeworks.fridgegrub.model.Recipe
import com.pancakeworks.fridgegrub.viewmodel.matchesFilters
import com.pancakeworks.fridgegrub.viewmodel.matchesKnownCookTime
import com.pancakeworks.fridgegrub.viewmodel.matchesKnownDifficulty
import com.pancakeworks.fridgegrub.viewmodel.parseServings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the difficulty/cook-time filter logic and servings parsing added alongside the recipe
 * metadata feature -- see RecipeMetadata.kt's doc. */
class RecipeMetadataTest {

    private fun recipe(difficulty: Int? = null, cookMinutesMin: Int? = null) = Recipe(
        id = 1,
        title = "recipe",
        servings = null,
        categories = emptyList(),
        ingredients = emptyList(),
        matchedCount = 0,
        totalCount = 0,
        difficulty = difficulty,
        cookMinutesMin = cookMinutesMin
    )

    // --- parseServings ---

    @Test
    fun `parseServings extracts a bare number`() {
        assertEquals(12, parseServings("12"))
    }

    @Test
    fun `parseServings extracts a number from surrounding text`() {
        assertEquals(6, parseServings("About 6"))
        assertEquals(2, parseServings("2 servings"))
    }

    @Test
    fun `parseServings returns null for text with no number`() {
        assertEquals(null, parseServings("A few"))
    }

    @Test
    fun `parseServings returns null for null input`() {
        assertEquals(null, parseServings(null))
    }

    // --- matchesFilters ---

    @Test
    fun `matchesFilters passes everything when no filters are active`() {
        assertTrue(matchesFilters(recipe(difficulty = 3, cookMinutesMin = 90), emptySet(), null))
    }

    @Test
    fun `matchesFilters excludes a recipe whose difficulty is not selected`() {
        assertFalse(matchesFilters(recipe(difficulty = 3), setOf(1, 2), null))
    }

    @Test
    fun `matchesFilters includes a recipe whose difficulty is selected`() {
        assertTrue(matchesFilters(recipe(difficulty = 2), setOf(1, 2), null))
    }

    @Test
    fun `matchesFilters always includes a recipe with unknown difficulty`() {
        assertTrue(matchesFilters(recipe(difficulty = null), setOf(1, 2), null))
    }

    @Test
    fun `matchesFilters excludes a recipe over the max cook time`() {
        assertFalse(matchesFilters(recipe(cookMinutesMin = 90), emptySet(), 60))
    }

    @Test
    fun `matchesFilters includes a recipe under the max cook time`() {
        assertTrue(matchesFilters(recipe(cookMinutesMin = 30), emptySet(), 60))
    }

    @Test
    fun `matchesFilters always includes a recipe with unknown cook time`() {
        assertTrue(matchesFilters(recipe(cookMinutesMin = null), emptySet(), 30))
    }

    // --- matchesKnownDifficulty / matchesKnownCookTime (ranking tiebreak, not inclusion) ---

    @Test
    fun `matchesKnownDifficulty is true for every recipe when no filter is active`() {
        assertTrue(matchesKnownDifficulty(recipe(difficulty = null), emptySet()))
    }

    @Test
    fun `matchesKnownDifficulty is true only for a genuine match, not an unrated pass-through`() {
        assertTrue(matchesKnownDifficulty(recipe(difficulty = 1), setOf(1)))
        assertFalse(matchesKnownDifficulty(recipe(difficulty = null), setOf(1)))
        assertFalse(matchesKnownDifficulty(recipe(difficulty = 2), setOf(1)))
    }

    @Test
    fun `matchesKnownCookTime is true only for a genuine match, not an unrated pass-through`() {
        assertTrue(matchesKnownCookTime(recipe(cookMinutesMin = 20), 30))
        assertFalse(matchesKnownCookTime(recipe(cookMinutesMin = null), 30))
        assertFalse(matchesKnownCookTime(recipe(cookMinutesMin = 60), 30))
    }
}
