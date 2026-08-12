# Ingredient matching & ranking: concept inventory

This is a reference, not a spec — it catalogues the *ideas* behind ingredient matching and recipe
ranking in this project: what's already implemented (in `IngredientMatcher.kt`, `CanonicalIndex.kt`,
`RecipeViewModel.kt`, all under `app/src/main/java/com/example/kitchenassistant/`), and what's been
identified as a gap but not yet built. Written so neither has to be re-derived from scratch in a
future session. See `ANDROID_HANDOFF.md` in this directory for the new corpus's own schema/handoff
notes, and `CLAUDE.md` at the repo root for the day-to-day architecture summary.

## Implemented: the old build's matching scheme (`IngredientMatcher.kt`)

**Head-anchored identity.** English noun compounds are head-final — "chicken broth" is a kind of
broth, not a kind of chicken. Every ingredient name reduces to a *head* (its last content word,
with some stripping — see below) plus the full set of content words. Two names match only if their
heads are equal; this is what stops fridge "chicken" from matching "chicken broth," "chicken
bouillon," or "cream chicken soup."

**Part-word stripping.** A cut or part of a thing is still that thing, so trailing words like
`breast`, `thigh`, `half`, `clove`, `fillet`, `loin` are skipped when finding the head — "chicken
breast half" resolves to head `chicken`. The list (`PART_WORDS`) is deliberately narrow and
corpus-tuned: `white`/`yolk` are included because leaving them out silently drops ~21,900 rows of
genuine "egg white"/"egg yolk" matches, but `steak` was tried and *rejected* — adding it turned
"round steak" into head `round`, a net loss. This is the direct precedent for why cross-name
grouping (see Gap section below) needs a different mechanism than growing this list further.

**Stopwords.** Preparation participles, sizes, and quality adjectives (`chopped`, `fresh`, `large`,
`organic`, ...) are dropped from both sides — they describe handling, not identity. Deliberately
*not* stopwords, even though they look like some: `flavoring`/`flavour` and `substitute` change
what a thing *is* ("butter flavoring" and "egg substitute" are correctly rejected as matches for
butter/egg because these words become the head).

**Block-modifiers.** Some compounds are a different substance, not a variety of their head:
`peanut butter` is not butter, `sour cream` is not cream. When a word in `BLOCK_MODIFIERS` appears
only on the more-specific side of a comparison, the match is rejected even though the heads agree.

**Asymmetric truncation.** Fridge names (OpenFoodFacts taxonomy, `ingredients.db`) run long and
compound clauses with "and"/"with" onto an unrelated second ingredient (e.g. "organic cocoa mass
*and* organic cocoa butter") — so the fridge side truncates at the first connective. Recipe/corpus
names never truncate — the only connective-containing canonicals are things like "cream of tartar,"
where truncating would let fridge "cream" wrongly swallow every cream-of-tartar row. Recipe-side
connectives are dropped as ordinary stopwords instead, which has the side benefit of cleaning up
corpus junk like "thyme or".

**Conservative singularization.** Timid on purpose — over-stemming silently breaks matches that
used to work, so anything ambiguous (`molasses`, `hummus`, `couscous`, `asparagus`, ...) is left
alone via an explicit `NEVER_STEM` list, plus a short hand-written `IRREGULAR_PLURALS` map for
`leaves→leaf` etc. that the mechanical suffix rules can't reach.

**Why all four word lists are one unit.** `PART_WORDS`, `STOPWORDS`, `BLOCK_MODIFIERS`, and
`FRIDGE_CUT`/`RECIPE_ONLY_STOPWORDS` are tuned together against the real (old) corpus and guarded
by `IngredientMatcherTest`. This is why the concept doc exists separately from the code: adding an
entry to any one list is a data decision, not a code change, and needs the same kind of evidence
gathering this file records.

## Implemented: fast matching at scale (`CanonicalIndex.kt`)

Testing every fridge item against all ~93k distinct canonical ingredient names in the old corpus
would be wasteful. Since matching requires equal heads, only a fridge item's own head-bucket can
possibly contain a hit — so the index buckets every canonical by its computed head once (built in
~0.3s, held for the process lifetime), turning each fridge-item lookup into a bucket scan instead of
a full scan. Same idea will apply to any equivalent index built for the new corpus.

## Implemented: ranking (`RecipeViewModel.kt`, bottom of file)

**Smoothed vs. unsmoothed ratio.** A bare `matched / total` puts every trivial one-ingredient
recipe at a perfect 1.0, ahead of a genuine 9-of-10 match. `ratioScore` adds a prior (`+2`) to the
denominator for ranking, measured to cut trivial recipes in the first 200 results from 142 to 25 for
a 3-item fridge. But the *card color* / tier bucket (`matchTier`, and `RecipeScreen`'s independent
`matchRatio`) deliberately uses the **unsmoothed** ratio, so a real 3/3 always shows green — the
smoothing only breaks ties within a tier, it never overrides which tier a recipe is in.

**Favorites pinned first, prioritized ingredients boost (not filter).** Starring a fridge ingredient
never removes recipes that don't use it — it only pushes up recipes that do
(`thenByDescending { prioritizedCount }`). This is a deliberate boost/not-filter distinction worth
preserving in any new ranking path.

**`recipeOrder` and `matchOrder` must stay in sync.** The `MAX_RESULTS` cut happens on raw
`RecipeMatch` scores (`matchOrder`) *before* favorites are known (favorites require the hydrated
`Recipe`), and the final display sort (`recipeOrder`) happens after. If the two orderings disagree,
a low-ranked-but-favorited recipe could be cut before the favorites-rescue step even sees it. Any
new-corpus scoring path needs the same two-stage discipline.

## Implemented: data-quality mitigation pattern

Both corpora have known parser gaps. The established pattern here is **never chase 100% clean
upstream parsing — defend at the matching/scoring layer instead**, via a named, reversible,
app-side switch:
- Old corpus: `loadUnparseableRecipeIds` (`parse_ok = 0`) and `loadUnderparsedRecipeIds` (a
  heuristic for collapsed multi-ingredient lines), gated by `SUPPRESS_UNDERPARSED_RECIPES`.
- New corpus: the equivalent is a **blob-row filter** (see Gap section) — a different bug in
  different code, so it gets its own switch, not a reuse of the old one.

This precedent is why, when the new corpus turned out to have far messier ingredient names than its
own handoff doc claimed (see below), the response was to filter rather than to keep patching the
regex parser.

## Gap, identified this conversation: cross-name category grouping

`IngredientMatcher`'s head-matching only unifies names that share a trailing word. It cannot know
that `ribeye`, `chuck`, `T-bone steak`, and `ground beef` are all beef — they have unrelated heads
(`ribeye`, `chuck`, `steak`, `beef`), and no amount of stopword/part-word tuning can merge them,
because there's no shared string to anchor on. (`PART_WORDS`'s own docstring already documents that
adding `steak` was tried and reverted for making other matches worse — confirming this needs a
different kind of fix, not a bigger list.)

**Also identified**: `tender` is not in `PART_WORDS` (only `tenderloin` is), so "chicken tender
halves" currently resolves to head `tender`, not `chicken` — a real, narrow gap in the existing
list, separate from the cross-name issue above.

**The fix in progress**: a hand-curated, tree-structured category taxonomy built directly against
the new corpus's own ingredient vocabulary (e.g. `Beef/Steak/Ribeye`, `Chicken/Breast`), assigned by
an LLM reading batches of ingredient names (see `NEW_CORPUS_DATA_QUALITY.md` for the construction
process and coverage numbers). This is a genuinely different data structure from
`IngredientMatcher`'s lists — a lookup table keyed by meaning, not a parsing rule — and is scoped to
the new corpus only; the old corpus keeps using `IngredientMatcher` unchanged behind a switch (see
`CLAUDE.md` once that Kotlin work lands).

`ingredients.db` (OpenFoodFacts taxonomy, used for fridge-entry autocomplete) was checked as a
possible source of ready-made hierarchy and ruled out — confirmed via direct schema inspection to be
stripped down to just `id, name_en` in this bundled build, with no parent/category column retained.

## Gap, identified this conversation: `DEFINING`/`SEASONING`/`SUPPORTING` tiering

The new corpus tags every `recipe_ingredients` row with a tier the old corpus has no equivalent of.
The intended use (per `search_recipes.py`'s `defining_or_supporting` default mode): `SEASONING` rows
should be excluded from *both* the numerator and denominator when computing a recipe's match ratio —
a recipe needing salt shouldn't be penalized for a fridge that doesn't have salt, and shouldn't get
credit for one that does. This folds into the *existing* `ratioScore`/`matchTier`/`recipeOrder`
machinery as better-quality input counts, not a parallel ranking system — those functions don't need
to change, only what feeds them.

## New-corpus data quality: see `NEW_CORPUS_DATA_QUALITY.md`

The investigation into the new corpus's ingredient-name quality (11.3% of distinct names are
un-stripped raw text, not the ~0.06% the handoff doc claims; why regex fixes don't help; the
Zipfian usage concentration that makes hand-categorization tractable) is written up separately
since it's long enough to stand on its own and is more "investigation notes" than "concept."
