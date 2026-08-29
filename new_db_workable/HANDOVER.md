# Recipe Database Handover — v1.4

Written for a new Claude session working in Android Studio, picking this up
cold. Read this whole file before touching code — the licensing section
especially is not optional.

## TL;DR

- File to use: `recipes_open_v1_4.sqlite` (~22 MB)
- 4,779 recipes, fully structured (ingredients + steps + roles + quality
  grades), integrity-checked, ready to ship in a free Android app
- This REPLACES the app's existing recipe database — do not merge, just swap
- The database license is mixed (CC BY-SA 4.0 for most of it, public domain
  for the rest) — the app needs a small "Data Sources" / attribution screen
  before this can ship. See the Licensing section. This is the one thing that
  actually blocks release if skipped.
- Full change history is in the accompanying README and audit JSON — read
  those for provenance detail; this doc is about *integration*, not history.

## What this project is

An offline, ingredient-based recipe discovery database for a free Android app
(no ads, no fee). Core use case: user has some ingredients on hand, app tells
them what they can cook. The whole database was built around that goal, not
around maximizing recipe count — see `ingredient-matching algorithm` section
below for why the schema looks the way it does.

## Files you should have

- `recipes_open_v1_4.sqlite` — the database itself
- `recipes_open_v1_4_README.md` — full change log, v1.2 through v1.4, phase
  by phase, with every bug found and fixed documented
- `recipes_open_v1_4_audit.json` — machine-readable version of the same,
  useful if you want to script anything against the history
- `OPEN_RECIPE_DATABASE_PROJECT.txt` — the original project charter (goals,
  design principles, phase plan). Worth skimming for the *why* behind the
  schema, especially the ingredient role system and the "quality over
  quantity" principle.

If any of these are missing, ask before proceeding — don't guess at the
history.

## Current numbers

| Metric | Value |
|---|---|
| Recipes | 4,779 |
| Ingredient identities | 10,185 |
| Recipe-ingredient relationships | 41,223 |
| Cooking steps | 29,305 |
| Quality grade A / B / C / D | 2,975 / 1,747 / 57 / 0 |
| Integrity | `PRAGMA integrity_check` = ok, 0 FK violations |

Recipes by source:

| Source | Count | License |
|---|---|---|
| Wikibooks Cookbook | 3,672 | CC BY-SA 4.0 |
| Pennsylvania Dutch Cooking (PG #26558) | 170 | Public domain, USA |
| Practical Vegetarian Cookery (PG #69812) | 397 | Public domain, USA |
| La Cuisine Creole (PG #75027) | 451 | Public domain, USA |
| Chinese Recipes, Nellie C. Wong (PG #76573) | 9 | Public domain, USA |
| The Khaki Kook Book (PG #25914) | 47 | Public domain, USA |
| USDA Leaflet No. 326 (1952) | 33 | Public domain, US federal work |

## LICENSING — read this before shipping

This is the one part of the project that has legal consequences if
skipped. Full detail is in the `sources` / `source_rights` / `licenses`
tables inside the database itself (query them directly, don't take my word
for it), but the short version:

1. **Wikibooks Cookbook (3,672 recipes, ~77% of the database) is CC BY-SA
   4.0.** This requires attribution AND share-alike. Practically, this means:
   - The app needs a screen (Settings > About, or similar) crediting
     "Wikibooks Cookbook contributors" with a link to
     `https://en.wikibooks.org/wiki/Cookbook`.
   - If the recipe *data* is ever redistributed (e.g. exported, shared via
     an API), it must carry the same CC BY-SA 4.0 license. This does **not**
     apply to the app's own code — only to the recipe text/data itself.
2. **The five Project Gutenberg sources are public domain in the USA**, per
   Project Gutenberg's own determination. The `source_rights` table
   explicitly notes this is *not* a worldwide public-domain claim — if the
   app will be distributed outside the US, that's a nuance worth knowing
   about, not necessarily a blocker.
3. **USDA Leaflet No. 326 is a genuine US federal government work**, public
   domain under 17 U.S.C. § 105, no caveats. This is actually the cleanest
   rights position of any source in the database.
4. Do not add new recipe sources without checking `rights_basis` and
   `verification_status` in `source_rights` the same way — see the project
   charter's "never assume hosting implies rights" principle. It's there for
   a reason; it caught a real mistake during this project (Tuskegee/Carver
   material was almost mis-labeled as a "government work" when it isn't one).

**Minimum viable compliance**: one attribution screen listing all seven
sources with name + URL + license. Query `select name, url, attribution from
sources` and `select name, url from licenses` for the exact text to use —
don't paraphrase the license names.

## Schema reference

Core tables:

```sql
recipes(
  id, name, cuisine, category, servings, time_text, difficulty,
  source_id, source_url, license_id, description,
  prep_minutes_min/max, cook_minutes_min/max, total_minutes_min/max,
  cuisine_normalized
)
-- UNIQUE(name, source_id)

ingredients(id, canonical_name)
-- canonical_name is UNIQUE. This is the food identity, not raw text.

recipe_ingredients(
  id, recipe_id, ingredient_id, raw_text, quantity_text, unit, preparation,
  optional, role, role_reason, role_confidence,
  quantity_value, quantity_value_max, quantity_unit,
  secondary_quantity_value, secondary_unit,
  ingredient_text, parse_confidence, parse_status,
  normalized_ingredient, ingredient_match_name
)
-- role CHECK IN ('Defining','Supportive','Seasoning')
-- role_confidence CHECK BETWEEN 0 AND 1
-- raw_text is the original ingredient line as written (always trust this
--   for display). quantity_value/unit are parsed-out numerics (nullable --
--   about 42% of ingredients are intentionally unquantified, e.g. "salt
--   and pepper to taste", NOT a data quality problem).

recipe_steps(id, recipe_id, step_number, instruction)
-- step_number is sequential 1..N per recipe, verified gapless.
```

Provenance chain (every recipe traces back through this):

```sql
recipes.source_id -> sources(id, name, url, attribution, notes)
recipes.license_id -> licenses(id, name, spdx_id, url, requirements)
sources.id -> source_rights(source_id, rights_basis, jurisdiction,
                             verification_status, verification_note, verified_date)
```

Quality / matching support tables:

```sql
recipe_quality(recipe_id, ingredient_count, step_count, defining_count,
                supportive_count, seasoning_count, quantified_count,
                unquantified_count, low_parse_count, quality_grade, quality_flags)
-- quality_grade: A (clean), B (mostly unquantified but fine), C (thin --
--   real recipe but few ingredients/steps), D (unusable -- currently 0 rows,
--   these get rejected rather than shipped)

recipe_usefulness(recipe_id, score, tier, reason)
recipe_match_profile(recipe_id, defining_count, supportive_count,
                      seasoning_count, quantified_count, ingredient_count,
                      step_count, match_weight)
-- match_weight = defining*3 + supportive*1.5 + seasoning*0.5
-- precomputed so the app doesn't need to recompute this per query
```

Normalization layer:

```sql
ingredient_aliases(alias PRIMARY KEY, canonical_name, reason)
-- e.g. 'onions' -> 'onion', 'freshly-ground black pepper' -> 'black pepper'
-- Does NOT touch recipe_ingredients -- it's a lookup layer for query time.

ingredient_ontology(canonical_name PRIMARY KEY, category, fdc_id,
                     fdc_description, fdc_data_type, fdc_confidence, notes)
-- 33 rows covering the top ingredients by frequency, with a rough category
-- (e.g. "spice", "dairy/fat"). fdc_id/fdc_description/fdc_confidence are
-- currently NULL for all rows -- USDA FoodData Central linkage was never
-- completed (their API demo key got rate-limited mid-project). See "Open
-- items" below if you want to finish this.
```

Cuisine inference (explicit-text-only, conservative — no cultural guessing):

```sql
recipe_cuisine_inference_v1(recipe_id, cuisine, confidence, reason, evidence)
-- reason is always "explicit cuisine/geographic label in recipe title"
-- confidence 0.95-0.97. Do not add inferred cuisines based on ingredients
-- or vibes -- that was a deliberate project decision, not an oversight.
```

Full-text search: `recipes_fts` (name, description, category, cuisine) and
`ingredients_fts` (canonical_name), both FTS5, both freshly rebuilt as of
v1.4. Use these for any text-search feature rather than `LIKE`.

Useful pre-built views: `recipe_discovery` (recipe + cuisine + quality +
usefulness + match weight, one row per recipe, good starting point for a
browse screen), `recipe_match_core_v1` (per-recipe role counts + a
pre-weighted `core_weight`), `recipe_match_summary`.

## Ingredient-matching algorithm — read before building the "what can I
cook" feature

This was tested end-to-end in the final QA pass and two real findings came
out of it. Both are about how the *app* should query, not about the data:

**Finding 1 — don't match on exact `canonical_name` equality alone.**
A query for "dry beans" will match nothing under exact equality, because the
canonical names are specific ("dry black beans", "dry pea beans or great
northern beans"). Either:
- Resolve user input through `ingredient_aliases` first, then match on
  `canonical_name`, or
- Use substring matching (`canonical_name LIKE '%' || :term || '%'`) against
  `recipe_ingredients.normalized_ingredient` or `ingredients.canonical_name`

**Finding 2 — deduplicate substring matches by search term.** If you use
substring matching, a recipe with "red onion", "white onion", and "spring
onion" will match the user's single search term "onion" three times,
inflating its score relative to a recipe with one plain "onion". Count each
user-provided ingredient as satisfied **at most once per recipe**, not once
per matching row.

**Scoring**: use `role` to weight matches — Defining ingredients should
matter far more than Seasoning ones. `recipe_match_profile.match_weight`
already implements the project's `defining*3 + supportive*1.5 +
seasoning*0.5` formula for the recipe's *own* ingredient list; for a
user-ingredients-matched query you'll want the analogous weighted sum over
only the matched subset, then probably combine with coverage (matched /
total ingredient_count) so a recipe where the user has everything ranks
above one where they have 3 of 20 ingredients even if the raw weighted score
is similar. There's a worked example of this exact query pattern in the
audit JSON under `v1_4_phase6_final_quality_pass.ingredient_matching_query_test`
if you want a starting point to adapt.

## Replacing the existing database

You don't have visibility into the existing app's current schema/DAO layer
from this handover — the first thing to do in the Android Studio session is
find and read the existing Room entities / DAOs / migration files before
deciding how to swap this in. General guidance:

1. **If the app uses Room with a bundled prebuilt database**
   (`Room.databaseBuilder(...).createFromAsset(...)`): drop
   `recipes_open_v1_4.sqlite` into `app/src/main/assets/`, update the
   filename reference, bump the Room database version, and write Room
   `@Entity` classes that match the schema above (or a subset — you don't
   need to expose every audit/provenance table as a Room entity, just the
   ones the app actually queries: `recipes`, `recipe_ingredients`,
   `recipe_steps`, `ingredients`, probably `recipe_match_profile` and
   `recipe_quality`, and `sources`/`licenses` for the attribution screen).
2. **If the app currently generates/seeds its database from code** rather
   than shipping a prebuilt file: switching to
   `createFromAsset`/`createFromFile` is almost certainly the right move
   given the size and structure of this data — don't try to replicate 4,779
   recipes via Room migrations or INSERT statements in Kotlin.
3. **Either way, this is a destructive replace, not a merge.** If the
   existing app database has any user data (favorites, custom recipes,
   ratings) that needs to survive, that lives in separate tables that should
   NOT be touched by this swap — check for that before dropping the old
   file. If user data exists in tables mixed into the same database file as
   the old recipe data, you'll need a proper Room migration that copies user
   data out, drops old recipe tables, and imports the new schema, rather
   than a raw file swap.
4. **Room version bump + destructive migration fallback**: at minimum,
   increment the Room database version and either provide a real
   `Migration` or, if acceptable for a pre-release beta, use
   `.fallbackToDestructiveMigration()` — check what the app currently does
   before choosing.
5. Don't forget indexes are already built into the .sqlite file (see
   `CREATE INDEX` statements above) — Room's `@Entity` annotations with
   `@Index` should mirror these rather than dropping them, or you'll get
   correct-but-slow queries at 4.7k+ recipes.

## Open items (known, not urgent, don't rediscover these)

- **USDA FoodData Central linkage incomplete.** `ingredient_ontology` has 33
  rows with `fdc_id` = NULL for all of them. The public API's DEMO_KEY got
  rate-limited during this project. To finish: register a free key at
  `https://fdc.nal.usda.gov/api-key-signup`, query `GET /v1/foods/search`
  for each ontology row restricted to Foundation Foods / SR Legacy data
  types, and backfill `fdc_id`/`fdc_description`/`fdc_data_type`/
  `fdc_confidence`. Not needed for the ingredient-matching feature to work;
  only needed if/when a nutrition-info feature gets built.
- **Cuisine gaps, not yet sourced**: zero Central Asian recipes (Uzbek,
  Kazakh, Afghan, etc.) anywhere in the database. Chinese cuisine is thin
  (17 recipes) relative to its size and internal diversity. Filling either
  needs a new, rights-vetted source — same process as the USDA source was
  added, documented in the README's Phase 1 section as a template.
  Wikibooks-only single-category limitation: each Wikibooks-derived recipe
  stores only one `category` value even though source pages usually belong
  to several simultaneously, so cuisine/category data is incomplete by
  construction for that source, not by bug.
- **Three "Pousse Cafe" cocktail recipes** (search `WHERE name LIKE 'POUSSE
  CAFE%'`) have real, distinct recipes but their ingredient *extraction*
  collapsed all three down to a single generic "brandy" entry each — the
  real distinguishing liqueurs and bitters are in the step text, not
  structured. Low priority (3 recipes) but a good example if you're
  building/testing an ingredient re-extraction pass.
- **The `source_candidates` table exists but is empty** (0 rows) — it looks
  like it was scaffolded for tracking candidate sources under evaluation but
  never populated. Either use it going forward or ignore it.

## If you need to regenerate the audit / re-verify integrity

```python
import sqlite3
con = sqlite3.connect('recipes_open_v1_4.sqlite')
cur = con.cursor()
print(cur.execute("PRAGMA integrity_check").fetchone())
print(len(cur.execute("PRAGMA foreign_key_check").fetchall()), "FK violations")
```

Should print `('ok',)` and `0`. If it doesn't, something broke during the
Android integration (e.g. a bad migration wrote to the file) — stop and
investigate before shipping, don't proceed.

## Don't do this

- Don't merge in new recipes without checking `source_rights` first —
  see the Licensing section.
- Don't infer cuisine from ingredients or dish type — the project
  deliberately only infers from explicit text, and diverging from that
  quietly undoes real work (see `recipe_cuisine_inference_v1.reason`).
- Don't "clean up" recipes with `quality_grade = 'B'` by force-adding
  quantities — B means "most ingredients are intentionally unquantified"
  (e.g. "salt and pepper to taste"), which is a legitimate recipe style, not
  a defect.
- Don't treat `recipe_ingredients.raw_text` as safe to regenerate/discard —
  it's the source of truth for what the recipe actually says; `canonical_name`
  and `normalized_ingredient` are derived conveniences for matching, not
  replacements.
