# New corpus (`recipe_database.sqlite`) data quality: investigation notes

Findings from a direct measurement pass against the shipped `recipe_database.sqlite`
(19,566 recipes, odunola/foodie), done while planning how to port fridge search onto it. See
`INGREDIENT_MATCHING_CONCEPTS.md` for how this connects to the matching/ranking design, and
`ANDROID_HANDOFF.md` for the original schema handoff this supersedes in accuracy on this one point.

## The headline number

`ANDROID_HANDOFF.md` and `README.md` both claim ~115 "blob" rows (0.06%) and ~276 junk fragments
(0.15%) — together well under 0.2% of `ingredients` rows. Direct measurement found:

- **11.3% of distinct ingredient names** (3,157 of 28,015) have `normalized_name` longer than 40
  characters — a strong signal of un-stripped raw text, not a clean ingredient name.
- That's **2.7% of all `recipe_ingredients` rows** (5,036 of 184,854) — lower than the name-level
  rate because messy/one-off names are reused across fewer recipes than common clean ones.

Example: ingredient_id 14998 has `name = "1/2 cups grated Kefalotiri, Parmesan, or Pecorino cheese,
divided"` stored verbatim as the ingredient name — the entire raw ingredient line, not just the
ingredient itself.

This is why the very first "top heads by usage" pass (running `IngredientMatcher`'s head algorithm
against raw `normalized_name`) surfaced garbage like `as`, `more`, `inch`, `cube`, `removed`,
`halved`, `crushed` as top-40 "heads" — parser leftovers, not real ingredients.

## Why: three things ruled out, one thing confirmed

**Not a stale build.** Hypothesis: the shipped `.sqlite` predates a parser fix already sitting in
`build_recipe_db.py`. Tested by running the repo's own `reparse_ingredients.py` (which re-derives
`ingredients`/`recipe_ingredients` from `recipes.instructions_raw` already stored in the db — no
re-download needed) against a scratch copy. Result: the long-name rate moved from 11.3% to 11.5% —
essentially unchanged. Ruled out.

*(Caveat found while testing this: `reparse_ingredients.py` crashes on the 727 recipes whose
`instructions_raw` is NULL — the LLM-reconstructed recipes from the original build, which never had
raw text stored for them. If reparsing is ever done for real, it must be scoped to skip/preserve
those recipes' existing rows, not blow them away with the blanket `DELETE FROM recipe_ingredients`
the script currently does.)*

**Not primarily a mixed-number-quantity gap.** `ANDROID_HANDOFF.md` does mention this specific gap
("5 ¼ cups" sometimes only captures the integer part). A candidate regex fix (allow a whole number
before a fraction, e.g. "1 1/2") was tested against the actual offending names: it would only
recognize a leading quantity in **117 of 3,146** cases (3.7%). A minor contributor, not the main
cause.

**The actual cause: missing delimiters in inconsistent free-text source data.** Looking at the
remaining ~96% of offenders after the above test, the pattern is inconsistent/absent spacing between
the ingredient and a trailing clause — not a quantity-parsing problem at all:

```
medium-sized sardinesscaled & gutted, head & backbone removed, butterflied
peanut butteruse natural peanut butter, the kind that has oil on top
bottlethick white asparagus320 gr drained
peeled potatoeschopped into 2 inch chunks
```

This matches `build_recipe_db.py`'s own docstring, which already describes the source dataset as "a
single loosely-templated text field" parsed "conservatively" specifically so a botched parse doesn't
destroy data (the raw text stays recoverable from `instructions_raw`/`original_text`). In other
words: this level of noise is an accepted, structural property of a free-text source, not an
oversight — chasing it further with regex has sharply diminishing returns, as the two tests above
show.

## Response: filter, don't chase (consistent with existing precedent)

Per the data-quality mitigation pattern already established for the old corpus (see
`INGREDIENT_MATCHING_CONCEPTS.md`), the plan is a length/word-count-based blob filter at the
matching/scoring layer — cheap, reversible, and exactly what `ANDROID_HANDOFF.md` itself already
recommended ("a recipe with a blob ingredient row should never be shown as tier A/B"). Not a
parser rewrite.

Where this project's own strength changes the calculus: an LLM reading `original_text` *can* often
recover the true ingredient a regex can't (e.g. "sardinesscaled & gutted..." → sardine). That
recovery is scoped as a separate, targeted pass (see the taxonomy-construction plan) — reading only
the recipes that actually have a blob row, not a wholesale re-parse.

## Coverage math that makes hand-curation tractable

After filtering names >40 chars, the remaining 24,135 clean distinct names reduce to **2,153
distinct algorithmic heads** (via `IngredientMatcher`'s rules, Python-replicated for this
measurement). Usage across those heads is strongly Zipfian:

| Heads hand-categorized | % of clean `recipe_ingredients` rows covered |
|---|---|
| top 200 | 92.6% |
| top 500 | 97.9% |

This is why the taxonomy build targets the top ~200-500 heads by usage rather than all 2,153 — the
long tail is rare, low-value ingredients where the existing string-matching fallback is an
acceptable default.

## Addendum: a few junk heads survive the blob filter

`extract_ingredient_heads.py` (the formalized head-extraction/ranking script, see below) mirrors
`IngredientMatcher`'s real `STOPWORDS` list exactly, on purpose — so the computed "head" matches
what the app would actually compute. That fidelity surfaces a small, separate gap: `STOPWORDS`
doesn't include `as`/`more`/`if`, so trailing clauses like `"water, or as needed"` and
`"salt, or more"` leave `as`/`more`/`if` as the last surviving word, becoming the (wrong) head.
Scanned the top 200 heads by usage: only **3 heads** (`as`, `more`, `if`), all from this one
recurring phrase pattern, not widespread noise. Left as-is rather than patched here — changing
`IngredientMatcher.STOPWORDS` needs its own test-covered change (per
`INGREDIENT_MATCHING_CONCEPTS.md`'s note that the four word lists are guarded by
`IngredientMatcherTest`) and affects the old corpus's matching too, so it's out of scope for this
data-prep pass. Practical effect: a handful of rows whose real ingredient is `water`/`garlic`/`salt`
etc. get invisibly mis-headed and won't be picked up by that ingredient's category — a small,
known recall gap, not a correctness bug (nothing mismatches, some things just don't get grouped).
These heads are explicitly skipped (not assigned a category) during the categorization pass rather
than left ambiguously "not yet categorized."

## Exact-duplicate recipes (found post-launch, fixed by deletion)

Found while testing the app for real: the same recipe ("Natasha's Chicken Burgers") showed up
twice in one search's results, at two different `recipe_id`s. Checked whether this was isolated —
it wasn't. Comparing every column (not just title) between the two rows showed them byte-identical,
including `source_id` (both `1` — so it's not "two sources scraped the same page", the same
source's own data lists the recipe twice).

Measured across the whole corpus by grouping on byte-identical `instructions_raw` (sufficient on
its own: `recipe_ingredients`/`recipe_steps` are deterministically parsed *from*
`instructions_raw`, so identical source text guarantees identical derived rows too — confirmed for
the sample pair before trusting it as the general signal):

- **1,451 duplicate groups**, covering **4,927 of 19,566 recipes (25.2%)** — some groups had up to
  11 copies of the same recipe.
- **3,476 "extra" rows** once each group is collapsed to one canonical entry (the lowest
  `recipe_id`).

Unlike the blob-name and not-yet-categorized cases above (deliberately *not* deleted — there's
something to potentially recover or extend later), an exact duplicate has nothing to recover from;
it's pure redundancy. Deleted via `dedupe_exact_recipes.py` (backs nothing up itself — the usual
`db-backup/` snapshot was taken first, as with every schema-changing script in this directory).
Corpus after: **16,090 recipes**, 157,409 `recipe_ingredients` rows (was 184,854), 15,254
`recipe_steps` rows (was 16,409), zero orphaned child rows, zero remaining duplicate groups.
Verified fixed live in the app afterward (filtering results for "Natasha" now returns exactly one).

This also means `RecipeViewModel`'s `totalMatchCount` ("Top 500 of *N*") was quietly inflated by
duplicates before this fix — some of a given search's *N* was the same recipe counted more than
once.

## Tier distribution (post-dedup; see above for why the corpus shrank)

`recipe_ingredients.tier`, current corpus (16,090 recipes): DEFINING 19,448 / SEASONING 33,014 /
SUPPORTING 104,947.
