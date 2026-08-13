package com.example.kitchenassistant

import com.example.kitchenassistant.viewmodel.IngredientViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards [IngredientViewModel.rankSuggestions], the autocomplete dropdown's ranking. Reported
 * bug: sorting by raw string length buried "chicken breast" 16th out of 50 real "chicken"
 * matches in the bundled `ingredients.db` — behind rarer cuts like "chicken blood" and "chicken
 * stomach" purely because they happen to be a couple of characters shorter.
 *
 * Follow-up: word count alone is only a proxy, used before the real signal —
 * [com.example.kitchenassistant.data.IngredientPopularityIndex]'s recipe-corpus frequency — has
 * finished loading (`ingredients.db` itself has none). The `frequencyOf` cases below guard that
 * once real popularity is available, it wins over word count, not just alphabetical order.
 */
class IngredientViewModelTest {

    @Test
    fun `common two-word cuts outrank longer names by word count, not length`() {
        // The word-count fallback used before frequencyOf loads (or when it reports everyone
        // equally unmeasured): "chicken breast" (14 chars) used to sort behind "chicken neck"
        // (12 chars) purely on length, despite both being equally general 2-word entries.
        val candidates = listOf(
            "chicken", "chicken neck", "chicken breast", "chicken stomach", "chicken blood"
        )
        val ranked = IngredientViewModel.rankSuggestions(candidates, "chicken")
        assertEquals(listOf("chicken", "chicken blood", "chicken breast", "chicken neck", "chicken stomach"), ranked)
    }

    @Test
    fun `real frequency outranks word count once available`() {
        // With frequencyOf supplied, "chicken breast" (common in the recipe corpus) jumps ahead
        // of "chicken blood" and "chicken stomach" (rare) even though all three tie on word count.
        val candidates = listOf("chicken breast", "chicken blood", "chicken stomach")
        val frequency = mapOf("chicken breast" to 4000, "chicken blood" to 3, "chicken stomach" to 1)
        val ranked = IngredientViewModel.rankSuggestions(candidates, "chicken") { frequency[it] ?: 0 }
        assertEquals(listOf("chicken breast", "chicken blood", "chicken stomach"), ranked)
    }

    @Test
    fun `frequency only breaks ties within a group, prefix-vs-mid-word grouping still wins`() {
        // Frequency only breaks ties within the prefix/mid-word groups -- a low-frequency prefix
        // match must still outrank a high-frequency mid-word match, since that grouping exists to
        // keep "chicken" itself above "cream of chicken soup" regardless of either's popularity.
        val candidates = listOf("cream of chicken soup", "chicken")
        val frequency = mapOf("cream of chicken soup" to 9999, "chicken" to 1)
        val ranked = IngredientViewModel.rankSuggestions(candidates, "chicken") { frequency[it] ?: 0 }
        assertEquals(listOf("chicken", "cream of chicken soup"), ranked)
    }

    @Test
    fun `prefix matches always outrank mid-word matches`() {
        // Only "cheese" itself starts with "cheese"; "cream cheese" and "cheddar cheese" contain
        // it mid-word, so both rank after -- alphabetically between themselves.
        val candidates = listOf("cream cheese", "cheese", "cheddar cheese")
        val ranked = IngredientViewModel.rankSuggestions(candidates, "cheese")
        assertEquals(listOf("cheese", "cheddar cheese", "cream cheese"), ranked)
    }

    @Test
    fun `exact match sorts first within the prefix group`() {
        val candidates = listOf("chicken bone", "chicken fat", "chicken")
        val ranked = IngredientViewModel.rankSuggestions(candidates, "chicken")
        assertEquals("chicken", ranked.first())
    }

    @Test
    fun `ties within the same word count break alphabetically`() {
        val candidates = listOf("chicken wing", "chicken egg", "chicken fat")
        val ranked = IngredientViewModel.rankSuggestions(candidates, "chicken")
        assertEquals(listOf("chicken egg", "chicken fat", "chicken wing"), ranked)
    }

    @Test
    fun `matching is case-insensitive`() {
        val candidates = listOf("Chicken Breast", "CHICKEN")
        val ranked = IngredientViewModel.rankSuggestions(candidates, "chicken")
        assertEquals(listOf("CHICKEN", "Chicken Breast"), ranked)
    }

    @Test
    fun `non-matching candidates are excluded`() {
        val candidates = listOf("chicken", "beef", "tofu")
        val ranked = IngredientViewModel.rankSuggestions(candidates, "chicken")
        assertEquals(listOf("chicken"), ranked)
    }

    @Test
    fun `empty query yields no suggestions`() {
        val candidates = listOf("chicken", "beef")
        assertEquals(emptyList<String>(), IngredientViewModel.rankSuggestions(candidates, ""))
    }
}
