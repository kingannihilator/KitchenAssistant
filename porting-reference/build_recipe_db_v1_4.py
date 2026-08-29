#!/usr/bin/env python3
"""
Transforms `new_db_workable/recipes_open_v1_4.sqlite` (the "clean" v1.4 corpus, see
new_db_workable/HANDOVER.md) into the exact schema the app's Room layer expects
(app/src/main/java/.../data/NewRecipeEntities.kt, NewRecipeDao.kt), so the corpus swap needs
zero changes to app code -- only the bundled asset file changes.

Output tables (app schema):
  recipes(recipe_id, title, source_id, servings, difficulty, time_text, total_minutes_min,
          total_minutes_max)
  ingredients(ingredient_id, name, normalized_name, category_id)   -- category_id left NULL
                                                                       here; apply_categories.py
                                                                       fills it in a second pass
  recipe_ingredients(recipe_id, ingredient_id, original_text, amount, unit, preparation,
                      position, tier)
  recipe_steps(recipe_id, step_no, instruction)

Source -> app mapping:
  recipes.id -> recipe_id, recipes.name -> title, recipes.source_id -> source_id,
      recipes.servings/difficulty/time_text/total_minutes_min/total_minutes_max carried through
      as-is (all nullable; see new_db_workable/HANDOVER.md population rates -- 74% difficulty,
      ~25% time/servings, none parsed or reshaped here, that happens app-side in Recipe.kt/
      RecipeViewModel so it stays unit-testable under plain JUnit)
  ingredients.id -> ingredient_id, ingredients.canonical_name -> name,
      lower(canonical_name) -> normalized_name
  recipe_ingredients: recipe_id, ingredient_id, raw_text -> original_text,
      quantity_text -> amount, unit -> unit, preparation -> preparation,
      row_number() over (partition by recipe_id order by id) -> position,
      role -> tier via ROLE_TO_TIER (Defining/Supportive/Seasoning -> DEFINING/SUPPORTING/SEASONING)
  recipe_steps: recipe_id, step_number -> step_no, instruction -> instruction

Everything else in the source file (quantity parsing detail beyond amount/unit/preparation,
sources/licenses/source_rights, FTS tables, ingredient_ontology/aliases, quality/usefulness
tables) is intentionally dropped -- the app doesn't read any of it. The source file itself
(plus its README/audit JSON) stays in new_db_workable/ as the provenance record.

Usage:
  python build_recipe_db_v1_4.py
  python build_recipe_db_v1_4.py --src ../new_db_workable/recipes_open_v1_4.sqlite --out recipe_database_v1_4.sqlite
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

SRC = Path("../new_db_workable/recipes_open_v1_4.sqlite")
OUT = Path("recipe_database_v1_4.sqlite")

ROLE_TO_TIER = {
    "Defining": "DEFINING",
    "Supportive": "SUPPORTING",
    "Seasoning": "SEASONING",
}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", default=str(SRC))
    ap.add_argument("--out", default=str(OUT))
    args = ap.parse_args()

    out_path = Path(args.out)
    if out_path.exists():
        out_path.unlink()

    src = sqlite3.connect(args.src)
    src.text_factory = lambda b: b.decode("utf-8", "replace")
    out = sqlite3.connect(args.out)

    out.execute("""
        CREATE TABLE recipes(
            recipe_id INTEGER PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            source_id INTEGER,
            servings TEXT,
            difficulty INTEGER,
            time_text TEXT,
            total_minutes_min INTEGER,
            total_minutes_max INTEGER
        )
    """)
    out.execute("""
        CREATE TABLE categories(
            category_id INTEGER PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            parent_id INTEGER REFERENCES categories(category_id),
            UNIQUE(parent_id, name)
        )
    """)
    out.execute("""
        CREATE TABLE ingredients(
            ingredient_id INTEGER PRIMARY KEY NOT NULL,
            name TEXT NOT NULL UNIQUE,
            normalized_name TEXT NOT NULL,
            category_id INTEGER REFERENCES categories(category_id)
        )
    """)
    out.execute("CREATE INDEX idx_ingredients_category ON ingredients(category_id)")
    out.execute("CREATE INDEX idx_ingredients_normalized ON ingredients(normalized_name)")
    out.execute("""
        CREATE TABLE recipe_ingredients(
            recipe_id INTEGER NOT NULL REFERENCES recipes(recipe_id),
            ingredient_id INTEGER NOT NULL REFERENCES ingredients(ingredient_id),
            original_text TEXT NOT NULL,
            amount TEXT,
            unit TEXT,
            preparation TEXT,
            position INTEGER,
            tier TEXT
        )
    """)
    out.execute("""
        CREATE TABLE recipe_steps(
            recipe_id INTEGER NOT NULL REFERENCES recipes(recipe_id) ON DELETE CASCADE,
            step_no INTEGER NOT NULL,
            instruction TEXT NOT NULL,
            PRIMARY KEY (recipe_id, step_no)
        )
    """)

    recipes = src.execute("""
        SELECT id, name, source_id, servings, difficulty, time_text, total_minutes_min,
               total_minutes_max
        FROM recipes
    """).fetchall()
    out.executemany(
        """INSERT INTO recipes(recipe_id, title, source_id, servings, difficulty, time_text,
                                total_minutes_min, total_minutes_max)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        recipes,
    )

    ingredients = src.execute("SELECT id, canonical_name FROM ingredients").fetchall()
    out.executemany(
        "INSERT INTO ingredients(ingredient_id, name, normalized_name, category_id) VALUES (?, ?, ?, NULL)",
        [(iid, name, name.lower()) for iid, name in ingredients],
    )

    ri_rows = src.execute("""
        SELECT recipe_id, ingredient_id, raw_text, quantity_text, unit, preparation, role,
               ROW_NUMBER() OVER (PARTITION BY recipe_id ORDER BY id) AS position
        FROM recipe_ingredients
    """).fetchall()
    unknown_roles = {role for *_, role, _ in ri_rows if role not in ROLE_TO_TIER}
    if unknown_roles:
        raise SystemExit(f"Unrecognized role values not in ROLE_TO_TIER: {unknown_roles}")
    out.executemany(
        """INSERT INTO recipe_ingredients(recipe_id, ingredient_id, original_text, amount, unit,
                                           preparation, position, tier)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        [
            (recipe_id, ingredient_id, raw_text, amount, unit, prep, position, ROLE_TO_TIER[role])
            for recipe_id, ingredient_id, raw_text, amount, unit, prep, role, position in ri_rows
        ],
    )

    steps = src.execute("SELECT recipe_id, step_number, instruction FROM recipe_steps").fetchall()
    out.executemany(
        "INSERT INTO recipe_steps(recipe_id, step_no, instruction) VALUES (?, ?, ?)", steps
    )

    out.commit()

    integrity = out.execute("PRAGMA integrity_check").fetchone()[0]
    fk_violations = out.execute("PRAGMA foreign_key_check").fetchall()

    def count(conn, table):
        return conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]

    print(f"integrity_check: {integrity}")
    print(f"foreign_key_check violations: {len(fk_violations)}")
    print()
    print(f"{'table':<20}{'source rows':>14}{'output rows':>14}")
    for src_table, out_table in [
        ("recipes", "recipes"),
        ("ingredients", "ingredients"),
        ("recipe_ingredients", "recipe_ingredients"),
        ("recipe_steps", "recipe_steps"),
    ]:
        print(f"{out_table:<20}{count(src, src_table):>14}{count(out, out_table):>14}")

    out.close()
    src.close()
    print(f"\nWrote {args.out}")


if __name__ == "__main__":
    main()
