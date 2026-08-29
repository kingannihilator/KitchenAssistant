# Recipe Database v1.4

Phase 1 (USDA source audit) result: 33 new recipes from a genuine
U.S. federal government publication.

## New source

**Dry Beans, Peas, Lentils: Modern Cookery** — USDA Leaflet No. 326
(September 1952), by Mary T. Swickard, Bureau of Human Nutrition and
Home Economics, Agricultural Research Administration, U.S. Department
of Agriculture. Full text: https://archive.org/details/drybeanspeaslent326swic

Rights basis: genuine U.S. federal government work, public domain
under 17 U.S.C. 105. Archive.org's own rights field states the
contributing institution believes the item is not in copyright. This
is a materially stronger rights position than the existing Project
Gutenberg sources (which rely on PD-in-USA-by-edition, not federal
authorship) and does not depend on the age of the work at all.

33 recipes were extracted: 17 main dishes, 8 soups, 6 salads, 2
sauces (some soup/sauce recipes build on a shared bean puree base,
noted in ingredient text as "bean puree" rather than re-deriving the
puree method, which was excluded as a preparation technique rather
than a servable dish — consistent with the v1.3 cleanup criteria).

Quality grading: 27 grade A, 0 grade B, 6 grade C. The 6 grade-C
recipes are genuinely short original recipes (e.g. "Bean-Tomato Bake"
is a real two-ingredient, one-step recipe in the source text) rather
than parsing failures — verified against the source text directly,
not inferred from heuristics alone.

## Important correction to the project's source-rights framing

The project doc's Section C groups Carver/Tuskegee bulletins under
"government-created work." Tuskegee Institute Experiment Station is
a private institution, not a federal agency, so 17 U.S.C. 105 does
not apply to it directly. Carver's bulletins are still very likely
public domain, but via a different route: publication date. Bulletin
No. 31 (1925) is PD by age (pre-1929), not by government authorship.
Later Carver bulletins (through 1943) would need individual
copyright-renewal verification before import. This USDA Leaflet
No. 326 source does not have that complication — it's authored by
actual USDA staff as part of official duties.

## Current numbers (v1.4)

- Recipes: 4,782 (was 4,749 in v1.3)
- Ingredient identities: 10,332
- Recipe-ingredient relationships: 41,375
- Cooking steps: 29,314
- Quality grades: A 2,976 / B 1,773 / C 33 / D 0
- Orphan ingredient identities (unreferenced by any recipe): 0
  (pruned in this pass — see below)

## Orphan ingredient prune

Inspected the 123 ingredient identities left unreferenced since v1.3.
They were not legitimate unused ingredients — they were parsing
artifacts from an earlier extraction pass: whole phrases stored as
if they were single ingredient names (e.g. "8 cloves", "garnish",
"salt and pepper to taste", "for 4 servings:"), plus a few stray
typos ("egg s", "onio n"). None were referenced by any recipe or any
ingredient alias, so they were removed outright rather than folded
into normalization. `ingredients_fts` rebuilt after the prune.

## Phase 2: USDA FoodData Central ingredient ontology (this pass)

Started by reviewing the top ~40 ingredients by frequency to prioritize
ontology entries. That review surfaced four real parsing bugs
predating this session, none previously caught:

1. **"tender" as a bogus ingredient** — 126 recipes (mostly Gutenberg-
   era) had a standalone ingredient row that was just the word
   "tender," lifted from cooking-instruction text like "...until
   tender" rather than any real ingredient. Removed. 2 legitimate
   uses ("tender young coconut") were repointed to a proper
   ingredient identity, not deleted.
2. **"jar" as a bogus ingredient** — 13 canning/preserving recipes had
   the same class of bug ("pour into jar" bleeding into the
   ingredient list). Removed.
3. **"ea" (each) mistaken for the ingredient itself** — 135 rows
   across the Wikibooks corpus had the unit abbreviation "ea." parsed
   as the ingredient name instead of the real ingredient sitting
   right after it (e.g. "4 ea. (200 g) eggs" was filed under the
   ingredient "ea" instead of "eggs"). Corrected by re-deriving the
   real ingredient name from each row's own raw_text; 0 failures.
4. **"freshly-ground black pepper" truncated to "freshly- black
   pepper"** — 136 rows, all genuinely black pepper, merged into the
   existing black pepper ingredient identity.
5. A further 60 rows had the same unit-token bug as #3 with other
   units (cm, dl, pt, qt, pkg, can, pinch, dash).

One recipe ("Baked Sweet Potatoes," Practical Vegetarian Cookery) had
zero real ingredients once its only ingredient row (the bogus
"tender") was removed — its actual ingredient (sweet potatoes) was
never captured by the original extraction. Rather than inventing an
ingredient list from the title, this recipe was removed, consistent
with the v1.3 precedent of rejecting unreliable extractions rather
than patching them.

137 recipes were regraded after these fixes (ingredient counts
changed), which is why quality-C count moved from 33 to 57 in this
pass — those are recipes that lost one non-food "ingredient" row and
are now correctly graded on their real, smaller ingredient list.

**Ontology built:** a new `ingredient_ontology` table (canonical_name,
category, fdc_id, fdc_description, fdc_data_type, fdc_confidence,
notes) with 33 rows covering the top clean ingredients by frequency,
plus 2 new entries in `ingredient_aliases` (most top-ingredient
aliases already existed from prior corpus work).

**FDC linkage is intentionally not populated in this pass.** The USDA
FDC API's public DEMO_KEY returned HTTP 429 (rate limited) during this
session, and search snippets don't reliably surface real FDC IDs.
Per the project doc's own rule ("Do NOT force uncertain mappings"),
no FDC IDs were fabricated to fill the field. To complete this:
register a free API key at
https://fdc.nal.usda.gov/api-key-signup (instant, no cost) and run a
lookup pass against `GET /v1/foods/search` for each `ingredient_ontology`
row, restricted to Foundation Foods and SR Legacy per Section 3D of
the project doc, filling `fdc_id`, `fdc_description`, `fdc_data_type`,
and `fdc_confidence`.

## Phase 3: improve the Wikibooks foundation (this pass)

Reviewed the `category` field across all 3,674 Wikibooks-derived
recipes for consistency, since it hadn't been audited since import.
Found and fixed two things:

1. **One non-recipe page imported as a recipe**: "Policy/Recipe
   template," a Wikibooks documentation page showing editors the
   recipe-page format, with literal placeholder content ("ingredient
   1," "ingredient 2," "Step one," "Step two," "Example servings").
   Removed, consistent with the v1.3 precedent for non-dish content.
   A broader scan for other Wikibooks meta-pages (policy, talk,
   template, user, sandbox, redirect, disambiguation namespaces)
   found nothing else — this was an isolated case.
2. **Redlink / URL-encoding contamination in `category`**: 32 recipes
   had a category value like "Flatbread&action=edit&redlink=1"
   instead of "Flatbread" (the raw MediaWiki URL fragment for a
   category page that didn't exist yet at scrape time got captured
   instead of the clean category name), and one had "Souffl%C3%A9"
   still URL-encoded instead of decoded to "Soufflé." All fixed.

**Known structural limitation, not fixed in this pass**: each
Wikibooks recipe stores only a single `category` value, but the
source wiki pages typically belong to multiple categories at once
(e.g. a dish can be both "Nigerian recipes" and "Soup recipes"
simultaneously on Wikibooks). This means cuisine/nationality
information is only present in this database when it happened to be
the one category value that was captured — it is not comprehensively
missing, but it is incomplete by construction. Recovering the full
category set would require re-scraping Wikibooks rather than
reprocessing the existing corpus, so this is flagged rather than
attempted here.

## Phase 4: cuisine gap analysis (this pass)

Before gaps could be identified honestly, cuisine coverage itself
needed fixing — it was too sparse and partly wrong to analyze as-is.

**Bug found and fixed**: 341 recipes had `cuisine` set to the exact
same string as the recipe's full `name` (e.g. "Abula (Nigerian Three
Stews)" had cuisine = "Abula (Nigerian Three Stews)" instead of
"Nigerian") — a copy-paste error from the original pipeline, not
useful for anything. Fixed 307 by extracting the real cuisine word
from the title using a fixed list of known nationality/cuisine
adjectives (same conservative, explicit-text-only method already
documented in `recipe_cuisine_inference_v1`: no guessing from
ingredients or cultural association, only literal words in the
title). The remaining 34 had no confident match and were cleared to
NULL rather than left holding the wrong value.

**Coverage expanded**: applying that same extraction method across
the other ~3,083 recipes with no cuisine signal at all (not just the
buggy ones) found 323 more recipes with an explicit nationality word
in the title that had simply never been checked. Spot-checked 20 at
random for accuracy — all correct (e.g. "Ikijumba (Rwandan Sweet
Potato Stew)" -> Rwandan, "Rosti (Swiss Potato Patties)" -> Swiss).

Recipes with *some* cuisine signal (across `recipes.cuisine`,
`recipe_cuisine_inference`, and `recipe_cuisine_inference_v1`
combined): **2,084 of 4,780** — up from 1,944 before this pass.

**Gap analysis against the doc's priority regions** (distinct recipe
counts, deduplicated across all cuisine-signal sources):

| Region | Count |
|---|---|
| African (sub-Saharan) | 415 |
| Indian / South Asian | 99 |
| Middle Eastern / North African | 72 |
| Southeast Asian | 67 |
| Latin American | 54 |
| Caribbean | 27 |
| East Asian (Japan/Korea) | 20 |
| Chinese | 17 |
| **Central Asian** | **0** |

Two genuine, actionable gaps, not just "smaller than the rest":

- **Central Asian is a true zero.** No Uzbek, Kazakh, Afghan, Tajik,
  or Mongolian recipes anywhere in the corpus, at all.
- **Chinese is thin (17) relative to its global significance and
  internal diversity.** The corpus has no distinct representation of
  Sichuan, Cantonese, Hunan, or other major regional Chinese
  traditions — everything is lumped as generic "Chinese," and there
  isn't much of it.

Per the doc's Section 8, sourcing recipes to fill these gaps needs
the same rights-verification rigor as Phase 1 (USDA audit) — this
pass identifies the gaps but does not yet source material to fill
them, since finding and vetting an appropriately-licensed Central
Asian or Chinese recipe source is its own bounded task.

## Phase 5, first half: near-duplicate detection (this pass)

Method: normalized every recipe title (lowercased, parenthetical
stripped, stopwords removed), grouped recipes into blocks by matching
normalized word-sets, then computed ingredient-set Jaccard similarity
within each block to distinguish "same dish name" from "actually the
same recipe."

- 249 candidate blocks examined, 576 recipes involved
- 165 blocks were same-source (higher duplicate risk), 84 spanned
  multiple sources (lower risk, consistent with the earlier exact-name
  check from v1.3/v1.4 which found cross-source name collisions are
  reliably coincidental, not duplicates)
- 12 pairs had ingredient-set similarity >= 0.75, checked individually
  against full ingredient lists and steps

**Result: exactly one genuine duplicate.** "Easy Nachos" (id 1012)
and "Nachos" (id 2108), both Wikibooks-sourced, had near-identical
ingredients and near-word-for-word steps. Kept 2108, removed 1012.

**Everything else checked was a false positive** — genuinely distinct
recipes that happen to share a title or most ingredients:

- Snickerdoodles I/II: different sugar/shortening/butter ratios and a
  different cinnamon-application technique (post-bake sprinkle vs.
  pre-bake roll-in-cinnamon-sugar)
- French Fries vs. French Fries (Belgian): same 3 base ingredients but
  a genuinely different multi-step double-fry method
- Drawn Butter (Wikibooks) vs. DRAWN BUTTER (La Cuisine Creole):
  different proportions, and the Gutenberg-era version adds parsley
- Oven Pancakes vs. Oven Pancakes (Vegan): an intentionally documented
  variant (soy milk substitution), not accidental duplication

**One data-quality issue surfaced, not a duplication issue**: the
three "Pousse Cafe No. 1/2/3" cocktail recipes are genuinely distinct
(different liqueurs and bitters per recipe) but their ingredient
*extraction* collapsed all three down to a single generic "brandy"
entry each — the real distinguishing ingredients are sitting in the
step text but were never captured as structured `recipe_ingredients`
rows. This makes them structurally look like near-duplicates even
though they aren't. Left as-is for this pass (fixing it would mean
re-parsing cocktail-recipe ingredient lists specifically, a bounded
follow-up task) but documented here so it isn't mistaken for a
resolved duplicate.

**Overall conclusion**: the corpus does not have a meaningful
near-duplicate problem. Of 576 recipes flagged as structurally
similar by title, only 2 (one pair) were true duplicates.

## Phase 5, second half: new-source vs. existing-corpus check (this pass)

The first half covered internal near-duplicates across the whole
corpus. This half specifically checks what Phase 5 is written to
check: new recipes against the existing corpus -- in this case, the
33 USDA Leaflet 326 recipes added in Phase 1 against everything
already in the database.

Method: title word-set overlap first (74 candidate matches found),
then ingredient-set Jaccard similarity on each candidate.

**Result: zero duplicates.** The highest ingredient overlap was
USDA's "Boston Baked Beans" against the existing Gutenberg-era
"BOSTON BAKED BEANS" at 0.50 Jaccard -- same traditional base
(beans, molasses, mustard, salt, water) but a different fat source
(salt pork vs. butter) and different proportions. That's well below
the 0.75 threshold that identified genuine duplicates in the first
half, and consistent with every other check across this project: a
shared, standardized dish name (Stuffed Peppers, Succotash, Black
Bean Soup) does not imply duplicated content when multiple
independent cookbooks each have their own version.

## Phase 6: final quality pass (this pass)

Full pre-release audit against the doc's own checklist.

**Structural checks — all clean:**
`PRAGMA integrity_check` ok, 0 foreign key violations, 0 orphaned
ingredient/recipe references, 0 invalid `role` values, 0
`role_confidence` outside [0,1], 0 recipes with zero ingredients or
zero steps, 0 non-sequential step numbering, 0 null/empty
instructions.

**Malformed ingredients — two more real bug classes found and fixed:**

1. **72 dash/slash-fragment canonical names** (e.g. "-sized onion,"
   "- black pepper," "/ beef") — hyphenated compound descriptors like
   "finely-minced," "medium-sized," "whole-wheat" got split at the
   internal hyphen during original extraction, leaving only the
   fragment after it. Same root cause as the "ea" bug from Phase 2,
   different manifestation. Fixed: 144 relationships corrected.
2. **267 long, unparsed canonical names** — full ingredient sentences
   stored as-is instead of being reduced to a clean food name.
   Breaking this down by what was actually found:
   - 240 were genuinely complex-but-legitimate ingredient
     descriptions (parenthetical asides, "OR" alternatives, brand
     references) — simplified to a clean canonical name via improved
     extraction, taking the first named alternative where multiple
     options were offered.
   - 6 were **pure narrative/citation text with no ingredient content
     at all** ("This cake has three layers that are prepared
     separately...", "This recipe comes from one of the most
     traditional Marseille restaurants...") that had been captured as
     if they were an ingredient row. Removed outright — same
     treatment as the "tender"/"jar" bug from Phase 2. None of the 6
     affected recipes were left with zero ingredients afterward.
   - 2 had a real ingredient buried in narrative text ("You need a
     large leg of mutton..." / "Water—the amount depends on...") —
     extracted the real ingredient (mutton / water) rather than
     deleting.
   - 7 were genuine multi-item "any of the following" lists bundled
     into a single row (optional seasoning/topping/accompaniment
     lists) — not deleted, since they carry real content, but given
     an honest generic label ("optional seasonings," "optional
     toppings," etc.) rather than a fabricated specific name, and
     marked `optional=1`.

All affected recipes' quality grades, match profiles, and usefulness
scores were recomputed after these fixes.

**One more instance of the Phase 3 redlink bug**: "Banana Pudding"
had `cuisine = "American dessert&action=edit&redlink=1"` — the same
contamination pattern fixed in Phase 3, but that pass only checked
the `category` field, not `cuisine`, so this one was missed. Fixed to
`cuisine = "American"`. A full-database scan for the `&action=edit`/
`redlink` pattern across all text columns came back clean afterward.

**Role classification audit**: 6,875 Seasoning / 30,158 Supportive /
4,190 Defining relationships. One recipe ("Homemade Baharat") is
100% Seasoning-classified — checked and confirmed correct, not a
bug, since it's genuinely a spice-blend recipe with no non-seasoning
ingredients.

**Provenance and licensing audit**: 0 recipes with missing or invalid
`source_id`/`license_id`, 0 sources missing a `source_rights` row.
5 licenses, 7 source_rights rows (one per source), all internally
consistent.

**Index rebuild**: `recipes_fts` and `ingredients_fts` rebuilt, full
`REINDEX`, `VACUUM`, and `PRAGMA optimize` run.

**Ingredient-matching query test** — using the project's own stated
example query:

- *"chicken, onion, bell pepper, rice, soy sauce"* — exact
  canonical-name matching returned sensible results (a cluster of
  fried-rice-family dishes), ranked correctly by the
  Defining/Supportive/Seasoning weighting.
- *"dry beans, ham, onion"* — exact matching returned poor results,
  because the USDA-sourced canonical names are specific ("dry black
  beans," "dry pea beans or great northern beans") rather than a
  generic "dry beans." Switching to substring matching correctly
  surfaced "Bean or Pea Soup" (60% ingredient coverage) as the top
  hit, confirming the underlying data is fine — the gap is in how a
  query would need to match against it.

**Two concrete recommendations for the Android app's matching logic**,
not database changes:

1. Don't rely on exact `canonical_name` equality alone. Route user
   input through `ingredient_aliases` / `ingredient_ontology` first,
   or use substring/fuzzy matching against `canonical_name`.
2. Substring matching over-counts near-duplicate ingredient variants
   — "red onion," "white onion," and "spring onion" would each
   separately match a user's single search term "onion," inflating
   that recipe's score. Deduplicate by user search term: each
   ingredient the user provides should count as satisfied at most
   once per recipe, not once per matching row.

## Final state (v1.4, all six phases complete)

- Recipes: 4,779
- Ingredient identities: 10,185
- Recipe-ingredient relationships: 41,223
- Cooking steps: 29,305
- Quality grades: A 2,975 / B 1,747 / C 57 / D 0
- Integrity: `PRAGMA integrity_check` ok, `PRAGMA quick_check` ok,
  0 foreign key violations

## Integrity

PRAGMA integrity_check: ok
PRAGMA foreign_key_check: 0 violations
FTS index rebuilt after import.

## Sources and licensing (full list)

- Wikibooks Cookbook — CC BY-SA 4.0 (3,674 recipes)
- Pennsylvania Dutch Cooking, PG #26558 — public domain in USA (165)
- Practical Vegetarian Cookery, PG #69812 — public domain in USA (395)
- La Cuisine Creole, PG #75027 — public domain in USA (453)
- Chinese Recipes, Nellie C. Wong, PG #76573 — public domain in USA (9)
- The Khaki Kook Book, Mary Kennedy Core, PG #25914 — public domain
  in USA (47)
- Dry Beans, Peas, Lentils: Modern Cookery, USDA Leaflet No. 326 —
  U.S. federal government work, public domain (33, new in v1.4)

## Recommended next steps

- Home and Garden Bulletin 177, "How to buy dry beans, peas, and
  lentils" (USDA, 1970) was identified as a second candidate federal
  source during the Phase 1 audit but not yet checked for actual
  recipe content (its title suggests a buying guide, possibly thin
  on recipes) — worth a quick look before committing effort to it
- Carver's pre-1929 Tuskegee bulletins remain a viable but
  legally distinct source (PD by age, not by government authorship);
  post-1928 bulletins need individual renewal checks before use
- Phase 3 (Wikibooks normalization) and Phase 4 (cuisine gap
  analysis) remain open from the original action plan
