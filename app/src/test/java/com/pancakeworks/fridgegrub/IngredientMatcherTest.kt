package com.pancakeworks.fridgegrub

import com.pancakeworks.fridgegrub.data.IngredientMatcher
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
    fun `trailing preparation participles do not steal the head`() {
        // Real corpus rows: without these in STOPWORDS, the trailing participle becomes the head
        // instead of the actual ingredient, so e.g. fridge "cheese" silently failed to match
        // "blue cheese crumbled" even though nothing about category expansion was involved --
        // pure head misdetection.
        assertMatches("cheese", "blue cheese crumbled")
        assertMatches("egg", "eggs at room temperature separated")
        assertMatches("egg", "large egg separated divided")
        assertMatches("potato", "potatoes quartered")
        assertMatches("lemon", "lemon juiced")
        assertMatches("almond", "almonds toasted")
        assertMatches("shrimp", "shrimp deveined")
        assertMatches("bean", "beans undrained")
        assertMatches("garlic", "garlic pressed")
        assertMatches("apple", "apples halved and cored")
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
    fun `quantity words fused onto a block modifier are still blocked`() {
        // Real corpus rows: a missing space between a quantity and the next word (e.g.
        // "2 gr cream cheese" saved as "grcream cheese") glues a BLOCK_MODIFIERS entry onto a
        // quantity word, so the literal token is never in BLOCK_MODIFIERS and the match used to
        // slip through -- which NewIngredientIndex's category expansion then spread to every
        // other ingredient sharing that category (e.g. all of Cream Cheese, satisfied by plain
        // "cheese"). See ingredient_id 2325 in the bundled corpus.
        assertDoesNotMatch("cheese", "grcream cheese")
        assertDoesNotMatch("butter", "tablespoonspeanut butter")
        assertDoesNotMatch("butter", "tbsppeanut butter")
        assertDoesNotMatch("butter", "grpeanut butter")
        assertDoesNotMatch("sugar", "cuppowdered sugar")
        assertDoesNotMatch("flour", "gralmond flour")
        assertDoesNotMatch("extract", "tspalmond extract")
        assertDoesNotMatch("milk", "cancondensed milk")
        assertDoesNotMatch("milk", "canevaporated milk")
        assertDoesNotMatch("milk", "cupcoconut milk")
        assertDoesNotMatch("cream", "cansour cream")
        assertDoesNotMatch("cream", "cupwhipping cream")
        assertMatches("buttermilk", "buttermilk") // sanity: the compound itself still matches
        assertDoesNotMatch("sauce", "tablespoonsoy sauce")

        // The single-letter "g"/"c" abbreviations (grams, cups/cans) fuse the same way.
        // ingredient_id 2819 in the bundled corpus -- this is the exact row that let plain
        // "cheese" wrongly satisfy the whole Cream Cheese category via NewIngredientIndex's
        // category expansion.
        assertDoesNotMatch("cheese", "gcream cheese")
        assertDoesNotMatch("cream", "gsour cream")
        assertDoesNotMatch("cream", "csour cream")
        assertDoesNotMatch("sugar", "gpowdered sugar")
    }

    @Test
    fun `single-letter quantity abbreviations still leave real words alone`() {
        // "g" + "oat" reconstructs "goat" and "c" + "oat" reconstructs "coat" -- both real,
        // common words, not fusion typos. PREFIX_FUSION_EXCEPTIONS carves these two out so
        // legitimate goat recipes don't get wrongly blocked.
        assertMatches("cheese", "goat cheese")
        assertMatches("milk", "goat milk")
    }

    // -----------------------------------------------------------------------------------------
    // isSpecificVariantOf: the one-directional half of matches, for popularity counting
    // -----------------------------------------------------------------------------------------

    private fun assertVariant(less: String, more: String) =
        assertTrue(
            "expected \"$more\" to be \"$less\" or a more specific variant of it",
            IngredientMatcher.isSpecificVariantOf(
                IngredientMatcher.parseFridge(less),
                IngredientMatcher.parseRecipe(more)
            )
        )

    private fun assertNotVariant(less: String, more: String) =
        assertFalse(
            "expected \"$more\" NOT to count as \"$less\" or a more specific variant of it",
            IngredientMatcher.isSpecificVariantOf(
                IngredientMatcher.parseFridge(less),
                IngredientMatcher.parseRecipe(more)
            )
        )

    @Test
    fun `a more specific variant counts, the reverse does not`() {
        // The reported bug: "chicken egg" satisfies plain "egg" as a fridge item (egg is egg), so
        // matches() correctly allows it -- but that's the wrong direction for popularity, since it
        // credited "chicken egg" with plain "egg"'s entire (much larger) corpus frequency.
        // IngredientPopularityIndex calls isSpecificVariantOf(candidate, corpusRow), so the
        // direction that matters for that bug is: is corpus row "egg" a variant of candidate
        // "chicken egg"? No -- "egg" is a different, more general thing, not a specific chicken-egg
        // variant, so it must not count toward it.
        assertVariant("chicken breast", "boneless chicken breast")
        assertVariant("chicken egg", "chicken egg yolk")
        assertNotVariant("chicken egg", "egg")
        assertNotVariant("chicken breast", "chicken")
        // The reverse direction is legitimate, though: "egg" (general) is satisfied by "chicken
        // egg" (specific) being in the corpus, same as "chicken" aggregates its named cuts below.
        assertVariant("egg", "chicken egg")
    }

    @Test
    fun `a bare term is a variant of its own more specific cuts`() {
        // This is what lets bare "chicken" aggregate frequency across all its named cuts.
        assertVariant("chicken", "chicken breast")
        assertVariant("chicken", "chicken thigh")
    }

    @Test
    fun `block modifiers still reject the more-specific direction`() {
        assertNotVariant("cheese", "cream cheese")
        assertNotVariant("milk", "powdered milk")
        assertNotVariant("butter", "peanut butter")
    }

    @Test
    fun `the same term is trivially its own variant`() {
        assertVariant("chicken", "chicken")
        assertVariant("chicken egg", "chicken egg")
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
    // isDifferentSubstance: the category-expansion guard
    // -----------------------------------------------------------------------------------------

    private fun assertDifferentSubstance(fridge: String, canonical: String) =
        assertTrue(
            "expected \"$canonical\" to be rejected as a different substance from fridge \"$fridge\"",
            IngredientMatcher.isDifferentSubstance(
                IngredientMatcher.parseFridge(fridge),
                IngredientMatcher.parseRecipe(canonical)
            )
        )

    private fun assertNotDifferentSubstance(fridge: String, canonical: String) =
        assertFalse(
            "expected \"$canonical\" NOT to be flagged as a different substance from fridge \"$fridge\"",
            IngredientMatcher.isDifferentSubstance(
                IngredientMatcher.parseFridge(fridge),
                IngredientMatcher.parseRecipe(canonical)
            )
        )

    @Test
    fun `isDifferentSubstance flags exactly the same pairs matches already blocks`() {
        // NewIngredientIndex.Dairy Milk (category_id 98) holds both "milk" and "powdered milk" --
        // the exact real row that let category expansion resurrect a BLOCK_MODIFIERS rejection.
        assertDifferentSubstance("milk", "powdered milk")
        assertDifferentSubstance("butter", "peanut butter")
        assertDifferentSubstance("cheese", "cream cheese")
        assertDifferentSubstance("cream", "sour cream")
    }

    @Test
    fun `isDifferentSubstance is false for a head mismatch, not just a non-match`() {
        // This is what lets NewIngredientIndex's cross-head expansion ("beef" reaching "ribeye")
        // survive the guard: those pairs fail matches() too, but not because of a blocked
        // modifier, so the category boost must still be allowed to add them.
        assertNotDifferentSubstance("beef", "ribeye")
        assertNotDifferentSubstance("chicken", "chicken broth")
        assertNotDifferentSubstance("black pepper", "cayenne pepper")
    }

    @Test
    fun `isDifferentSubstance is false whenever matches would succeed`() {
        assertNotDifferentSubstance("cheese", "cheddar cheese")
        assertNotDifferentSubstance("chicken", "chicken breast half")
        assertNotDifferentSubstance("milk", "milk")
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
