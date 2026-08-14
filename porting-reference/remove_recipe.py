#!/usr/bin/env python3
"""
Removes a single recipe (by exact title) from recipe_database.sqlite, in place.

One-off tool for stripping out entries that aren't actual recipes -- e.g. "25 Summer Dinner
Ideas" (recipe_id 15664), a listicle title that slipped into the odunola/foodie source dataset
alongside real recipes. Unlike SUPPRESS_BLOB_RECIPES_NEW/SUPPRESS_UNDERPARSED_RECIPES (app-side
flags that hide affected recipes from ranking because the underlying text might still be
recoverable), a non-recipe title has nothing to recover -- deletion is appropriate.

Same child-table-then-parent delete order as dedupe_exact_recipes.py, and for the same reason:
recipe_fts is deliberately NOT kept in sync -- the app doesn't query it (fridge matching goes
through IngredientMatcher/NewIngredientIndex, not FTS5).

Usage:
  python remove_recipe.py --db recipe_database.sqlite --title "25 Summer Dinner Ideas"
  python remove_recipe.py --db recipe_database.sqlite --title "25 Summer Dinner Ideas" --dry-run
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--title", required=True, help="Exact recipe title to remove.")
    ap.add_argument("--dry-run", action="store_true", help="Report what would be deleted without deleting.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    rows = cur.execute("SELECT recipe_id, title FROM recipes WHERE title = ?", (args.title,)).fetchall()
    if not rows:
        print(f"No recipe found with title {args.title!r}. Nothing to do.")
        return
    if len(rows) > 1:
        print(f"Refusing to proceed: {len(rows)} recipes share title {args.title!r}: {rows}. "
              f"Re-run targeting a specific recipe_id if that's expected.")
        return

    recipe_id, title = rows[0]
    ing_count = cur.execute("SELECT COUNT(*) FROM recipe_ingredients WHERE recipe_id=?", (recipe_id,)).fetchone()[0]
    step_count = cur.execute("SELECT COUNT(*) FROM recipe_steps WHERE recipe_id=?", (recipe_id,)).fetchone()[0]
    cuisine_count = cur.execute("SELECT COUNT(*) FROM recipe_cuisines WHERE recipe_id=?", (recipe_id,)).fetchone()[0]

    print(f"Found recipe_id={recipe_id} title={title!r}")
    print(f"  recipe_ingredients rows: {ing_count}")
    print(f"  recipe_steps rows: {step_count}")
    print(f"  recipe_cuisines rows: {cuisine_count}")

    if args.dry_run:
        print("Dry run -- nothing deleted.")
        return

    cur.execute("DELETE FROM recipe_ingredients WHERE recipe_id=?", (recipe_id,))
    cur.execute("DELETE FROM recipe_steps WHERE recipe_id=?", (recipe_id,))
    cur.execute("DELETE FROM recipe_cuisines WHERE recipe_id=?", (recipe_id,))
    cur.execute("DELETE FROM recipes WHERE recipe_id=?", (recipe_id,))
    conn.commit()

    still_there = cur.execute("SELECT COUNT(*) FROM recipes WHERE recipe_id=?", (recipe_id,)).fetchone()[0]
    print(f"Deleted. recipes row still present: {bool(still_there)} (should be False)")

    conn.execute("VACUUM")
    conn.close()


if __name__ == "__main__":
    main()
