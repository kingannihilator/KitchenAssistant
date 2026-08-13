package com.example.kitchenassistant.data

/**
 * Decides whether an ingredient sitting in the user's fridge satisfies an ingredient a recipe
 * calls for.
 *
 * This is the single source of truth for ingredient matching. Recipe scoring
 * ([com.example.kitchenassistant.viewmodel.RecipeViewModel.searchRecipes]), the detail screen's
 * green-check / red-X list, and cook mode all go through here, so a card that says
 * "8/8 ingredients" and its detail screen can never disagree.
 *
 * ## The rule
 *
 * A name is reduced to a [Term]: a set of content words plus a *head* — the word that carries the
 * thing's identity. English noun compounds are head-final ("A B" is a kind of B), so the head of
 * "chicken broth" is `broth`, not `chicken`. Two terms match when
 *
 *  1. their heads are equal, **and**
 *  2. one word set contains the other (either side may be the more specific one), **and**
 *  3. the extra words on the more specific side are not [BLOCK_MODIFIERS].
 *
 * Rule 1 is what stops fridge "chicken" from matching `chicken broth`, `chicken bouillon` and
 * `cream chicken soup` — the bug this class was written for. [PART_WORDS] carve out the exception
 * that matters: a cut or part of a thing is still that thing, so `chicken breast half` has its
 * trailing `half` and `breast` stripped and comes back to head `chicken`.
 *
 * Rule 2 works in both directions. Recipe "flour" is satisfied by fridge "wheat flour" (recipe
 * side is more general), and fridge "chicken" satisfies recipe "chicken thigh" (fridge side is
 * more general).
 *
 * Rule 3 catches compounds that are a different substance rather than a variety of their head:
 * having plain butter does not mean you have peanut butter.
 *
 * ## Why fridge and recipe names are normalized differently
 *
 * Recipe names come from `clean_ingredients.canonical`, which is already lemmatized and terse
 * (`breadcrumb`, `ground turkey`). Fridge names come from the OpenFoodFacts taxonomy in
 * `ingredients.db`, which contains long descriptive entries such as
 * "organic cocoa mass and organic cocoa butter" and "brewed oolong tea without sugar".
 *
 * Taken whole, those trailing clauses are poison: "…and organic cocoa butter" would make every
 * butter recipe look satisfied. So the **fridge side truncates** at the first connective in
 * [FRIDGE_CUT], keeping only the leading noun phrase. The **recipe side never truncates** — the
 * only canonicals containing connectives are things like `cream of tartar` and `half and half`,
 * and cutting those would let fridge "cream" swallow 2,348 rows of cream of tartar. There, the
 * connectives are dropped as ordinary stop words instead, which also cleans up the corpus's
 * trailing junk (`thyme or`, `all purpose flour mixed w`).
 */
object IngredientMatcher {

    /**
     * A normalized ingredient name.
     *
     * @property words de-duplicated content words, after stop-word removal and singularization.
     * @property head the word carrying the ingredient's identity, or `null` when nothing survived
     *   normalization (a heading like `"-----"`, or a bare adjective like `"chopped"`). A term with
     *   a null head matches nothing, including another null-headed term.
     */
    class Term internal constructor(
        val words: Set<String>,
        val head: String?
    )

    /** Parses a fridge ingredient name, truncating at the first [FRIDGE_CUT] connective. */
    fun parseFridge(name: String): Term = parse(name, cut = true)

    /** Parses a recipe's `clean_ingredients.canonical` value. Never truncates. */
    fun parseRecipe(canonical: String): Term = parse(canonical, cut = false)

    /** True when the fridge ingredient satisfies the recipe's ingredient. */
    fun matches(fridge: Term, recipe: Term): Boolean {
        val fridgeHead = fridge.head ?: return false
        val recipeHead = recipe.head ?: return false
        if (fridgeHead != recipeHead) return false

        // Whichever side is more specific carries the extra words; if neither contains the other
        // they describe different things ("black pepper" vs "cayenne pepper").
        val extra = when {
            recipe.words.containsAll(fridge.words) -> recipe.words - fridge.words
            fridge.words.containsAll(recipe.words) -> fridge.words - recipe.words
            else -> return false
        }
        return extra.none { isBlockedModifier(it) }
    }

    /**
     * True when [recipe] is specifically rejected as a different substance from [fridge] — same
     * head, but a [BLOCK_MODIFIERS] word among the extra words (`fridge=milk` vs
     * `recipe=powdered milk`). False for every other reason two terms fail to match, including a
     * head mismatch, which is what lets [NewIngredientIndex]'s cross-head category expansion work
     * at all (`beef` reaching `ribeye` shares no words, let alone a blocked one).
     *
     * [NewIngredientIndex] calls this to stop category expansion from re-admitting a sibling that
     * direct matching already rejected for exactly this reason. Without it, categories that group a
     * plain ingredient with its blocked variant (`Dairy Milk` holds both `milk` and `powdered
     * milk`) let the category boost silently overrule [BLOCK_MODIFIERS]: fridge `milk` string
     * matches the `milk` row, and expansion then adds every other row sharing that category —
     * `powdered milk` included — even though direct matching correctly rejects it.
     */
    fun isDifferentSubstance(fridge: Term, recipe: Term): Boolean {
        val fridgeHead = fridge.head ?: return false
        val recipeHead = recipe.head ?: return false
        if (fridgeHead != recipeHead) return false

        val extra = when {
            recipe.words.containsAll(fridge.words) -> recipe.words - fridge.words
            fridge.words.containsAll(recipe.words) -> fridge.words - recipe.words
            else -> return false
        }
        return extra.any { isBlockedModifier(it) }
    }

    /**
     * True when [more] names the same thing as [less] or a more specific variant of it — same
     * head, [more]'s words a superset of [less]'s, and whatever's extra isn't a [BLOCK_MODIFIERS]
     * word. Unlike [matches], only this one direction counts; [less] being the more specific side
     * (the direction that lets a fridge item satisfy a more general recipe requirement) does not.
     *
     * [IngredientPopularityIndex] calls this instead of [matches] specifically because that
     * direction is wrong for counting popularity. Fridge "chicken egg" legitimately *satisfies* a
     * recipe that just calls for "egg" — egg is egg — so [matches] correctly says yes both ways.
     * But summing every recipe-corpus row a candidate would satisfy that way credited "chicken egg"
     * with plain "egg"'s entire frequency (in the thousands, since eggs are in nearly everything),
     * vaulting it above "chicken" itself and "chicken breast" for a "chicken" search — backwards
     * from what popularity should mean here. Restricting to "more is less-or-more-specific" keeps
     * "chicken breast" crediting from "boneless chicken breast" (more specific, same thing) while
     * excluding "egg"/"egg yolk" (a different, more general thing "chicken egg" merely satisfies).
     */
    fun isSpecificVariantOf(less: Term, more: Term): Boolean {
        val lessHead = less.head ?: return false
        val moreHead = more.head ?: return false
        if (lessHead != moreHead) return false
        if (!more.words.containsAll(less.words)) return false
        val extra = more.words - less.words
        return extra.none { isBlockedModifier(it) }
    }

    /**
     * True when [word] is a modifier that should reject the match — either the word itself, or a
     * [QUANTITY_PREFIXES] entry glued directly onto one with no separating space.
     *
     * The corpus has rows like `grcream cheese` and `tablespoonspeanut butter` — a missing space
     * between a quantity ("gr", "tablespoons") and the next word, which [TOKEN_SEPARATOR] can't
     * detect since both sides are letters. Written correctly these would tokenize to `cream`/
     * `peanut` and get blocked normally; fused, the literal token is never in [BLOCK_MODIFIERS] and
     * the match slips through — and since [NewIngredientIndex] expands a direct match to every
     * ingredient sharing its category, one bad row like this can wrongly mark a whole sibling group
     * (e.g. all `Cream Cheese`-category rows) as satisfied by plain `cheese`.
     *
     * [QUANTITY_PREFIXES] includes the single-letter abbreviations `g` and `c` (grams, cups/cans —
     * both appear glued on in the corpus, e.g. `gcream cheese`), guarded by
     * [PREFIX_FUSION_EXCEPTIONS]: `g` + `oat` reconstructs `goat`, and `c` + `oat` reconstructs
     * `coat`, both real words that appear legitimately (`goat cheese`, `goat milk`). Every other
     * single-letter-prefix-plus-modifier combination was checked by hand and isn't a real word, so
     * this is the only carve-out needed.
     */
    private fun isBlockedModifier(word: String): Boolean {
        if (word in BLOCK_MODIFIERS) return true
        if (word in PREFIX_FUSION_EXCEPTIONS) return false
        for (prefix in QUANTITY_PREFIXES) {
            if (word.length > prefix.length && word.startsWith(prefix) &&
                word.substring(prefix.length) in BLOCK_MODIFIERS
            ) {
                return true
            }
        }
        return false
    }

    /** Convenience overload for one-off comparisons; parses both sides every call. */
    fun matches(fridgeName: String, canonical: String): Boolean =
        matches(parseFridge(fridgeName), parseRecipe(canonical))

    // ---------------------------------------------------------------------------------------
    // Normalization
    // ---------------------------------------------------------------------------------------

    // Splits on anything that isn't a lowercase letter, so digits and punctuation act as
    // separators and vanish. Without this, "rye flour type 170" and "82% fat unsalted butter"
    // would put their head on a number.
    private val TOKEN_SEPARATOR = Regex("[^a-z]+")

    private fun parse(raw: String, cut: Boolean): Term {
        val ordered = mutableListOf<String>()
        for (token in raw.lowercase().split(TOKEN_SEPARATOR)) {
            if (token.isEmpty()) continue
            // Only truncate once we have something to keep, so a name that opens with a
            // connective doesn't collapse to nothing.
            if (cut && ordered.isNotEmpty() && token in FRIDGE_CUT) break
            if (token in STOPWORDS) continue
            if (!cut && token in RECIPE_ONLY_STOPWORDS) continue
            ordered.add(singularize(token))
        }
        return Term(words = ordered.toSet(), head = effectiveHead(ordered))
    }

    /**
     * The identity word: the last word, skipping trailing [PART_WORDS] so that a cut of a thing
     * resolves to the thing ("chicken breast half" -> "chicken").
     *
     * Stops at index 0 so a name that *is* a part word ("liver", "breast") keeps its own head.
     */
    internal fun effectiveHead(words: List<String>): String? {
        var i = words.lastIndex
        while (i > 0 && words[i] in PART_WORDS) i--
        return words.getOrNull(i)
    }

    /**
     * Conservative singularization. `canonical` is already lemmatized, but taxonomy names are not:
     * without this, "dried goji berries" and "sprouted lentils" match nothing at all.
     *
     * Deliberately timid — over-stemming silently breaks matches that used to work, so anything
     * ambiguous is left alone.
     */
    internal fun singularize(word: String): String {
        if (word.length <= 3) return word
        IRREGULAR_PLURALS[word]?.let { return it }
        if (word in NEVER_STEM) return word
        return when {
            word.endsWith("ies") -> word.dropLast(3) + "y"
            word.endsWith("oes") -> word.dropLast(2)
            word.endsWith("ches") || word.endsWith("shes") ||
                word.endsWith("xes") || word.endsWith("zes") -> word.dropLast(2)
            // "molasses", "hummus", "couscous", "asparagus", "watercress" all land here.
            word.endsWith("ss") || word.endsWith("us") || word.endsWith("is") -> word
            word.endsWith("s") -> word.dropLast(1)
            else -> word
        }
    }

    // ---------------------------------------------------------------------------------------
    // Word lists
    //
    // All four are tuned against the real corpus (93,203 distinct canonicals) and guarded by
    // IngredientMatcherTest. Adding or removing an entry changes search results, so change them
    // with a test.
    // ---------------------------------------------------------------------------------------

    /**
     * Stripped from the right when finding the head, because a part or cut of an ingredient is
     * still that ingredient.
     *
     * `white` and `yolk` are here for `egg white` (10,876 rows) and `egg yolk` (10,623) — the two
     * largest canonicals after `egg` itself. Leaving them out silently drops 21,900 rows of
     * genuine egg matches.
     *
     * Deliberately absent after checking the corpus: `steak` (turns `round steak` into head
     * `round`, a net loss), `flake` (`corn flake` is not corn, and `red pepper flake` is not black
     * pepper), `strip` (9 rows), `end` (matches junk titles).
     */
    private val PART_WORDS = setOf(
        "breast", "thigh", "wing", "leg", "drumstick", "liver", "fillet", "filet", "cutlet",
        "meat", "part", "half", "piece", "slice", "clove", "bulb", "stalk", "sprig", "leaf",
        "kernel", "floret", "chop", "loin", "rib", "shank", "tenderloin", "tip", "top", "stem",
        "root", "skin", "bone", "heart", "gizzard", "neck", "white", "yolk"
    )

    /**
     * Dropped from both sides: preparation participles, sizes, quality adjectives, and the unit
     * abbreviations that leak into the corpus. These describe how an ingredient was handled, not
     * what it is, so "heavy cream" and "cream" are the same thing.
     */
    private val STOPWORDS = setOf(
        "of", "the", "a", "an", "to", "all", "purpose", "w", "wo", "x", "c", "t",
        "fresh", "freshly", "frozen", "dried", "dry", "raw", "cooked", "uncooked", "chopped",
        "minced", "sliced", "diced", "grated", "shredded", "melted", "softened", "beaten",
        "peeled", "pared", "seeded", "cored", "trimmed", "rinsed", "drained", "packed", "canned",
        "whole", "large", "small", "medium", "med", "lge", "lg", "sm", "jumbo", "extra",
        "fine", "finely", "coarse", "coarsely", "thin", "thinly", "thick",
        "organic", "natural", "unsalted", "salted", "lightly", "well", "hot", "cold", "warm",
        "room", "temperature", "good", "quality", "best", "pure", "real", "plain", "regular",
        "light", "heavy", "firm", "soft", "reduced", "low", "nonfat", "free",
        "approximately", "about", "approx", "cut", "up", "new", "old", "assorted", "mixed",
        "prepared", "instant", "quick", "ready", "level", "little", "pat", "size", "sized",
        "type", "brand", "style", "divided", "needed", "taste", "desired",
        // More preparation participles, same family as the ones above -- found by scanning the
        // new corpus's ingredients table for words ending a canonical that aren't already
        // covered, so they were wrongly becoming the head (e.g. "blue cheese crumbled" resolved
        // to head "crumbled" instead of "cheese", silently failing to match fridge "cheese").
        // Row counts are canonicals ending in that word in the bundled recipe_database.sqlite.
        "removed", "quartered", "halved", "thawed", "crushed", "reserved", "crumbled", // 200/164/163/144/128/70/61
        "toasted", "deveined", "chilled", "juiced", "undrained", "mashed", "warmed", // 58/58/56/55/53/52/36
        "pitted", "sifted", "flaked", "cooled", "separated", "zested", "scrubbed", // 36/27/25/24/24/22/20
        "unpeeled", "discarded", "cleaned", "stemmed", "pressed", "heated", "undiluted", // 12/12/12/10/9/8/7
        "baked", "unwrapped", "blanched", "defrosted", "slivered", "boiled", "pureed", "squeezed" // 7/5/5/5/5/5/5/5
    )
    // Deliberately not stop words, though they look like ones: `flavoring`/`flavour` and
    // `substitute` change what a thing *is*. Dropping them made "butter flavoring" read as butter
    // and "egg substitute" (681 rows) read as egg. Left in place, they become the head and the
    // match is correctly rejected.

    /**
     * Dropped from the recipe side only. On the fridge side these truncate instead (see
     * [FRIDGE_CUT]); here they must be dropped rather than cut so `cream of tartar` keeps its
     * `tartar` head and `chicken breast without skin` still resolves to `chicken`.
     */
    private val RECIPE_ONLY_STOPWORDS = setOf(
        "and", "or", "with", "without", "in", "for", "from", "into", "on", "at", "by"
    )

    /**
     * The fridge side stops here. Taxonomy names trail off into clauses that name a second,
     * unrelated ingredient — "organic cocoa mass and organic cocoa butter" would otherwise satisfy
     * every butter recipe in the database.
     */
    private val FRIDGE_CUT = setOf(
        "and", "or", "with", "without", "including", "include", "from", "made", "plus", "such",
        "like", "etc", "in", "for", "optional", "use", "used"
    )

    /**
     * Words that make a compound a different substance rather than a variety of its head. When
     * these appear only on the more specific side, the match is rejected: plain butter is not
     * peanut butter, plain cream is not sour cream, plain milk is not coconut milk.
     *
     * `green` and `bell` are deliberately absent. `pepper` is irreducibly ambiguous — `black
     * pepper` and `green bell pepper` share a head — so blocking `bell` would break the correct
     * "green pepper" -> "green bell pepper" match while `red pepper` and `cayenne pepper` kept
     * matching anyway.
     */
    private val BLOCK_MODIFIERS = setOf(
        "peanut", "almond", "cashew", "hazelnut", "walnut", "pecan", "coconut", "soy", "soya",
        "oat", "hemp", "cocoa", "shea", "apple", "sour", "cream", "whipping", "whipped", "ice",
        "condensed", "evaporated", "spring", "clotted", "buttermilk", "tartar", "powdered",
        "malted"
    )

    /**
     * Quantity words seen glued directly onto a [BLOCK_MODIFIERS] entry elsewhere in the corpus
     * (`cuppowdered sugar`, `tbsppeanut butter`, `gcream cheese`) — see [isBlockedModifier].
     */
    private val QUANTITY_PREFIXES = listOf(
        "tablespoons", "tablespoon", "tbsp", "tbl", "teaspoons", "teaspoon", "tsp",
        "cups", "cup", "cans", "can", "grams", "gram", "gr", "g", "c", "ounces", "ounce", "oz",
        "pounds", "pound", "lbs", "lb", "packages", "package",
        "containers", "container", "jars", "jar", "tubs", "tub"
    )

    /**
     * Real English words that happen to match the quantity-prefix + [BLOCK_MODIFIERS] pattern but
     * are not fusion typos — see [isBlockedModifier].
     */
    private val PREFIX_FUSION_EXCEPTIONS = setOf("goat", "coat")

    private val IRREGULAR_PLURALS = mapOf(
        "leaves" to "leaf",
        "loaves" to "loaf",
        "halves" to "half",
        "calves" to "calf",
        "knives" to "knife",
        "geese" to "goose",
        "shelves" to "shelf"
    )

    /**
     * Singular words that look plural and must survive [singularize] intact. Kept short on
     * purpose: `canonical` is fully singularized (`caper`, `oat`, `chip`, `grit`, `leaf`), so
     * anything wrongly listed here stops matching the corpus entirely.
     */
    private val NEVER_STEM = setOf(
        "molasses", "asparagus", "hummus", "couscous", "watercress", "swiss", "anise", "haggis",
        "series"
    )
}
