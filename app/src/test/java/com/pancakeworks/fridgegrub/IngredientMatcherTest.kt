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
    fun `chunks, wedges and cubes resolve to the whole ingredient`() {
        // Real corpus rows found while auditing every canonical whose head landed on the literal
        // last word -- same "a cut is still the thing" pattern as the rest of PART_WORDS.
        assertMatches("pineapple", "pineapple chunks")
        assertMatches("beef", "beef chunks")
        assertMatches("cauliflower", "big cauliflower cut into chunks")
        assertMatches("lemon", "lemon wedge")
        assertMatches("lemon", "lemon wedges")
        assertMatches("orange", "canned mandarin orange wedges in syrup")
        assertMatches("bread", "bread cubes")
        assertMatches("onion", "onion cubes")
        assertMatches("sugar", "sugar cubes")
        // The bouillon/stock/seasoning "cube" family should resolve the same way its "cube"-less
        // counterpart already does -- "chicken bouillon" (no cube) has head "bouillon", not
        // "chicken" (see `chicken does not satisfy chicken derived products`), so
        // "chicken bouillon cube" landing anywhere but "bouillon" would be inconsistent.
        assertMatches("bouillon", "chicken bouillon cube")
        assertMatches("stock", "chicken stock cube")
        assertDoesNotMatch("chicken", "chicken bouillon cube")
        assertMatches("cinnamon", "cinnamon stick")
        assertMatches("cinnamon", "cinnamon sticks")
        assertMatches("celery", "celery sticks")
    }

    @Test
    fun `trailing for serving does not steal the head`() {
        // Real corpus rows: "for serving" is a common trailing clause, distinct from the
        // preparation participles above but the same class of bug -- without "serving" in
        // STOPWORDS these resolved to head "serving" instead of the actual ingredient.
        assertMatches("oil", "extra-virgin olive oil for serving")
        assertMatches("bun", "hamburger buns for serving")
        assertMatches("pasta", "enough pasta for 4 servings")
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
        assertMatches("butter", "unsalted butter")
        assertMatches("onion", "finely chopped onion")
    }

    // -----------------------------------------------------------------------------------------
    // `whole` is a real word, not a preparation adjective (see STOPWORDS' doc)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `whole is a real word, not an ignorable preparation adjective`() {
        // The reported bug: fridge "whole chicken" was searched no differently from bare
        // "chicken" (whole was stripped as filler), and bare "chicken" is deliberately allowed to
        // satisfy any specific cut -- so "whole chicken" was directly matching chicken thighs/
        // wings/breast recipes, not merely reaching them through category expansion.
        assertDoesNotMatch("whole chicken", "chicken thigh")
        assertDoesNotMatch("whole chicken", "chicken wing")
        assertDoesNotMatch("whole chicken", "chicken breast")
        // Same distinction for milk: "whole milk" (a fat-content grade) is not "skim milk".
        assertDoesNotMatch("whole milk", "skim milk")
    }

    @Test
    fun `whole still satisfies what it should`() {
        // Fridge more general still satisfies a recipe's bare ingredient -- unaffected by whole
        // no longer being stripped, since this direction only needs the recipe's words to be a
        // subset of the fridge's.
        assertMatches("whole chicken", "chicken")
        assertMatches("whole milk", "milk")
        // A recipe that actually calls for a whole bird/whole milk still matches exactly.
        assertMatches("whole chicken", "whole chicken")
        assertMatches("whole milk", "whole milk")
        // The other direction (fridge bare, recipe says "whole X") is untouched either way --
        // fridge is still the more general side here.
        assertMatches("milk", "whole milk")
        assertMatches("chicken", "whole chicken")
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

    @Test
    fun `parenthetical asides and stray single letters do not steal the head`() {
        // 290 corpus rows have a parenthetical note or aside; without cutting there, "(optional",
        // "(e.g", or a unit-conversion aside becomes the head. 286 more rows have a stray
        // single-letter token ("e"/"g"/"s"/"x") that must never itself become the head.
        assertMatches("cayenne pepper", "cayenne pepper (optional")
        assertMatches("chile powder", "chile powder (optional")
        assertMatches("miso", "% by volume miso (red")
        assertMatches("vegetable", "assorted vegetables (e.g")
        assertMatches("bell pepper", "bell peppers (red")
    }

    @Test
    fun `bone-in does not steal the head from the in-cut`() {
        // "bone-in" (108 rows) tokenizes to "bone", "in" -- the "in" cut fired immediately and
        // stranded the head on "bone" before the carve-out below. "boneless"/"skinless" are the
        // same class of cut/bone-content descriptor as "bone-in" itself.
        assertMatches("chicken", "bone-in chicken thighs")
        assertMatches("pork chop", "thick-cut bone-in pork rib chops")
        assertMatches("chicken thigh", "boneless skinless chicken thighs and drumsticks")
        assertMatches("salmon", "skin-on coho salmon fillets")
    }

    @Test
    fun `the recipe side truncates at in, unlike other connectives`() {
        // The reported bug: "tuna in water" resolved to head "water" instead of "tuna", because
        // dropping (not cutting) "in" left "water" as the last surviving word. Real corpus rows
        // following the same "food in packing medium" shape.
        assertMatches("tuna", "tuna in water")
        assertMatches("tuna", "chunk tuna in water")
        assertMatches("chile", "chipotle chiles in adobo sauce")
        assertMatches("pineapple", "canned pineapple in juice")
        assertMatches("olive", "green olives packed in brine")
        assertDoesNotMatch("water", "tuna in water")
    }

    @Test
    fun `the recipe side also truncates at for, same shape as in`() {
        // Same "food in packing medium" shape as the "in" cut, one clause later: without cutting
        // here, "frying"/"browning"/"coating" (236 " for " rows) were winning the head instead of
        // the real ingredient before "for".
        assertMatches("vegetable oil", "vegetable oil for frying")
        assertMatches("oil", "oil for deep frying")
        assertMatches("butter", "butter for browning")
        assertMatches("sugar", "extra sugar for coating")
    }

    @Test
    fun `as required and as necessary do not steal the head, unlike a real as-example`() {
        // "as"/"such as" is deliberately NOT cut like "for" is -- most rows name the real
        // ingredient right after it, which effectiveHead already resolves correctly by taking the
        // last word. Only the abstract, non-food exceptions need their own STOPWORDS entry.
        assertMatches("water", "warm water as required")
        assertMatches("water", "water as necessary")
        // Regression guard: the common case, where "as"/"such as" precedes the real ingredient,
        // must keep working exactly as it already does today.
        assertMatches("tomato", "vegetables such as tomatoes")
        assertMatches("spinach", "handfuls of a leafy green such as spinach")
        assertMatches("porter", "fl oz beer such as porter")
    }

    @Test
    fun `mid-string preparation participles do not steal the head from an in-clause`() {
        // Real corpus rows: "dissolved" and "tied" aren't trailing like the participles in
        // `trailing preparation participles do not steal the head`, but the "in" cut still exposes
        // them as the last surviving word unless they're stopwords too.
        assertMatches("yeast", "dry yeast dissolved in ½ cup of warm water")
        assertMatches("pandan", "pandan leaves tied in a knot")
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
    // Diacritics
    // -----------------------------------------------------------------------------------------

    @Test
    fun `accented names match their plain-ascii equivalents`() {
        // TOKEN_SEPARATOR splits on anything that isn't a lowercase ascii letter, so an accented
        // letter used to act as a separator and strand everything after it as its own token --
        // "tomato purée" -> head "e" (258 corpus rows total across these patterns).
        assertMatches("tomato puree", "tomato purée")
        assertMatches("jalapeno", "jalapeños")
        assertMatches("creme fraiche", "crème fraîche")
        assertMatches("crouton", "croûtons")
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
