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
./gradlew test --tests "com.example.kitchenassistant.ExampleUnitTest"

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
- Recipe detail view shows full ingredient list (checkmarked against the fridge) and directions, plus a "cook mode" that lets the user deduct used ingredients from fridge counts
- Favorites are persisted via SharedPreferences

### Current State

Working single-activity app with two main screens (ingredient list, recipe list) plus a recipe detail screen, navigated via a simple sealed-class `Screen` state machine in `MainActivity`. No network layer — ingredient autocomplete and recipe search both run against SQLite databases bundled in `app/src/main/assets/` (`ingredients.db`, `recipes.db`), copied to app-internal storage on first use. Ingredient list itself is still in-memory only (lost on process death); favorites are the only persisted state.

The exclude/negative-ingredient feature (`isNegative` on `Ingredient`) exists in the data model but its UI and toggle logic are commented out, not deleted — see `viewmodel/IngredientViewModel.kt` and `ui/IngredientScreen.kt` for the intentionally-disabled code paths.

## Tech Stack

- **Language**: Kotlin 2.2.10
- **UI**: Jetpack Compose with Material3 (BOM 2024.09.00)
- **Min SDK**: 24 (Android 7.0), **Target/Compile SDK**: 36
- **AGP**: 9.3.1
- **Persistence**: bundled read-only SQLite (`android.database.sqlite`) for ingredients/recipes, `SharedPreferences` for favorites
- **Build system**: Gradle with Kotlin DSL (`.gradle.kts`) and version catalog (`gradle/libs.versions.toml`)

No networking library (no Retrofit/OkHttp) is currently in the dependency graph — all data is local.

## Architecture

Single `app` module. Source root: `app/src/main/java/com/example/kitchenassistant/`

```
├── MainActivity.kt   Screen navigation (sealed class Screen: Ingredients / Recipes / RecipeDetail)
├── model/            Ingredient.kt, Recipe.kt, DetailIngredient.kt — core data classes
├── data/             FavoritesRepository.kt (SharedPreferences-backed favorite IDs), IngredientMatcher.kt (the matching rule), CanonicalIndex.kt (in-memory index of the corpus's canonical names)
├── viewmodel/        IngredientViewModel.kt, RecipeViewModel.kt — StateFlow-based state, both AndroidViewModel (need Application/assets access)
├── ui/               IngredientScreen.kt, RecipeScreen.kt, RecipeDetailScreen.kt, Scrollbar.kt
└── ui/theme/         KitchenAssistantTheme — dynamic color (Android 12+), dark/light modes; Color.kt has match-tier colors (FullMatch*, PartialMatch*, FridgeMatchGreen/FridgeMissingRed)
```

**Data flow:** `MainActivity` owns a single `IngredientViewModel` shared across screens; `RecipeViewModel` is scoped per recipe/detail screen. `IngredientScreen` collects `IngredientViewModel` StateFlows; typing in the add-ingredient field filters an in-memory list of names loaded once from `assets/ingredients.db` (no debounce, no network). `RecipeScreen` triggers `RecipeViewModel.searchRecipes(fridgeIngredients)` on entry, which queries `assets/recipes.db` on a background dispatcher and exposes `sortedRecipes`/`filteredRecipes`. `RecipeDetailScreen` loads full ingredients/directions for one recipe on demand and lets the user deduct fridge quantities while cooking.

**Key ViewModel functions:**
- `IngredientViewModel`: `addIngredient`, `removeIngredient`, `setCount`/`incrementCount`/`decrementCount`, `setCountByIdOrRemove` (used from cook mode, where 0 means "used it all up"), `setExpirationDate`, `togglePrioritized`, `clearAll`, `onSearchQueryChange`, `clearSuggestions`
- `RecipeViewModel`: `searchRecipes`, `loadRecipeDetail`, `toggleFavorite`, `setFilterQuery`

**Ingredient states:** default (`surfaceVariant`) vs. prioritized/starred (`primaryContainer`, boosts ranking). The excluded/negative state is disabled — see Current State above.

## Recipe matching

`data/IngredientMatcher.kt` is the single source of truth for "does this fridge item satisfy this recipe ingredient" — recipe scoring, the detail screen's check/X icons, and cook mode all call it, so the card's "X/Y ingredients" and the detail screen can't disagree.

Matching is **word-level and head-anchored**, not substring. A name reduces to a set of content words plus a *head* (the last word, skipping trailing part-words like `breast`/`half`/`clove`). Two names match when their heads are equal, one word set contains the other, and the extra words aren't in `BLOCK_MODIFIERS`. Consequences worth knowing:

- fridge `chicken` matches `chicken breast half` and `cut up chicken`, but **not** `chicken broth`, `chicken bouillon` or `cream chicken soup` — different head noun
- fridge `butter` doesn't match `peanut butter`; `cheese` doesn't match `cream cheese`; `egg` doesn't match `egg substitute`
- `egg` **does** match `egg white`/`egg yolk` (21,900 rows — the biggest single regression risk if `PART_WORDS` changes)
- fridge names are truncated at connectives (`and`, `with`, `without`, …) because the OpenFoodFacts taxonomy has entries like "organic cocoa mass and organic cocoa butter"; canonical names are never truncated, so `cream of tartar` keeps its head

The four word lists (`PART_WORDS`, `STOPWORDS`, `FRIDGE_CUT`, `BLOCK_MODIFIERS`) are tuned against the real corpus and guarded by `app/src/test/java/.../IngredientMatcherTest.kt`. **Change a list only alongside that test** — `IngredientMatcher` has no Android imports specifically so it runs under plain JUnit.

**Query strategy:** matching runs against the indexed `clean_ingredients.canonical` column, never `text`. `CanonicalIndex` caches all 93k distinct canonicals bucketed by head word (~9MB, built once per process), and `searchRecipes` resolves the fridge to a canonical set, then scores every recipe in one `GROUP BY recipe_id` pass using inline SQL literals — bound parameters can't be used, the set is far past SQLite's 999-parameter limit. `COUNT(DISTINCT canonical)` is used for both numerator and denominator (19% of recipes list a duplicate). Recipes with `parse_ok = 0` are dropped. Search time is independent of fridge size (~3s), where the old substring scan grew from 1.8s at 5 items to 12.7s at 25.

**Ranking** (`recipeOrder`/`matchOrder`, which must stay in sync — the cut to `MAX_RESULTS` happens before favorites are known): favorites, then `prioritizedCount`, then a *smoothed* ratio `matched / (total + 2)`, then `matchedCount`, then title. The smoothing is what stops trivial one-ingredient recipes from monopolizing the first page at a perfect 1.0. Card tier colors deliberately use the **unsmoothed** ratio so a real 3/3 still shows green.

**Recipe match-tier coloring** (`RecipeScreen.kt`, `RecipeCard`): `matchRatio = matchedCount / totalCount` (unsmoothed, unlike the ranking) → 100% uses `FullMatchContainer*`, ≥75% uses `PartialMatchContainer*`, below 75% falls back to the default `surfaceVariant` theme color. Both card background and ingredient-count text switch per tier, with separate light/dark values from `ui/theme/Color.kt`.

All dependency versions in `gradle/libs.versions.toml`.
