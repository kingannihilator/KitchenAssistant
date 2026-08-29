# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

All commands use Gradle wrapper from the project root:

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew test --tests "com.pancakeworks.fridgegrub.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean

# Lint
./gradlew lint
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Project Vision

Kitchen Assistant helps users manage their fridge ingredients and get personalized recipe recommendations.

### Core Features

**Ingredient Management**
- Users add ingredients (name, quantity/unit, optional expiration date) via autocomplete against a bundled offline ingredient database
- Ingredients can be starred as "prioritized", which boosts recipes using them up the ranking (a boost, not a filter — recipes using none are still listed)
- Quantities support increment/decrement, direct numeric entry, and deletion (with a confirmation dialog above a quantity threshold)

**Recipe Recommendations**
- Recipes are matched against the current fridge contents from a bundled offline recipe database (no network calls)
- Results are scored by percentage of recipe ingredients present in the fridge and sorted best-match-first, with favorites pinned to the top
- Recipe cards are color-coded by match tier: 100% match, ≥75% match, and below 75%
- Recipe detail view shows full ingredient list (checkmarked against the fridge) and directions
- Favorites are persisted via SharedPreferences
- The fridge ingredient list is persisted via SharedPreferences too (`data/FridgeRepository.kt`, JSON-encoded) — survives app close/process death, cleared only on uninstall; no account-level sync yet

### Current State

Working single-activity app with two main screens (ingredient list, recipe list) plus a recipe detail screen, navigated via a simple sealed-class `Screen` state machine in `MainActivity`. No network layer — ingredient autocomplete runs against `ingredients.db` (bundled in `app/src/main/assets/`, copied to app-internal storage on first use), and recipe search runs against the bundled recipe corpus (see "The recipe corpus" below), opened in place via Room (`database/recipe_database.sqlite`). The ingredient list and favorites are both persisted via SharedPreferences (`data/FridgeRepository.kt`, `data/FavoritesRepository.kt`).

The exclude/negative-ingredient feature (`isNegative` on `Ingredient`) exists in the data model but its UI and toggle logic are commented out, not deleted — see `viewmodel/IngredientViewModel.kt` and `ui/IngredientScreen.kt` for the intentionally-disabled code paths.

## Tech Stack

- **Language**: Kotlin 2.2.10
- **UI**: Jetpack Compose with Material3 (BOM 2024.09.00)
- **Min SDK**: 24 (Android 7.0), **Target/Compile SDK**: 36
- **AGP**: 9.3.1
- **Persistence**: `ingredients.db` via raw `android.database.sqlite` (see `data/BundledDatabase.kt`), `recipe_database.sqlite` via Room 2.8.4 (see `data/NewRecipeDatabase.kt`) — plus `SharedPreferences` for favorites and the fridge ingredient list (JSON via `org.json`, no external serialization dependency)
- **Build system**: Gradle with Kotlin DSL (`.gradle.kts`) and version catalog (`gradle/libs.versions.toml`); Room's annotation processing runs through KSP (`com.google.devtools.ksp`, pinned to `2.2.10-2.0.2` to match the project's Kotlin version)

No networking library (no Retrofit/OkHttp) is currently in the dependency graph — all data is local.

## Architecture

Single `app` module. Source root: `app/src/main/java/com/example/kitchenassistant/`

```
├── MainActivity.kt   Screen navigation (sealed class Screen: Ingredients / Recipes / RecipeDetail)
├── model/            Ingredient.kt, Recipe.kt, DetailIngredient.kt — core data classes
├── data/             FavoritesRepository.kt (SharedPreferences-backed favorite IDs), FridgeRepository.kt (SharedPreferences-backed fridge contents, JSON), IngredientMatcher.kt (the matching rule), NewRecipeEntities.kt/NewRecipeDao.kt/NewRecipeDatabase.kt (Room layer for the recipe corpus), NewIngredientIndex.kt (in-memory index, category-aware)
├── viewmodel/        IngredientViewModel.kt, RecipeViewModel.kt — StateFlow-based state, both AndroidViewModel (need Application/assets access); RecipeRanking.kt — pure scoring/ranking math, split out for testability (see below)
├── ui/               IngredientScreen.kt, RecipeScreen.kt, RecipeDetailScreen.kt, Scrollbar.kt
└── ui/theme/         KitchenAssistantTheme — dynamic color (Android 12+), dark/light modes; Color.kt has match-tier colors (FullMatch*, PartialMatch*, FridgeMatchGreen/FridgeMissingRed)
```

**Data flow:** `MainActivity` owns a single `IngredientViewModel` shared across screens; `RecipeViewModel` is scoped per recipe/detail screen. `IngredientScreen` collects `IngredientViewModel` StateFlows; typing in the add-ingredient field filters an in-memory list of names loaded once from `assets/ingredients.db` (no debounce, no network). `RecipeScreen` triggers `RecipeViewModel.searchRecipes(fridgeIngredients)` on entry, which queries `assets/database/recipe_database.sqlite` on a background dispatcher and exposes `sortedRecipes`/`filteredRecipes`. `RecipeDetailScreen` loads full ingredients/directions for one recipe on demand and lets the user deduct fridge quantities while cooking.

**Key ViewModel functions:**
- `IngredientViewModel`: `addIngredient`, `removeIngredient`, `setCount`/`incrementCount`/`decrementCount`, `setExpirationDate`, `togglePrioritized`, `clearAll`, `onSearchQueryChange`, `clearSuggestions`
- `RecipeViewModel`: `searchRecipes`, `loadRecipeDetail`, `toggleFavorite`, `setFilterQuery`

**Ingredient states:** default (`surfaceVariant`) vs. prioritized/starred (`primaryContainer`, boosts ranking). The excluded/negative state is disabled — see Current State above.

**Why `RecipeRanking.kt` is a separate file:** `RecipeMatch`, `ratioScore`, `matchTier`, `recipeOrder`, `matchOrder`, and `chunkIntLiterals` used to live as file-private declarations at the bottom of `RecipeViewModel.kt`. They're pure functions/comparators with no `Application`/`NewRecipeDao`/coroutine dependency — the actual scoring math, as opposed to the DB access and StateFlow plumbing around it — so nothing about them requires being co-located with the ViewModel. Splitting them into their own file and changing `private` to `internal` (same visibility trick `IngredientViewModel.rankSuggestions` already used) makes them directly unit-testable under plain JUnit, with no Room/Robolectric/instrumented-test setup needed — see `RecipeRankingTest.kt`. This is a narrower move than a full ViewModel→repository split: the DB-access code (`searchRecipesNew`, `scoreRecipesNew`, `hydrateNew`, etc.) stays in `RecipeViewModel.kt`, since after the legacy-corpus removal (see "The recipe corpus" above) that file is back down to a size where a bigger split isn't earning its complexity yet.

## Recipe matching

`data/IngredientMatcher.kt` is the single source of truth for "does this fridge item satisfy this recipe ingredient" — recipe scoring and the detail screen's check/X icons both call it, so the card's "X/Y ingredients" and the detail screen can't disagree.

Matching is **word-level and head-anchored**, not substring. A name reduces to a set of content words plus a *head* (the last word, skipping trailing part-words like `breast`/`half`/`clove`). Two names match when their heads are equal, one word set contains the other, and the extra words aren't in `BLOCK_MODIFIERS`. Consequences worth knowing:

- fridge `chicken` matches `chicken breast half` and `cut up chicken`, but **not** `chicken broth`, `chicken bouillon` or `cream chicken soup` — different head noun
- fridge `butter` doesn't match `peanut butter`; `cheese` doesn't match `cream cheese`; `egg` doesn't match `egg substitute`
- `egg` **does** match `egg white`/`egg yolk` (21,900 rows — the biggest single regression risk if `PART_WORDS` changes)
- fridge names are truncated at connectives (`and`, `with`, `without`, …) because the OpenFoodFacts taxonomy has entries like "organic cocoa mass and organic cocoa butter"; canonical names are never truncated, so `cream of tartar` keeps its head

The four word lists (`PART_WORDS`, `STOPWORDS`, `FRIDGE_CUT`, `BLOCK_MODIFIERS`) are tuned against the real corpus and guarded by `app/src/test/java/.../IngredientMatcherTest.kt`. **Change a list only alongside that test** — `IngredientMatcher` has no Android imports specifically so it runs under plain JUnit.

**Query strategy:** `NewIngredientIndex` resolves the fridge+pantry to the set of `ingredient_id`s they can supply (head-word matching plus category-taxonomy expansion — see "Category taxonomy" below), and `searchRecipes` scores every recipe in one `GROUP BY recipe_id` pass over `recipe_ingredients` using inline SQL literals — bound parameters can't be used, the matched set can be far past SQLite's 999-parameter limit (`RecipeViewModel.chunkIntLiterals` splits it into multiple queries if needed). `COUNT(DISTINCT ingredient_id)` is used for both numerator and denominator across every tier including `SEASONING` (see "Schema differences that matter" below for why that changed) — a recipe whose only gap is an unmatched `SEASONING`-tier ingredient still scores below 100% and shows a small "missing N seasonings" indicator on its card (`Recipe.unmatchedSeasoningCount`) rather than being silently treated as a full match.

**Pantry items and search-result relevance:** pantry-checked items (`data/PantryRepository.kt`) are merged into the same matched-ingredient set as real fridge items for scoring — but a recipe only appears in results at all if at least one matched ingredient traces back to a *real* fridge item (`RecipeMatch`/`Recipe.usesRealFridgeItem`, computed via `IngredientMatcher.parseFridge` origin-key membership). Without this gate, a handful of common pantry staples (garlic, onion, butter, olive oil) are common enough as `Supportive`/`Defining` ingredients that pantry alone qualified ~80% of the whole corpus for inclusion, regardless of what was actually in the fridge (measured directly against the corpus during this feature's development, not estimated) — so the gate applies to inclusion in `RecipeViewModel.searchRecipesNew`, not just ranking. Favorited recipes are exempt from the gate, same as the `MAX_RESULTS` cut. `usesRealFridgeItem` is also ranked above every other key in `recipeOrder`/`matchOrder` (but below favorites), so even an exempted favorite that's pantry-only sinks below any real-fridge match.

**Ranking** (`recipeOrder`/`matchOrder`, which must stay in sync — the cut to `MAX_RESULTS` happens before favorites are known): favorites, then `prioritizedCount`, then a *smoothed* ratio `matched / (total + 2)`, then `matchedCount`, then title. The smoothing is what stops trivial one-ingredient recipes from monopolizing the first page at a perfect 1.0. Card tier colors deliberately use the **unsmoothed** ratio so a real 3/3 still shows green.

**Recipe match-tier coloring** (`RecipeScreen.kt`, `RecipeCard`): `matchRatio = matchedCount / totalCount` (unsmoothed, unlike the ranking) → 100% uses `FullMatchContainer*`, ≥75% uses `PartialMatchContainer*`, below 75% falls back to the default `surfaceVariant` theme color. Both card background and ingredient-count text switch per tier, with separate light/dark values from `ui/theme/Color.kt`.

## The recipe corpus

`recipe_database.sqlite` (~88MB, odunola/foodie, 16,090 recipes after
`porting-reference/dedupe_exact_recipes.py` removed 3,476 exact-duplicate rows the source dataset
itself shipped with — see `NEW_CORPUS_DATA_QUALITY.md`) is the app's only recipe data source,
opened via Room. An earlier, much larger corpus (`recipes.db`, ~620MB, raw SQLite) existed
alongside it for a time, switched between at compile time; that path has since been removed
outright (not just disabled) to keep the large file from ever shipping in an APK build — see
`porting-reference/legacy-recipe-path/README.md` if it's ever needed for reference.
`porting-reference/` (outside the app module, not bundled) holds the build scripts,
taxonomy-construction scripts, and two write-ups — `INGREDIENT_MATCHING_CONCEPTS.md` (the ideas
behind the matching scheme) and `NEW_CORPUS_DATA_QUALITY.md` (the corpus's known data-quality
issues) — worth reading before touching the recipe-search code path.

**Schema differences that matter:** the new corpus tags every ingredient `DEFINING`/`SEASONING`/
`SUPPORTING` relative to its recipe (`recipe_ingredients.tier`). `SEASONING` rows originally
counted toward neither the numerator nor denominator of the match ratio — deliberately, back when
there was no way to know whether a fridge had salt short of literally typing it in, so a recipe
needing it was neither penalized nor credited. Once pantry items (`data/PantryRepository.kt`) gave
the app a real, user-confirmed seasoning-availability signal instead of that noise, `SEASONING`
rows were folded into `total`/`matched` like any other tier; `RecipeMatch`/`ratioScore`/
`matchTier`/`recipeOrder`/`matchOrder` didn't need to change to support this, only what feeds them
(`scoreRecipesNew`'s SQL) did. `SEASONING` totals are *also* tracked separately purely to power
`Recipe.unmatchedSeasoningCount` — a small card indicator (not a ranking input) calling out when
the only gap left is a seasoning, since that's a much lower bar to clear than a missing
Supportive/Defining ingredient. `servings`/`category`/`cuisine`/`country` are always `NULL` in this
corpus build, so `Recipe.servings`/`categories` are hardcoded empty for new-corpus recipes rather
than queried.

**Category taxonomy (`categories` table, `ingredients.category_id`):** a hand-curated (LLM-assigned)
tree — e.g. `Meat/Beef`, `Produce/Pepper/Bell` — built by `porting-reference/apply_categories.py`
from `porting-reference/head_categories.json`, to fix a gap `IngredientMatcher`'s string matching
can't: cuts that share no substring with their category (`ribeye`, `chuck`, `sirloin` are all
`Meat/Beef`, but none of them contain "beef"). `NewIngredientIndex.matching()` does the old
head-word matching first, then expands: any ingredient sharing a matched ingredient's `category_id`
is added too. An ingredient with `category_id = NULL` (blob name, or a head not yet covered by the
taxonomy — see `NEW_CORPUS_DATA_QUALITY.md` for the coverage numbers) just falls back to plain
string matching; nothing is ever removed by having no category, only possibly not boosted.

**Data-quality mitigation:** `SUPPRESS_BLOB_RECIPES_NEW` (~2.7% of `recipe_ingredients` rows are
un-stripped raw text, see `NEW_CORPUS_DATA_QUALITY.md`) suppresses affected recipes from ranking
rather than deleting anything, and is a named, reversible, app-side flag in `RecipeViewModel`'s
companion object.

**Room specifics:** `recipe_ingredients` is deliberately *not* a Room `@Entity` — its real composite
primary key `(recipe_id, ingredient_id, position)` has `position` declared without `NOT NULL`
(confirmed via `PRAGMA table_info`), which conflicts with Room's requirement that composite-key
fields be non-nullable Kotlin types. Since the app only ever reads that table, `NewRecipeDao`
accesses it entirely through `@RawQuery` methods (which skip Room's compile-time and runtime schema
validation) rather than `@Query`. See the docstring in `data/NewRecipeEntities.kt` before adding a
new entity or query against that table.

All dependency versions in `gradle/libs.versions.toml`.

## Release versioning and changelog routine

`versionCode`/`versionName` (`app/build.gradle.kts`) had never been bumped as of this writing —
still `1`/`"1.0"` from the original upload. **Do not bump on every commit or every push.**
`versionCode` exists solely to identify distinct artifacts uploaded to Google Play Console (every
upload, including internal/beta tracks, needs a strictly higher one than the last), so it should
only change immediately before cutting an actual release build — treat it as the last step of a
release checklist, not a development habit. `versionName` should follow semver
(`MAJOR.MINOR.PATCH`): PATCH for bug-fix-only releases, MINOR for backward-compatible new features
(the normal case), MAJOR reserved for a genuinely big user-visible overhaul.

**The tag is the source of truth for "what shipped."** Every commit actually uploaded to Play
Console gets tagged `playstore-v<versionName>-<versionCode>` (e.g. `playstore-v1.0-1`) at the
moment of upload — this is the only reliable way to answer "what's changed since the last
published version," since git history alone has no other marker for it. `playstore-v1.0-1` marks
the commit right after "Add landing page for GitHub Pages with a brief app overview"
(`37619f0`) — confirmed by the user as the actual last Play Store upload date (2026-08-15), and is
the oldest such tag; there was no way to reconstruct this retroactively from git alone, so if this
tag is ever missing or looks wrong, ask the user rather than guessing from commit dates.

**To write a changelog since the last release:** `git log <last-playstore-tag>..HEAD --oneline`
lists every candidate commit. Write the actual changelog as a short, grouped, user-facing summary
(by feature area, in plain language) — not a copy-paste of raw commit messages — the same way
Play Console's "What's new" release notes should read. As of the `playstore-v1.0-1` tag, 6 commits
are unreleased: rebranding to Fridge Grub, the full recipe corpus swap (odunola/foodie →
`recipes_open_v1_4`, along with several corpus data-quality fixes), the CC BY-SA attribution
screen the new corpus requires, the pantry/seasoning checklist feature, recipe-ranking fixes, two
navigation bug fixes, and recipe metadata (difficulty/time/ingredient-count) with filters —
comfortably enough for at least a MINOR bump (`1.0` → `1.1.0`, versionCode 2) whenever the user is
ready to cut that release; nothing forces it before then.

**Proactive reminder, for whichever session is active when this becomes relevant:** if the user
asks to commit, asks about shipping/releasing, or a work session is wrapping up, check
`git log <last-playstore-tag>..HEAD --oneline` — if several feature-level commits (not just tiny
fixes) have accumulated since the last `playstore-v*` tag, mention that a release/version bump
might be due, the same way this section itself came from the user asking for exactly that. Don't
bump the version or create the tag unilaterally — confirm with the user first, since tagging
happens at the moment of an actual upload they control.
