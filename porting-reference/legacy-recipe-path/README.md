# Legacy recipe-path backup

This folder preserves the `recipes.db`-backed ("legacy") recipe search/detail code path that was
removed from the app module. It's reference material only — nothing here compiles as part of the
app, and nothing here is bundled into an APK.

## Why it was removed

The app shipped two recipe corpora side by side, switched at compile time via
`RecipeViewModel.USE_NEW_RECIPE_DATABASE`: the original `recipes.db` (~620MB, raw SQLite,
`CanonicalIndex`-based matching) and the newer `recipe_database.sqlite` (~95MB, Room-based,
odunola/foodie corpus). The new corpus had already been the live default for a while, with the
legacy path kept fully intact and reachable only by flipping the flag back. To stop `recipes.db`
from ever being able to ship in an APK build again — and to shrink `RecipeViewModel.kt` — the
legacy path was deleted outright rather than left dormant.

## What's here

- `CanonicalIndex.kt` — full pre-deletion copy of the deleted file
  (`app/src/main/java/com/example/kitchenassistant/data/CanonicalIndex.kt`)
- `RecipeViewModel.legacy-excerpt.kt` — the deleted consts/functions from
  `RecipeViewModel.kt` (`USE_NEW_RECIPE_DATABASE`, `SUPPRESS_UNDERPARSED_RECIPES`,
  `searchRecipesLegacy`, `loadRecipeDetailLegacy`, `loadFavoriteRecipesLegacy`, `scoreRecipes`,
  `queryChunk`, `hydrate`, `loadUnparseableRecipeIds`, `loadUnderparsedRecipeIds`,
  `openRecipesDatabase`, `chunkByLiteralSize`, `sqlLiteralList`)
- `BundledDatabase.legacy-excerpt.kt` — the deleted `recipes.db`-only self-healing-index block
  from `data/BundledDatabase.kt` (`RECIPE_ID_INDEXES`, `ensureRecipeIdIndexes`); the rest of
  `BundledDatabase.kt` was kept, since `IngredientViewModel` still uses it for `ingredients.db`

## The data file itself

`recipes.db` is not duplicated here (it's ~620-681MB). A backup copy already lives outside the
app module at `db-backup/recipes.db` (gitignored, same as before this change) — see that folder
if the raw data is ever needed again.
