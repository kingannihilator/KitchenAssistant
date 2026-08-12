# Handoff: Porting recipe_database.sqlite to an Android app

This file is written for a fresh Claude session working in Android Studio,
with no memory of how this database was built. Read this before touching
the schema or writing search code — it explains what's already done, what
the data actually means, and what's still rough around the edges.

## What this is

A SQLite database of ~19,566 recipes (odunola/foodie dataset), built and
heavily cleaned up on desktop (Windows, Python) for one specific use case:
**"I have N ingredients, what can I make?"** — a fridge/pantry search app,
not a general recipe browser. Keep that use case in mind when making
design calls; it should guide tradeoffs (e.g. prioritize ingredient-based
querying speed over full-text recipe browsing polish).

Current file: `recipe_database.sqlite`, ~95 MB. `recipe_database.sqlite.bak`
and `recipe_database_pre_trim.sqlite.bak` are historical backups from the
desktop build process — irrelevant on Android, don't ship them.

## Schema

    source_datasets
    recipes (recipe_id, title, instructions_raw, ...)
      -> recipe_ingredients (recipe_id, ingredient_id, amount, unit, preparation, position, tier)
           -> ingredients (ingredient_id, name, normalized_name)
      -> recipe_steps (recipe_id, step_no, step_title, instruction)
      -> recipe_cuisines -> cuisines
    recipe_fts / ingredient_fts   (SQLite FTS5 virtual tables)

Row counts as of this handoff: 19,566 recipes, 28,015 distinct ingredients,
184,854 recipe_ingredients rows, 16,409 recipe_steps rows (the other ~3,157
recipes have no steps — either confirmed non-recipes with 0 ingredients, or
recipes whose directions text failed to parse; don't assume every recipe
has steps).

### `recipe_ingredients.tier` — the field the whole search design hinges on

Every ingredient row is pre-classified into one of three tiers **at build
time** (not something you need to recompute on-device):

- `DEFINING` — the ingredient (or part of its name) appears in the
  recipe's title, e.g. "garlic" in *Garlic Chicken*, "black pepper" in
  *Black Pepper Beef*. A title match overrides quantity/seasoning cues —
  e.g. black pepper stays DEFINING even at "1 tsp, or to taste" if the
  dish is literally named for it.
- `SEASONING` — a common seasoning/condiment/base word (salt, oil, stock,
  vinegar, extract, sugar, ...) used in a small quantity (tsp/pinch/dash/
  "to taste") and NOT title-defining.
- `SUPPORTING` — everything else: a real ingredient that's neither the
  dish's namesake nor a plain seasoning (onion, rice, cabbage, etc.).

This tier is what makes "search by 2-3 main ingredients, ignore
seasonings" possible without every recipe with a pinch of salt getting
disqualified. The classification logic (`classify_tier()` /
`title_phrases()` in `build_recipe_db.py`) does NOT need porting to
Kotlin — the tier is already a stored column, just query it.

### `amount` / `unit` — parsed but NOT normalized

Captured as separate fields wherever the source text stated a quantity
(`"250g"` -> amount=`250`, unit=`g`). Left `NULL` when no quantity was
stated — never guessed/fabricated.

**Important gap:** there are 201 distinct unit strings in the data, and
they are NOT normalized — `"cup"` and `"cups"` are different strings,
same for `"teaspoon"`/`"teaspoons"`, `"tablespoon"`/`"tablespoons"`/
`"tbsp"`, etc. There is no unit-conversion table anywhere in this
project. If you want to compare/sum quantities (e.g. "does the user's 5oz
of chicken cover what 2 different recipes need combined"), you'll need to
build a normalization + conversion layer from scratch — it doesn't exist
yet. Also known: mixed-number quantities like `"5 ¼ cups"` sometimes only
captured the integer part (`amount="5"`, unit left `NULL`, `"¼ cups"`
stayed stuck in the ingredient name) — a real residual parser gap, not
something you introduced.

### Data quality caveats (be aware, don't be surprised)

- **81 recipes have zero ingredients.** These were individually verified
  to genuinely not be recipes (news articles, product reviews, technique
  posts referencing a different recipe elsewhere) — not parsing failures.
  Don't try to "fix" these.
- **~115 rows are still "blob" rows** (several ingredients that didn't get
  split apart) and **~276 rows are tiny junk fragments** (stray leftover
  punctuation/numbers) — together well under 0.2% of rows. Not worth
  chasing further; a `LENGTH(ingredients.name) > 100` check flags most
  remaining blobs if you need to filter them out defensively.
- **721 recipes had their ingredients reconstructed via LLM semantic
  reading**, not the CSV parser, because they're free-form blog posts
  with no structured `Ingredients`/`Directions` template. Their
  `recipe_steps` entry is the recipe's full original text verbatim (not
  re-split into numbered steps, since the source prose has no reliable
  step boundaries). **70 of those 721** carry a specific known caveat
  (truncated source text, an ingredient/directions contradiction, an
  ambiguous quantity) — see `nlp_flagged_all.json` in this same directory
  for the full list with recipe_id + reason, if you want to spot-check or
  suppress any of them in the app.

## The search design to port (from `search_recipes.py`)

The Python reference implementation lives in `search_recipes.py` in this
directory — read it, it's the spec. Port the *query logic*, not a literal
line-by-line translation (see performance note below). Key functions and
what they do:

- `term_pattern(term)` — word-boundary regex match on a search phrase
  against `ingredients.normalized_name`, with an optional trailing `s`
  (e.g. "broccoli" matches "broccoli"/"broccolis" but correctly excludes
  "broccolini"). Port as a Kotlin `Regex`.
- `search(terms, mode, match)` — finds recipes containing all `terms`.
  `mode="defining_or_supporting"` (default) requires tier DEFINING or
  SUPPORTING; `mode="defining_only"` requires DEFINING. `match="loose"`
  allows other ingredients too; `match="exact"` requires the recipe's
  non-seasoning ingredients to be ONLY the searched terms (turns out to
  be too strict in practice — almost always returns 0 results for 2+
  ingredients, since real recipes need onion/rice/sauce too. Prefer loose
  + the ranking approach below.)
- `combo_report(terms, ...)` / the `--fridge` CLI mode — the actual "what
  can I cook" feature. Given N pantry ingredients, enumerates all `2^N-1`
  non-empty subsets (e.g. 3 ingredients -> 7 "dish options": all 3
  together, each pair, each one alone), and for each subset ranks
  matching recipes by how many OTHER non-seasoning ingredients they
  additionally require — fewer extra ingredients = better match. This
  is the UX to replicate: not a single search box, but "here's what you
  could make with subsets of what you have, ranked by how little else
  you'd need."
- **Feasibility tiers in the UI** (distinct from the ingredient `tier`
  column, don't confuse the two): A = 0 extra non-seasoning ingredients
  needed ("feasible now"), B = 1-2 extra (named, "secondary"), C = 3+
  extra ("needs a shopping list"). A recipe with a "blob" ingredient row
  should never be shown as tier A/B — its extras count is unreliable, cap
  it at a low-confidence tier instead of trusting a possibly-wrong "0
  extra" count.

## Concrete Android setup steps

1. **Room with a prepackaged database.** Put `recipe_database.sqlite` in
   `app/src/main/assets/database/`. Define `@Entity` classes mirroring the
   tables above (read-only — you're querying a prebuilt DB, not writing to
   it via Room's normal insert path). Build with:
   `Room.databaseBuilder(context, AppDatabase::class.java, "recipe_database.sqlite").createFromAsset("database/recipe_database.sqlite").build()`
2. **Don't port the "load everything into memory, filter in a loop"
   approach** that `search_recipes.py` uses — that's fine for a one-shot
   desktop script over 185K rows, not for a phone on every keystroke.
   Write proper indexed SQL queries instead: `WHERE tier IN (...) AND
   normalized_name LIKE ...`, or use the existing `ingredient_fts` FTS5
   table for fast substring/prefix matching. Indexes already exist on
   `recipe_ingredients(recipe_id)`, `(ingredient_id)`, and `(tier)`, and
   on `ingredients(normalized_name)` — confirmed present in the shipped
   file, so simple lookups on those columns are already fast; you likely
   only need new indexes if you add query patterns beyond these.
3. **Bundle SQLite explicitly for FTS5 consistency** — Android's system
   SQLite version varies by OS version/vendor. Use `requery:sqlite-android`
   or Room's bundled-SQLite support rather than relying on whatever
   version ships on the user's phone, so FTS5 behaves identically on
   every device.
4. **UI**: Jetpack Compose is the current recommended approach for new
   Android apps if there's no existing View-based codebase to match.

## Files in this directory worth reading before writing code

- `search_recipes.py` — the search logic spec (read this first)
- `build_recipe_db.py` — schema definition + tier classification logic
  (read for understanding the `tier` column; don't port the build/parse
  logic itself, it doesn't run on-device)
- `README.md` — project overview, schema diagram, data-quality summary
- `example_queries.sql` — plain SQL query examples against the schema
- `nlp_flagged_all.json` — the 70 recipes with known extraction caveats
