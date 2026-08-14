#!/usr/bin/env python3
"""
Removes recipes whose title is a scraped blog-post blob rather than an actual recipe title, from
recipe_database.sqlite, in place.

Found while auditing "recipes with a blob title" (a distinct issue from SUPPRESS_BLOB_RECIPES_NEW,
which is about blob *ingredient* names -- see NEW_CORPUS_DATA_QUALITY.md): source_id-flavored
recipes from the odunola/foodie scrape have `title` set to the entire first paragraph of the blog
post -- a date stamp, the recipe name, and marketing prose all run together with no separators,
e.g. "October 7, 2021Healthy Low Sugar Gluten-Free Pumpkin BarsI have enough gluten-free friends
and family...". Many are hard-truncated at exactly 200 characters (source scraper's cap): 529
titles are exactly 200 chars, another 150 are exactly 199.

`title_length_threshold` (default 150) was chosen by inspecting the corpus: every distinct title
>=150 chars sampled (all 450 of them) was this blob pattern, no false positives. Genuine long
titles (bilingual dish names like "Hønsekødssupper Med Kød Og Melboller (Chicken Soup with Meat
and Dumplings)") top out around 90 chars. Below 150 the picture gets murkier -- more blob titles
exist down to roughly 90 chars, but so do a couple of legitimate quirky titles (e.g. "Felix K.'s
'Don't even try to say these aren't the best you've ever eaten...' Chocolate Chip Cookies", 113
chars) -- so 150 is a deliberately conservative cut, not the full extent of the problem.

Unlike blob *ingredient* rows (deliberately not deleted -- see SUPPRESS_BLOB_RECIPES_NEW's
docstring in RecipeViewModel, there's an LLM-recovery pass that could still salvage them), a blob
title is being deleted outright per explicit user decision: recovering "the actual recipe name"
from a run-on paragraph isn't reliably automatable, and unlike the ingredient case this doesn't
corrupt matching (title isn't used for scoring) -- it's a pure display problem with no good
suppress-in-ranking answer, so removal is the fix.

Same child-table-then-parent delete order as dedupe_exact_recipes.py/remove_recipe.py, and for the
same reason: recipe_fts is deliberately NOT kept in sync -- the app doesn't query it (fridge
matching goes through IngredientMatcher/NewIngredientIndex, not FTS5).

Usage:
  python remove_blob_title_recipes.py --db recipe_database.sqlite
  python remove_blob_title_recipes.py --db recipe_database.sqlite --dry-run
  python remove_blob_title_recipes.py --db recipe_database.sqlite --title-length-threshold 150
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")
DEFAULT_TITLE_LENGTH_THRESHOLD = 150


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--title-length-threshold", type=int, default=DEFAULT_TITLE_LENGTH_THRESHOLD,
                     help="Recipes with a title at least this many characters are removed.")
    ap.add_argument("--dry-run", action="store_true", help="Report what would be deleted without deleting.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    rows = cur.execute(
        "SELECT recipe_id, title FROM recipes WHERE length(title) >= ?",
        (args.title_length_threshold,),
    ).fetchall()

    if not rows:
        print(f"No recipes with title length >= {args.title_length_threshold}. Nothing to do.")
        return

    to_delete = [rid for rid, _ in rows]
    print(f"Found {len(to_delete)} recipes with title length >= {args.title_length_threshold} "
          f"({len(set(t for _, t in rows))} distinct titles).")

    if args.dry_run:
        print("Dry run -- nothing deleted. Sample:")
        for rid, title in rows[:5]:
            print(f"  {rid}: {title[:100]!r}{'...' if len(title) > 100 else ''}")
        return

    before = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
              for t in ("recipes", "recipe_ingredients", "recipe_steps", "recipe_cuisines")}

    id_list = ",".join(str(i) for i in to_delete)
    cur.execute(f"DELETE FROM recipe_ingredients WHERE recipe_id IN ({id_list})")
    cur.execute(f"DELETE FROM recipe_steps WHERE recipe_id IN ({id_list})")
    cur.execute(f"DELETE FROM recipe_cuisines WHERE recipe_id IN ({id_list})")
    cur.execute(f"DELETE FROM recipes WHERE recipe_id IN ({id_list})")
    conn.commit()

    after = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
             for t in ("recipes", "recipe_ingredients", "recipe_steps", "recipe_cuisines")}

    print()
    for t in before:
        print(f"{t}: {before[t]} -> {after[t]} ({before[t] - after[t]} removed)")

    orphan_ri = cur.execute(
        "SELECT COUNT(*) FROM recipe_ingredients WHERE recipe_id NOT IN (SELECT recipe_id FROM recipes)"
    ).fetchone()[0]
    orphan_rs = cur.execute(
        "SELECT COUNT(*) FROM recipe_steps WHERE recipe_id NOT IN (SELECT recipe_id FROM recipes)"
    ).fetchone()[0]
    print(f"orphaned recipe_ingredients rows: {orphan_ri} (should be 0)")
    print(f"orphaned recipe_steps rows: {orphan_rs} (should be 0)")

    conn.execute("VACUUM")
    conn.close()


if __name__ == "__main__":
    main()
