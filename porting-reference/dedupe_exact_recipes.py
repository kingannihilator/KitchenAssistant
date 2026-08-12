#!/usr/bin/env python3
"""
Removes exact-duplicate recipes from recipe_database.sqlite, in place.

Found while testing the app: the same recipe (e.g. "Natasha's Chicken Burgers") was showing up
twice in search results. Traced to the source dataset (odunola/foodie) itself, not anything in
this repo's build/port pipeline -- confirmed by comparing every column between duplicate rows,
not just the title:

    recipe_id 10609 and 11960: identical title, description, instructions_raw (character for
    character), AND source_id (both 1 -- so it's not "two sources scraped the same recipe", the
    same source's data has this row twice).

Measured across the whole corpus: 1,451 groups of byte-identical `instructions_raw`, covering
4,927 of 19,566 recipes (25.2%) -- some groups have up to 11 copies of the same recipe. That's
3,476 pure "extra" rows once each group is collapsed to one canonical entry.

Deduping on `instructions_raw` alone is both necessary and sufficient: `recipe_ingredients` and
`recipe_steps` are parsed FROM `instructions_raw` by a deterministic parser
(build_recipe_db.py/reparse_ingredients.py), so byte-identical instructions_raw guarantees
byte-identical derived rows too -- confirmed for the sample pair before writing this script, and
the script itself double-checks title also matches within each group as a cheap extra safety net,
skipping (not deleting) any group where it doesn't.

Unlike the blob-name and not-yet-categorized cases (deliberately NOT deleted -- see
NEW_CORPUS_DATA_QUALITY.md and the conversation this was built in), there is nothing to recover
from an exact duplicate: it's pure redundancy, not data that might be fixed up later. Deletion
here is the user's explicit choice for this specific case, not a blanket policy change.

recipe_fts/ingredient_fts (FTS5) are deliberately NOT rebuilt after this runs -- the app doesn't
use them (fridge matching goes through IngredientMatcher/NewIngredientIndex, not FTS5), so their
staleness relative to the now-smaller `recipes`/`ingredients` tables doesn't affect anything the
app actually queries.

Usage:
  python dedupe_exact_recipes.py --db recipe_database.sqlite
  python dedupe_exact_recipes.py --db recipe_database.sqlite --dry-run
"""

from __future__ import annotations

import argparse
import sqlite3
from collections import defaultdict
from pathlib import Path

DB = Path("recipe_database.sqlite")


def find_duplicate_groups(cur: sqlite3.Cursor) -> list[list[int]]:
    """Groups of recipe_ids sharing byte-identical instructions_raw, size >= 2, sorted so the
    lowest recipe_id (the one kept) is first in each group."""
    rows = cur.execute("SELECT recipe_id, instructions_raw, title FROM recipes WHERE instructions_raw IS NOT NULL").fetchall()
    by_text: dict[str, list[tuple[int, str]]] = defaultdict(list)
    for recipe_id, text, title in rows:
        by_text[text].append((recipe_id, title))

    groups = []
    skipped_title_mismatch = 0
    for text, entries in by_text.items():
        if len(entries) < 2:
            continue
        titles = {title for _, title in entries}
        if len(titles) > 1:
            # Identical instructions_raw but different titles would be surprising enough to be
            # worth a human look rather than an automatic delete -- skip, don't guess.
            skipped_title_mismatch += 1
            continue
        groups.append(sorted(recipe_id for recipe_id, _ in entries))

    if skipped_title_mismatch:
        print(f"Skipped {skipped_title_mismatch} group(s) with matching instructions_raw but "
              f"differing titles -- left untouched, worth a manual look.")
    return groups


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--dry-run", action="store_true", help="Report what would be deleted without deleting.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    groups = find_duplicate_groups(cur)
    to_delete = sorted(rid for group in groups for rid in group[1:])  # keep group[0] (min id)

    print(f"Duplicate groups: {len(groups)}")
    print(f"Recipes involved: {sum(len(g) for g in groups)}")
    print(f"Extra rows to delete (keeping lowest recipe_id per group): {len(to_delete)}")

    if args.dry_run:
        print("Dry run -- nothing deleted. Sample groups:")
        for group in groups[:5]:
            title = cur.execute("SELECT title FROM recipes WHERE recipe_id=?", (group[0],)).fetchone()[0]
            print(f"  keep {group[0]}, delete {group[1:]}  ({title!r})")
        return

    before = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
              for t in ("recipes", "recipe_ingredients", "recipe_steps", "recipe_cuisines")}

    id_list = ",".join(str(i) for i in to_delete)
    if id_list:
        # Explicit child-table deletes rather than relying on ON DELETE CASCADE -- SQLite only
        # enforces declared foreign keys when PRAGMA foreign_keys=ON is set on the connection
        # performing the delete, which sqlite3's Python driver does not do by default.
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

    # Orphan check: every remaining recipe_ingredients/recipe_steps row should point at a
    # surviving recipe_id.
    orphan_ri = cur.execute(
        "SELECT COUNT(*) FROM recipe_ingredients WHERE recipe_id NOT IN (SELECT recipe_id FROM recipes)"
    ).fetchone()[0]
    orphan_rs = cur.execute(
        "SELECT COUNT(*) FROM recipe_steps WHERE recipe_id NOT IN (SELECT recipe_id FROM recipes)"
    ).fetchone()[0]
    print(f"orphaned recipe_ingredients rows: {orphan_ri} (should be 0)")
    print(f"orphaned recipe_steps rows: {orphan_rs} (should be 0)")

    remaining_dupes = len(find_duplicate_groups(cur))
    print(f"remaining duplicate groups: {remaining_dupes} (should be 0, modulo any title-mismatch skips reported above)")

    conn.execute("VACUUM")
    conn.close()


if __name__ == "__main__":
    main()
