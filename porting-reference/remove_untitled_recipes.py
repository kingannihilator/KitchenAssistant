#!/usr/bin/env python3
"""
Removes recipes whose title is a literal scraper section-label rather than an actual title, from
recipe_database.sqlite, in place.

Found while auditing duplicate titles: 210 rows (186 distinct real recipes -- different
ingredients/instructions each) all have title = "summary". Root cause traced into
instructions_raw, which for these rows literally contains the source page's section labels mixed
into the scraped text:

    summary
    Chicken feet are eaten similarly to the way we eat chicken wings. Here is a guide...
    ingredients
    2 pounds chicken feet1 tablespoon vegetable oil4 garlic cloves minced...
    instructions
    Using a sharp knife, chop the nails off the claws...

The scraper grabbed a "summary" section-header label (probably a `class="summary"` div) into the
`title` column instead of the page's actual title. `description` is empty on all of these rows,
and there is no field anywhere holding the real original title -- unlike blob ingredient rows
(recoverable from original_text) or blob titles (recoverable in the sense that the truncated
paragraph still contains the real name, just buried), the real title was simply never captured.
That makes these different from SUPPRESS_BLOB_RECIPES_NEW's "defer to a future LLM-recovery pass"
stance -- there's nothing there to recover. Deletion is the user's explicit choice, same reasoning
as remove_blob_title_recipes.py.

Matches by exact title rather than a length heuristic, since the failure mode here is a specific
literal string, not a length distribution -- pass --title to target a different literal value if
another such label surfaces later (this script found only "summary"; see the investigation this
was built in for how "ingredients"/"directions"/"instructions"/etc. were ruled out as exact
titles, zero hits each).

Same child-table-then-parent delete order as dedupe_exact_recipes.py/remove_blob_title_recipes.py,
and for the same reason: recipe_fts is deliberately NOT kept in sync -- the app doesn't query it.

Usage:
  python remove_untitled_recipes.py --db recipe_database.sqlite
  python remove_untitled_recipes.py --db recipe_database.sqlite --dry-run
  python remove_untitled_recipes.py --db recipe_database.sqlite --title "ingredients"
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")
DEFAULT_TITLE = "summary"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--title", default=DEFAULT_TITLE, help="Exact literal title to remove all rows of.")
    ap.add_argument("--dry-run", action="store_true", help="Report what would be deleted without deleting.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    rows = cur.execute("SELECT recipe_id FROM recipes WHERE title = ?", (args.title,)).fetchall()
    to_delete = [rid for (rid,) in rows]

    if not to_delete:
        print(f"No recipes with title == {args.title!r}. Nothing to do.")
        return

    print(f"Found {len(to_delete)} recipes with title == {args.title!r}.")

    if args.dry_run:
        print("Dry run -- nothing deleted.")
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
