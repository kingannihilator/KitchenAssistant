#!/usr/bin/env python3
"""
Drops always-empty columns and unused tables from recipe_database.sqlite, in place.

Found while auditing which fields are "supposed to be short" vs. "supposed to be long":
`recipes` has 12 content columns (excluding the two id columns); only `title` (short) and
`instructions_raw` (long) actually carry data in this corpus build -- the other 10 are 100% NULL/
empty across every one of the 15,121 rows, confirmed by direct query, not by schema inspection
alone. `recipe_steps.step_title` is the same story (100% NULL). None of the ten are read anywhere
in app code either -- app-level Recipe.servings/categories are hardcoded null/emptyList() for this
corpus regardless of what's in the DB (see RecipeViewModel's hydrateNew docstring), and
step.stepTitle's only read site (RecipeViewModel.loadRecipeDetailNew) is a null-guarded fallback
that never fires.

`recipes.instructions_raw` is different from the other nine dropped `recipes` columns: it's
genuinely populated (median ~1,247 chars) but grep confirms it's never read anywhere in app code --
directions are displayed from the already-parsed `recipe_steps.instruction`, which was derived from
instructions_raw at corpus-build time and doesn't need it again at runtime. Dropping it removes
dead weight from the shipped asset. All ten drops were confirmed field-by-field with the user
before running this script (see the conversation this was built in) -- this isn't a blanket
"empty implies safe to drop" policy applied automatically.

Also drops:
  - idx_recipes_country (indexes the now-dropped `country` column)
  - source_datasets, recipe_cuisines, cuisines: unused, already flagged as unmapped/unused in
    NewRecipeEntities.kt's docstring (0 or 1 rows, never queried)
  - recipe_fts, ingredient_fts (FTS5 virtual tables): DROP TABLE on a virtual table also drops its
    shadow tables (_data/_idx/_docsize/_config) automatically. Deliberately never queried by the
    app -- fridge matching goes through IngredientMatcher/NewIngredientIndex, not FTS5 (see
    dedupe_exact_recipes.py's docstring for the same point made earlier).

NOT touched: recipe_ingredients.preparation's blob-leak (1,670 rows / 1.1% contain full leftover
ingredient text instead of a short prep note) -- explicitly deferred per user decision, out of
scope for this pass.

Whichever Kotlin files reference the dropped columns/tables (NewRecipeEntities.kt's RecipeEntity/
RecipeStepEntity, NewRecipeDao.kt's getSteps/RecipeStepRow, RecipeViewModel.kt's stepTitle usage)
must be updated in the same change -- Room validates the bundled schema byte-for-byte against the
declared entities at first open, so a schema/entity mismatch is a hard runtime crash, not a
silent no-op.

Usage:
  python cleanup_unused_schema.py --db recipe_database.sqlite
  python cleanup_unused_schema.py --db recipe_database.sqlite --dry-run
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")

RECIPES_COLUMNS_TO_DROP = [
    "local_name", "country", "region", "cuisine", "category",
    "prep_time", "cook_time", "total_time", "servings", "description",
    "instructions_raw",
]
RECIPE_STEPS_COLUMNS_TO_DROP = ["step_title"]
TABLES_TO_DROP = ["source_datasets", "recipe_cuisines", "cuisines", "recipe_fts", "ingredient_fts"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--dry-run", action="store_true", help="Report the plan without changing anything.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    print("Plan:")
    print(f"  DROP INDEX idx_recipes_country")
    for c in RECIPES_COLUMNS_TO_DROP:
        print(f"  ALTER TABLE recipes DROP COLUMN {c}")
    for c in RECIPE_STEPS_COLUMNS_TO_DROP:
        print(f"  ALTER TABLE recipe_steps DROP COLUMN {c}")
    for t in TABLES_TO_DROP:
        print(f"  DROP TABLE {t}")

    if args.dry_run:
        print("\nDry run -- nothing changed.")
        return

    before_size = Path(args.db).stat().st_size

    cur.execute("DROP INDEX IF EXISTS idx_recipes_country")
    for c in RECIPES_COLUMNS_TO_DROP:
        cur.execute(f"ALTER TABLE recipes DROP COLUMN {c}")
    for c in RECIPE_STEPS_COLUMNS_TO_DROP:
        cur.execute(f"ALTER TABLE recipe_steps DROP COLUMN {c}")
    for t in TABLES_TO_DROP:
        cur.execute(f"DROP TABLE IF EXISTS {t}")
    conn.commit()

    print("\nAfter recipes columns:", [r[1] for r in cur.execute("PRAGMA table_info(recipes)").fetchall()])
    print("After recipe_steps columns:", [r[1] for r in cur.execute("PRAGMA table_info(recipe_steps)").fetchall()])
    print("Remaining tables:", [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type IN ('table','view')").fetchall()])

    conn.execute("VACUUM")
    conn.close()

    after_size = Path(args.db).stat().st_size
    print(f"\nFile size: {before_size / 1e6:.1f} MB -> {after_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
