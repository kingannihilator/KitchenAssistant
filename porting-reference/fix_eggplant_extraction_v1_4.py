#!/usr/bin/env python3
"""
Fixes a real extraction bug found while testing the recipe-metadata feature: 6 old, prose-style
recipes (no structured ingredient list in the source text -- quantities embedded in the
directions, all sharing the old two-word title spelling "EGG PLANT") never got their actual
subject ingredient extracted at all. The original v1.4 extraction step matched the substring
"egg" *inside* "egg plant" and pulled out a spurious standalone "egg" ingredient instead, or
alongside a genuine one when the recipe separately calls for a real egg-dip step.

Verified individually against each recipe's own `recipe_steps` text (not assumed) before deciding
what to do with each recipe's existing "egg" row:

  3737 FRIED EGG PLANT            -- "Dip slices in beaten egg..." -- real egg use, kept as-is.
  4023 ESCALOPED EGG PLANT        -- no egg anywhere in the steps -- existing "egg" row is spurious.
  4024 BAKED EGG PLANT            -- no egg anywhere in the steps -- existing "egg" row is spurious.
  4025 FRIED EGG PLANT            -- no egg anywhere in the steps -- existing "egg" row is spurious.
  4026 EGG PLANT IN EGG AND CRACKER -- "pour beaten egg over..." -- real egg use, kept as-is.
  4027 EGG PLANT BALLS            -- "pour beaten egg over them..." -- real egg use, kept as-is.

All 6 get a new `Defining`-tier "eggplant" row linked to the corpus's existing `eggplant`
ingredient identity (id 2233) -- not a new one-off identity -- so fridge "eggplant" now correctly
matches these recipes the same way it already matches the other 19 eggplant recipes in the
corpus, and so they stop relying on the spurious/coincidental "egg" match instead.

Deliberately out of scope: the various other single-word junk fragments already present in these
6 recipes' ingredient lists (inch, dry, soft, vegetable, tin, plate, peas, fat) -- same
pre-existing, already-documented long-tail issue flagged (but not exhaustively cleaned up) during
the ingredient-identity rebuild earlier in this project's history. This script only touches the
egg/eggplant confusion it was written to fix.

Usage:
  python fix_eggplant_extraction_v1_4.py --db ../new_db_workable/recipes_open_v1_4.sqlite
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("../new_db_workable/recipes_open_v1_4.sqlite")

EGGPLANT_INGREDIENT_ID = 2233  # ingredients.canonical_name = 'eggplant', verified before running

# recipe_id -> whether egg plant is the *only* structured ingredient row before this fix (used
# only for the printed sanity check below, not for the fix logic itself).
RECIPE_TITLES = {
    3737: "FRIED EGG PLANT",
    4023: "ESCALOPED EGG PLANT",
    4024: "BAKED EGG PLANT",
    4025: "FRIED EGG PLANT",
    4026: "EGG PLANT IN EGG AND CRACKER",
    4027: "EGG PLANT BALLS",
}

# recipe_ingredients.id values whose "egg" row is spurious -- verified against each recipe's own
# recipe_steps text (see module docstring): no separate egg-dip/egg-wash step exists, so the only
# "egg" in the source text is the one inside "egg plant" itself.
SPURIOUS_EGG_ROW_IDS = [35634, 35642, 35650]  # recipes 4023, 4024, 4025 respectively


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    conn.text_factory = lambda b: b.decode("utf-8")
    cur = conn.cursor()

    name = cur.execute("SELECT canonical_name FROM ingredients WHERE id = ?", (EGGPLANT_INGREDIENT_ID,)).fetchone()
    if not name or name[0] != "eggplant":
        raise SystemExit(f"Expected ingredient {EGGPLANT_INGREDIENT_ID} to be 'eggplant', found {name}")

    added = 0
    for recipe_id, title in RECIPE_TITLES.items():
        cur.execute(
            """INSERT INTO recipe_ingredients(
                   recipe_id, ingredient_id, raw_text, role, role_reason, role_confidence,
                   ingredient_text, parse_confidence, parse_status, normalized_ingredient,
                   ingredient_match_name
               ) VALUES (?, ?, 'egg plant', 'Defining', ?, 1.0, 'eggplant', 1.0, 'parsed',
                         'eggplant', 'eggplant')""",
            (
                recipe_id,
                EGGPLANT_INGREDIENT_ID,
                "namesake ingredient recovered from recipe_steps text -- missing from the "
                "original extraction, which mistook \"egg\" (a substring of \"egg plant\") for "
                "a standalone ingredient instead",
            ),
        )
        added += cur.rowcount

    cur.executemany("DELETE FROM recipe_ingredients WHERE id = ?", [(i,) for i in SPURIOUS_EGG_ROW_IDS])
    removed = len(SPURIOUS_EGG_ROW_IDS)

    conn.commit()

    integrity = cur.execute("PRAGMA integrity_check").fetchone()[0]
    fk_violations = cur.execute("PRAGMA foreign_key_check").fetchall()

    print(f"Added {added} 'eggplant' rows, removed {removed} spurious 'egg' rows")
    print(f"integrity_check: {integrity}")
    print(f"foreign_key_check violations: {len(fk_violations)}")
    print()
    print("Spot check -- each recipe's ingredients after the fix:")
    for recipe_id, title in RECIPE_TITLES.items():
        rows = cur.execute(
            """SELECT i.canonical_name, ri.role FROM recipe_ingredients ri
               JOIN ingredients i ON i.id = ri.ingredient_id
               WHERE ri.recipe_id = ? ORDER BY ri.id""",
            (recipe_id,),
        ).fetchall()
        print(f"  {recipe_id} {title}: {rows}")

    conn.close()


if __name__ == "__main__":
    main()
