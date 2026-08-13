"""Adds a handful of common grocery terms to app/src/main/assets/ingredients.db that the bundled
OpenFoodFacts-derived taxonomy has no entry for at all.

Background: ingredients.db has been a pre-built binary asset since this repo's first commit, with
no build/generation script ever checked in (confirmed by searching the full git history) -- so
gaps in it can't be fixed by regenerating from source, only by patching the shipped file directly.
This is the first such patch, and the first script that touches ingredients.db at all.

The specific gap this fixes: a user reported that typing "pork" into the fridge-add autocomplete
never surfaces "pork steak", even though the recipe corpus has recipes calling for it (e.g. "St.
Louis Pork Steak"). Checking confirmed the taxonomy has no bare "pork steak" or "beef steak" row --
only oddly-qualified variants like "pork minute steak" and "cooked minced beef steak with 15% fat".
"beef roast"/"pork roast" have the same gap and were added alongside them since they're the same
class of common, generic cut name with no bare entry, and (unlike "pork steak", genuinely rare in
the bundled 16k-recipe corpus at just 2 mentions) both have real recipe-corpus frequency (72 and 62
mentions respectively) backing that they're worth adding.

These four were deliberately NOT chosen by scanning for every protein+cut combination that happens
to be absent -- "beef chop", "turkey steak", "lamb steak" etc. are equally absent but were checked
and are not common real-world grocery terms, so inventing entries for them would be curation
without evidence, the same trap IngredientMatcher's word lists are deliberately careful to avoid.

Run once against the real asset:
    python porting-reference/patch_missing_ingredients.py
"""

import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "ingredients.db"

# (id, name_en) -- id follows the existing "en:kebab-case-name" OpenFoodFacts convention.
NEW_ENTRIES = [
    ("en:beef-steak", "beef steak"),
    ("en:pork-steak", "pork steak"),
    ("en:beef-roast", "beef roast"),
    ("en:pork-roast", "pork roast"),
]


def main() -> None:
    con = sqlite3.connect(DB_PATH)
    cur = con.cursor()
    for entry_id, name_en in NEW_ENTRIES:
        cur.execute("SELECT 1 FROM ingredients WHERE id = ?", (entry_id,))
        if cur.fetchone() is not None:
            print(f"skip (already exists): {entry_id}")
            continue
        cur.execute("INSERT INTO ingredients (id, name_en) VALUES (?, ?)", (entry_id, name_en))
        print(f"added: {entry_id} -> {name_en!r}")
    con.commit()

    cur.execute("SELECT COUNT(*) FROM ingredients")
    print(f"total rows now: {cur.fetchone()[0]}")
    con.close()


if __name__ == "__main__":
    main()
