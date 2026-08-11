package com.example.kitchenassistant

import com.example.kitchenassistant.data.IngredientMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [IngredientMatcher]'s four word lists. Every case here was checked against the real
 * `recipes.db` corpus, and the row counts in the comments are how much of the database each one
 * stands for — if one of these flips, search results move for a measurable slice of users.
 */
class IngredientMatcherTest {

    private fun assertMatches(fridge: String, canonical: String) =
        assertTrue(
            "expected fridge \"$fridge\" to satisfy recipe ingredient \"$canonical\"",
            IngredientMatcher.matches(fridge, canonical)
        )

    private fun assertDoesNotMatch(fridge: String, canonical: String) =
        assertFalse(
            "expected fridge \"$fridge\" NOT to satisfy recipe ingredient \"$canonical\"",
            IngredientMatcher.matches(fridge, canonical)
        )

    // -----------------------------------------------------------------------------------------
    // The reported bug: a modifier is not the thing itself
    // -----------------------------------------------------------------------------------------

    @Test
    fun `chicken does not satisfy chicken derived products`() {
        assertDoesNotMatch("chicken", "chicken broth")      // 15,444 rows
        assertDoesNotMatch("chicken", "chicken stock")
        assertDoesNotMatch("chicken", "chicken bouillon")   // 940 rows
        assertDoesNotMatch("chicken", "cream chicken soup") // 941 rows
        assertDoesNotMatch("chicken", "chicken fat")
        assertDoesNotMatch("chicken", "chicken base")
    }

    @Test
    fun `substring collisions no longer match`() {
        assertDoesNotMatch("egg", "eggplant")
        assertDoesNotMatch("ham", "graham cracker")
        assertDoesNotMatch("pea", "peanut")
        assertDoesNotMatch("rice", "licorice")
    }

    @Test
    fun `produce does not satisfy its processed forms`() {
        assertDoesNotMatch("tomato", "tomato sauce")
        assertDoesNotMatch("tomato", "tomato paste")
        assertDoesNotMatch("tomato", "tomato juice")
        assertDoesNotMatch("onion", "onion powder")
        assertDoesNotMatch("garlic", "garlic powder")
        assertDoesNotMatch("apple", "apple juice")
    }

    @Test
    fun `a derived product does not satisfy the base ingredient`() {
        assertDoesNotMatch("chicken broth", "chicken")
        assertDoesNotMatch("tomato paste", "tomato")
    }

    // -----------------------------------------------------------------------------------------
    // Regression guards: matches that must survive
    // -----------------------------------------------------------------------------------------

    @Test
    fun `egg still satisfies its whites and yolks`() {
        // 10,876 + 10,623 rows. These are the two largest canonicals after `egg` itself; if
        // `white` or `yolk` leaves PART_WORDS this silently drops ~21,900 rows of real matches.
        assertMatches("egg", "egg white")
        assertMatches("egg", "egg yolk")
    }

    @Test
    fun `chicken still satisfies cuts and preparations of chicken`() {
        assertMatches("chicken", "chicken")
        assertMatches("chicken", "chicken breast")
        assertMatches("chicken", "chicken breast half")
        assertMatches("chicken", "chicken breast without skin")
        assertMatches("chicken", "chicken thigh")
        assertMatches("chicken", "chicken wing")
        assertMatches("chicken", "chicken liver")
        assertMatches("chicken", "cut up chicken")
        assertMatches("chicken", "broiler fryer chicken")
        assertMatches("chicken", "frying chicken")
    }

    @Test
    fun `parts and cuts resolve to the whole ingredient`() {
        assertMatches("ginger", "ginger root")
        assertMatches("artichoke", "artichoke heart")
        assertMatches("pork", "pork chop")
        assertMatches("garlic", "garlic clove")
    }

    @Test
    fun `a specific fridge item satisfies a generic recipe ingredient`() {
        assertMatches("wheat flour", "flour")
        assertMatches("chicken breast", "chicken")
        assertMatches("cheddar cheese", "cheese")
    }

    @Test
    fun `a generic fridge item satisfies a specific variety`() {
        assertMatches("tomato", "cherry tomato")
        assertMatches("tomato", "plum tomato")
        assertMatches("cheese", "cheddar cheese")
        assertMatches("chicken broth", "low sodium chicken broth")
    }

    @Test
    fun `preparation adjectives are ignored`() {
        assertMatches("cream", "heavy cream")
        assertMatches("milk", "whole milk")
        assertMatches("butter", "unsalted butter")
        assertMatches("onion", "finely chopped onion")
    }

    @Test
    fun `corpus trailing junk still matches`() {
        // 693 canonicals end in a stray "or", 255 in a stray "w".
        assertMatches("thyme", "thyme or")
        assertMatches("butter", "butter or")
        assertMatches("flour", "all purpose flour mixed w")
    }

    // -----------------------------------------------------------------------------------------
    // BLOCK_MODIFIERS: compounds that are a different substance, not a variety
    // -----------------------------------------------------------------------------------------

    @Test
    fun `plain staples do not satisfy their compound namesakes`() {
        assertDoesNotMatch("butter", "peanut butter")
        assertDoesNotMatch("butter", "almond butter")
        assertDoesNotMatch("butter", "apple butter")
        assertDoesNotMatch("milk", "coconut milk")
        assertDoesNotMatch("milk", "soy milk")
        assertDoesNotMatch("milk", "sweetened condensed milk")
        assertDoesNotMatch("milk", "evaporated milk")
        assertDoesNotMatch("cream", "sour cream")
        assertDoesNotMatch("cream", "whipping cream")
        assertDoesNotMatch("cream", "ice cream")
        assertDoesNotMatch("cream", "cream of tartar")
        assertDoesNotMatch("onion", "spring onion")
        assertDoesNotMatch("cheese", "cream cheese")  // 9,787 rows
    }

    @Test
    fun `imitations and flavourings are not the real ingredient`() {
        // These three read as the real thing until `substitute` and `flavoring` were pulled back
        // out of STOPWORDS; "egg substitute" alone is 681 rows.
        assertDoesNotMatch("egg", "egg substitute")
        assertDoesNotMatch("egg", "liquid egg substitute")
        assertDoesNotMatch("butter", "butter flavoring")
    }

    @Test
    fun `the compound itself still matches its own recipes`() {
        assertMatches("peanut butter", "peanut butter")
        assertMatches("peanut butter", "creamy peanut butter")
        assertMatches("coconut milk", "coconut milk")
        assertMatches("sour cream", "sour cream")
    }

    @Test
    fun `bell and green are not blocked because pepper is ambiguous`() {
        // `black pepper` (26,552) and `green bell pepper` (5,053) share a head, so blocking
        // `bell` would break this without making `pepper` any less ambiguous.
        assertMatches("green pepper", "green bell pepper")
        assertMatches("black pepper", "freshly ground black pepper")
        assertDoesNotMatch("black pepper", "cayenne pepper")
    }

    // -----------------------------------------------------------------------------------------
    // Fridge-side truncation at connectives
    // -----------------------------------------------------------------------------------------

    @Test
    fun `taxonomy names do not leak their trailing clauses`() {
        // Without truncation these three would satisfy 77,410 / 91,863 / 101,689 rows apiece.
        assertDoesNotMatch("organic cocoa mass and organic cocoa butter", "butter")
        assertDoesNotMatch("brewed oolong tea without sugar", "sugar")
        assertDoesNotMatch("black and green pepper", "black pepper")
        assertDoesNotMatch("sodium salts of orthophosphoric acid", "salt")
    }

    @Test
    fun `truncation repairs long taxonomy names`() {
        assertMatches("chicken meat including natural chicken juices", "chicken")
        assertMatches("pasta made from wheat flour", "pasta")
        assertMatches("uht pasteurised whole milk", "milk")
    }

    @Test
    fun `the recipe side is never truncated`() {
        // Cutting at "of" would turn `cream of tartar` (2,348 rows) into `cream`.
        assertDoesNotMatch("cream", "cream of tartar")
        assertMatches("cream of tartar", "cream of tartar")
        assertMatches("half", "half and half")
    }

    // -----------------------------------------------------------------------------------------
    // Singularization
    // -----------------------------------------------------------------------------------------

    @Test
    fun `plural fridge names match singular canonicals`() {
        // 1,055 of 4,375 taxonomy names (24%) match nothing at all without this.
        assertMatches("eggs", "egg")
        assertMatches("tomatoes", "tomato")
        assertMatches("potatoes", "potato")
        assertMatches("dried goji berries", "goji berry")
        assertMatches("sprouted lentils", "lentil")
        assertMatches("rolled oats", "rolled oat")
        assertMatches("capers", "caper")
    }

    @Test
    fun `singular words that end in s keep their s`() {
        assertMatches("molasses", "molasses")
        assertDoesNotMatch("molasses", "molasse")
        assertMatches("hummus", "hummus")
        assertMatches("couscous", "couscous")
        assertMatches("asparagus", "asparagus")
    }

    // -----------------------------------------------------------------------------------------
    // Degenerate input
    // -----------------------------------------------------------------------------------------

    @Test
    fun `names that normalize to nothing match nothing`() {
        // 909 canonicals reduce to an empty word list. A null head must never match, including
        // against another null head, or every heading row would look satisfied.
        for (empty in listOf("", "   ", "-----", "fresh", "chopped", "1/4", "%%%")) {
            assertDoesNotMatch(empty, "chicken")
            assertDoesNotMatch("chicken", empty)
            assertDoesNotMatch(empty, empty)
        }
    }
}
