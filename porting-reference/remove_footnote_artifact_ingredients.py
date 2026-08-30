#!/usr/bin/env python3
"""
Removes two ingredient rows from recipe 97 ("Apple Crumb Cake") that are actually Wikipedia
footnote-backlink text ("↑ a b total amount of flour"), not real ingredients, in
recipe_database.sqlite, in place.

## Background

Found while investigating a fridge "flour"/"sugar" search wrongly crediting matches on this
recipe: its ingredient extraction produced three garbled rows, all Wikibooks Cookbook footnote
backlinks ("↑ a b ...") rather than real ingredient text -- e.g. "↑ a b total amount of flour for
whole recipe: 3⅓ cups or 410 grams" got truncated during extraction into a much shorter, still
un-real "↑ a b total amount of flour". Two of the three (ingredient_id 8214 "...of flour", 8216
"...of sugar") are short enough (27 chars) to fall under the blob-length threshold (40) that
would otherwise suppress them from matching -- so unlike most extraction junk in this corpus, these
two actively participate in real search scoring today. The third (8212, "...of butter for whole
recipe: 16 ounces (one pound", 67 chars) is already blob-suppressed and left alone; it's still
wrong data, but harmless, and recipe 97's blob-suppression status doesn't depend on removing it.

Checked before deciding on this fix: a sibling recipe (2623 "Poticza") has the exact same footnote-
artifact pattern, but all three of its rows are already long enough to be blob-suppressed, so it
has no active bug and needs no change. Also checked recipe 97's other content: it has 17 real,
substantial direction steps, so the recipe itself is legitimate -- only its structured ingredient
list failed to extract, which is why this removes just the two bad rows rather than the whole
recipe (unlike remove_blob_title_recipes.py, which deletes recipes whose *title* extraction failed
outright with no salvageable content).

Confirmed via direct query that ingredient_ids 8214 and 8216 are referenced by recipe 97 only, so
this also removes the two ingredients rows themselves (no longer referenced by anything) rather
than leaving them as harmless orphans.

Usage:
  python remove_footnote_artifact_ingredients.py --db recipe_database.sqlite
  python remove_footnote_artifact_ingredients.py --db recipe_database.sqlite --dry-run
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")

RECIPE_ID = 97
INGREDIENT_IDS = [8214, 8216]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--dry-run", action="store_true", help="Report what would change without writing.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    id_list = ",".join(str(i) for i in INGREDIENT_IDS)

    rows = cur.execute(
        f"SELECT ingredient_id, normalized_name FROM ingredients WHERE ingredient_id IN ({id_list})"
    ).fetchall()
    if len(rows) != len(INGREDIENT_IDS):
        print(f"Expected {len(INGREDIENT_IDS)} ingredient rows, found {len(rows)} -- aborting, verify by hand.")
        return
    for iid, name in rows:
        print(f"  ingredient_id={iid}: {name!r}")

    other_refs = cur.execute(
        f"SELECT DISTINCT recipe_id FROM recipe_ingredients WHERE ingredient_id IN ({id_list}) AND recipe_id != ?",
        (RECIPE_ID,),
    ).fetchall()
    if other_refs:
        print(f"These ingredients are also used by other recipes {other_refs} -- aborting, would need a narrower fix.")
        return

    print(f"\n{'Would remove' if args.dry_run else 'Removing'} {len(INGREDIENT_IDS)} recipe_ingredients row(s) "
          f"for recipe {RECIPE_ID} and their now-orphaned ingredients row(s).")
    if args.dry_run:
        return

    cur.execute(f"DELETE FROM recipe_ingredients WHERE recipe_id = ? AND ingredient_id IN ({id_list})", (RECIPE_ID,))
    cur.execute(f"DELETE FROM ingredients WHERE ingredient_id IN ({id_list})")
    conn.commit()

    remaining = cur.execute(
        "SELECT i.ingredient_id, i.normalized_name FROM ingredients i JOIN recipe_ingredients ri "
        "ON ri.ingredient_id = i.ingredient_id WHERE ri.recipe_id = ?", (RECIPE_ID,)
    ).fetchall()
    print(f"Recipe {RECIPE_ID}'s remaining ingredient(s): {remaining}")
    conn.close()


if __name__ == "__main__":
    main()
