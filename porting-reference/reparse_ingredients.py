#!/usr/bin/env python3
"""
Full re-parse of recipe_ingredients/ingredients from the raw text already
stored in recipes.instructions_raw, using the newline-aware ingredient
splitter and improved amount/unit parser in build_recipe_db.py.

Wipes and rebuilds ingredients + recipe_ingredients from scratch (no
re-download needed) and computes the DEFINING/SUPPORTING/SEASONING tier
inline for every row via the same classify_tier() used elsewhere, so
tiering stays consistent across the whole database.
"""

import sqlite3
import build_recipe_db as b

DB = "recipe_database.sqlite"

def main():
    conn = sqlite3.connect(DB)
    cur = conn.cursor()

    cur.execute("DELETE FROM recipe_ingredients")
    cur.execute("DELETE FROM ingredients")

    recipes = cur.execute("SELECT recipe_id, title, instructions_raw FROM recipes").fetchall()
    print(f"Re-parsing {len(recipes)} recipes...")

    n_ing_rows = 0
    for i, (rid, title, raw) in enumerate(recipes, 1):
        _, ing_text, _ = b.split_sections(raw)
        phrases = b.title_phrases(title)
        lines = b.ingredient_lines(ing_text)

        for pos, line in enumerate(lines, 1):
            amount, unit, prep = b.parse_amount_unit(line)
            name = b.ingredient_name(line)
            nn = b.norm(name)
            if not nn:
                continue

            cur.execute("INSERT OR IGNORE INTO ingredients(name, normalized_name) VALUES (?,?)", (name, nn))
            iid = cur.execute(
                "SELECT ingredient_id FROM ingredients WHERE normalized_name=? LIMIT 1", (nn,)).fetchone()[0]
            tier = b.classify_tier(name, amount, unit, prep, phrases)

            cur.execute("""INSERT OR IGNORE INTO recipe_ingredients
                (recipe_id, ingredient_id, original_text, amount, unit, preparation, position, tier)
                VALUES (?,?,?,?,?,?,?,?)""",
                (rid, iid, line, amount, unit, prep, pos, tier))
            n_ing_rows += 1

        if i % 2000 == 0:
            conn.commit()
            print(f"  {i}/{len(recipes)} recipes done")

    conn.commit()
    print(f"Done. {n_ing_rows:,} recipe_ingredients rows across {len(recipes):,} recipes.")

    print("Rebuilding FTS indexes...")
    b.rebuild_fts(conn)
    conn.close()

if __name__ == "__main__":
    main()
