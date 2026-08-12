#!/usr/bin/env python3
"""
Rebuilds recipes/ingredients/categories with an explicit NOT NULL on their single-column
INTEGER PRIMARY KEY, and drops recipes.source_id's REFERENCES clause -- both in place.

Why NOT NULL: these tables were created as bare `col INTEGER PRIMARY KEY` (valid SQLite,
and PRAGMA table_info correctly reports notnull=0 for that form -- SQLite's rowid-alias
semantics don't require NOT NULL to be declared for the auto-assign-on-NULL-insert
behavior to work). Room's runtime schema validation does NOT compensate for this: it
compares the entity's declared notNull (true, since Kotlin's non-null Int implies it)
against the actual column's notnull flag, and rejects the whole database on mismatch --
confirmed via a real crash on-device, not theorized:

    java.lang.IllegalStateException: Pre-packaged database has an invalid schema:
    recipes(...RecipeEntity). Expected: ... notNull = 'true' ... Found: ... notNull = 'false'

Adding NOT NULL doesn't change read behavior for us -- these tables are never inserted
into after this migration runs (bundled read-only asset), so the "auto-assign on NULL
insert" behavior NOT NULL would otherwise block is moot.

Why drop the source_id reference: Room's schema validation also compares each entity's
declared foreign keys against `PRAGMA foreign_key_list`. `source_datasets` isn't modeled
as a Room entity at all (unused metadata, see NewRecipeEntities.kt's docstring), so
`RecipeEntity` declares no foreign keys -- but the original DDL's
`source_id INTEGER REFERENCES source_datasets(source_id)` shows up as a real entry in
`PRAGMA foreign_key_list(recipes)` regardless of whether FK enforcement was ever turned
on, causing the same class of Expected-vs-Found mismatch. Simplest fix: drop the
reference from the column definition (it was never enforced anyway) rather than modeling
a whole unused entity just to satisfy it.

recipe_steps is NOT touched here -- its composite primary key (recipe_id, step_no) was
already correctly NOT NULL on both columns (confirmed via PRAGMA table_info), so Room's
entity for it already validates fine; its real FK to recipes(recipe_id) IS modeled via
RecipeStepEntity's @ForeignKey, since recipes is a real Room entity. recipe_ingredients is
also not touched -- it's deliberately not a Room entity at all (see
app/.../data/NewRecipeEntities.kt's docstring). ingredients.category_id's real FK to
categories(category_id) and categories.parent_id's self-referencing FK are both left as-is
in the DDL and modeled properly via @ForeignKey in NewIngredientEntity/CategoryEntity,
since categories IS a real Room entity -- no DDL change needed for either.

Usage:
  python fix_primary_key_notnull.py --db recipe_database.sqlite
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")


def rebuild_categories(cur: sqlite3.Cursor) -> None:
    cur.execute("""
        CREATE TABLE categories_new(
            category_id INTEGER PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            parent_id INTEGER REFERENCES categories(category_id),
            UNIQUE(parent_id, name)
        )
    """)
    cur.execute("INSERT INTO categories_new SELECT * FROM categories")
    cur.execute("DROP TABLE categories")
    cur.execute("ALTER TABLE categories_new RENAME TO categories")


def rebuild_ingredients(cur: sqlite3.Cursor) -> None:
    cur.execute("""
        CREATE TABLE ingredients_new(
            ingredient_id INTEGER PRIMARY KEY NOT NULL,
            name TEXT NOT NULL UNIQUE,
            normalized_name TEXT NOT NULL,
            category_id INTEGER REFERENCES categories(category_id)
        )
    """)
    cur.execute("INSERT INTO ingredients_new SELECT * FROM ingredients")
    cur.execute("DROP TABLE ingredients")
    cur.execute("ALTER TABLE ingredients_new RENAME TO ingredients")
    cur.execute("CREATE INDEX idx_ingredients_normalized ON ingredients(normalized_name)")
    cur.execute("CREATE INDEX idx_ingredients_category ON ingredients(category_id)")


def rebuild_recipes(cur: sqlite3.Cursor) -> None:
    # source_id deliberately has no REFERENCES clause -- see module docstring.
    cur.execute("""
        CREATE TABLE recipes_new(
            recipe_id INTEGER PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            local_name TEXT,
            country TEXT,
            region TEXT,
            cuisine TEXT,
            category TEXT,
            prep_time TEXT,
            cook_time TEXT,
            total_time TEXT,
            servings TEXT,
            description TEXT,
            instructions_raw TEXT,
            source_id INTEGER
        )
    """)
    cur.execute("INSERT INTO recipes_new SELECT * FROM recipes")
    cur.execute("DROP TABLE recipes")
    cur.execute("ALTER TABLE recipes_new RENAME TO recipes")
    cur.execute("CREATE INDEX idx_recipes_country ON recipes(country)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    counts_before = {
        t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
        for t in ("categories", "ingredients", "recipes")
    }

    rebuild_categories(cur)
    rebuild_ingredients(cur)
    rebuild_recipes(cur)
    conn.commit()

    for table, before in counts_before.items():
        after = cur.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        status = "OK" if before == after else "MISMATCH"
        print(f"{table}: {before} -> {after} rows [{status}]")

    for table, pk in (("categories", "category_id"), ("ingredients", "ingredient_id"), ("recipes", "recipe_id")):
        notnull = cur.execute(f"PRAGMA table_info({table})").fetchall()
        pk_row = next(r for r in notnull if r[1] == pk)
        print(f"{table}.{pk}: notnull={pk_row[3]} (should be 1)")

    fks = cur.execute("PRAGMA foreign_key_list(recipes)").fetchall()
    print(f"recipes foreign keys: {fks} (should be [])")

    conn.close()


if __name__ == "__main__":
    main()
