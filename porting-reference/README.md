# Offline Recipe SQLite Builder

This package builds a self-contained SQLite recipe database from the
Global Food Recipes Dataset (`odunola/foodie`).

Why this source:
- 19,566 rows
- English
- ingredients and directions
- broad international coverage
- Apache-2.0 is reported by the dataset repository
- small enough for a normal desktop/local database

The resulting SQLite database is normalized for ingredient searching:
recipes -> recipe_ingredients -> ingredients

It also creates SQLite FTS5 indexes for fast text searching.

## Build

Requires Python 3.9+ and internet access for the one-time source download:

    python build_recipe_db.py

After that, `recipe_database.sqlite` is completely self-contained and can
be copied to another computer. No cloud service is needed to query it.

If you only need to re-run ingredient parsing (e.g. after editing the
parser in `build_recipe_db.py`) without re-downloading the source CSV,
use:

    python reparse_ingredients.py

This wipes and rebuilds `ingredients`/`recipe_ingredients` from the raw
text already stored in `recipes.instructions_raw`.

## Schema

    source_datasets
    recipes
      -> recipe_ingredients (amount, unit, preparation, tier)
           -> ingredients
      -> recipe_steps
      -> recipe_cuisines -> cuisines
    recipe_fts / ingredient_fts   (FTS5 full-text search)

`recipe_ingredients` carries three fields worth knowing about:
- `amount` / `unit` — parsed as separate fields wherever the source text
  states a quantity (e.g. `"250g"` -> amount=`250`, unit=`g`), so they can
  be used for quantity-aware matching later. Left `NULL` when no quantity
  is stated in the source — never guessed.
- `tier` — classifies each ingredient's role in its recipe:
  - `DEFINING` — the ingredient (or part of it) appears in the recipe's
    title, e.g. "garlic" in *Garlic Chicken*, "black pepper" in *Black
    Pepper Beef* — a title match overrides quantity/seasoning cues.
  - `SEASONING` — a common seasoning/condiment/base word (salt, oil,
    stock, vinegar, extract, ...) used in a small quantity (tsp/pinch/
    dash/"to taste") and not title-defining.
  - `SUPPORTING` — everything else: a real ingredient that's neither the
    dish's namesake nor a plain seasoning (e.g. onion, rice, cabbage).

  Tiering logic lives in `classify_tier()` / `title_phrases()` in
  `build_recipe_db.py` and is applied consistently whether a row was
  parsed automatically from the CSV or reconstructed by hand (see below).

## Data quality notes

The source dataset stores each recipe as a single loosely-templated text
field (title + description + "Ingredients" + directions all concatenated).
`build_recipe_db.py` parses this conservatively and preserves the full
original text in `recipes.instructions_raw`, so parsing errors never
destroy data — a botched parse can always be re-derived or hand-fixed
from the raw text.

Known residual imperfections, roughly in order of how much they matter:
- **~115 rows** (0.06%) are still "blob" rows where several ingredients
  didn't get split apart (usually a nonstandard section-header format).
- **~276 rows** (0.15%) are tiny junk fragments (stray numbers/punctuation
  left over from an odd source format); many are false positives of a
  crude length-based detector, not actually bad data.
- **81 recipes** have zero ingredients — these were individually verified
  to not be actual recipes (news articles, trade-show write-ups, product
  reviews, technique/tips posts that reference a *different* recipe
  elsewhere) rather than parsing failures.
- **721 recipes** that originally had zero ingredients (free-form blog
  posts with no `Ingredients`/`Directions` template for regex to key off
  of) were recovered via manual/LLM semantic reading rather than regex,
  since understanding meaning — not just pattern-matching structure — was
  required. Their `recipe_steps` entry is the verbatim original text
  rather than a re-split numbered list, since the source prose has no
  reliable step boundaries to split on.
- **70 of those 721** carry a known caveat (truncated source text, an
  ingredient-list/directions contradiction, an ambiguous quantity, etc.)
  — see `nlp_flagged_all.json` for the full list with reasons if you want
  to spot-check or hand-correct any of them.

## Search tool

`search_recipes.py` searches by a short list of ingredients using the
`tier` field, e.g. "what can I make with what's in my fridge":

    # Recipes containing all listed ingredients as real ingredients
    # (DEFINING or SUPPORTING tier, not just a seasoning mention)
    python search_recipes.py "chicken breast" "broccoli"

    # Stricter: ingredients must be what the dish is named/built around
    python search_recipes.py "chicken breast" "broccoli" --mode defining_only

    # Recipe's non-seasoning ingredients are ONLY the searched terms
    python search_recipes.py "chicken breast" "broccoli" --match exact

    # Pantry mode: given N ingredients, list every non-empty subset
    # (2**N-1 "dish options") from combining all of them down to each
    # ingredient standalone, ranked by fewest other ingredients needed
    # and labeled by feasibility tier (A: nothing else needed, B: needs
    # a couple more items, C: needs a shopping list, ?: unparsed/unsure)
    python search_recipes.py "chicken breast" "broccoli" "carrot" --fridge

See the module docstring in `search_recipes.py` for the full option list.

## Files

- `build_recipe_db.py` — downloads the source CSV and builds the database
  from scratch (schema, parsing, tier classification, FTS indexes)
- `reparse_ingredients.py` — re-parses ingredients from already-stored raw
  text, without re-downloading
- `search_recipes.py` — ingredient/fridge search CLI (see above)
- `example_queries.sql` — plain SQL examples for direct querying
- `nlp_flagged_all.json` — the 70 semantically-recovered recipes worth a
  second look, with a reason for each
- `recipe_database.sqlite` — the built database (created by the builder)
- `recipe_database.sqlite.bak` — a backup taken before the parser rewrite;
  reflects the *old*, less-clean parsing, kept only as a safety net
