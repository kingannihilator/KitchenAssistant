#!/usr/bin/env python3
"""
Appends a Roman-numeral suffix to every recipe whose title (case-insensitive) is shared by more
than one recipe, in recipe_database.sqlite, in place.

## Why

65 distinct titles are shared by 2-3 recipes each (136 recipes total) -- mostly a Title-Case
scrape of one source next to an ALL-CAPS scrape of another (e.g. "Black Bean Soup" / "BLACK BEAN
SOUP" / "Black Bean Soup" at three different recipe_ids). Checked every group's actual ingredient
sets directly: zero are exact duplicates (see dedupe_exact_recipes.py for that separate, already-
handled problem) -- these are genuinely different recipes that happen to share a name, same as the
corpus's own "Meatloaf V" / "Snickerdoodles I"/"Snickerdoodles II" pattern already does for 120
other titles.

## Numbering convention

Checked all 120 pre-existing numbered titles first: 112 have *no* bare/unnumbered counterpart --
the corpus's own convention numbers every variant starting at "I", it doesn't leave one bare while
numbering the rest (only 8 legacy titles do that, and they read as a pre-existing inconsistency in
the source data, not a pattern worth extending). So every member of a duplicate-title group gets a
numeral here, including the first (lowest recipe_id) -- there is no unnumbered "base" left behind.

Numerals are assigned in recipe_id order, skipping any numeral already taken by a *pre-existing*,
separately-titled recipe with the same base name -- e.g. "Corn Chowder I"/"Corn Chowder II" already
existed as their own separate recipes before this script ever runs, so the two "CORN CHOWDER"
duplicates in this pass become "III"/"IV", not colliding "I"/"II". Every generated title is checked
against every existing title (case-insensitively) before being applied; the script refuses to
create a new collision.

This script computes its plan fresh from the live data each run rather than hardcoding a recipe_id
list -- unlike fix_bare_fraction_time_bug.py/fix_unparsed_time_text.py, the "what's a duplicate"
question only depends on title text, not the kind of hand-verified per-row judgment those two
needed. It's naturally idempotent: once every duplicate group is numbered, a second run finds no
remaining case-insensitive duplicates and renames nothing.

Usage:
  python disambiguate_duplicate_titles.py --db recipe_database.sqlite
  python disambiguate_duplicate_titles.py --db recipe_database.sqlite --dry-run
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")

ROMAN_NUMERALS = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]


def build_plan(cur: sqlite3.Cursor) -> tuple[list[tuple[int, str, str]], list[tuple[str, int, str, str]]]:
    all_rows = cur.execute("SELECT recipe_id, title FROM recipes").fetchall()
    all_lower_titles = {title.lower() for _, title in all_rows}

    dup_titles = [
        row[0] for row in cur.execute(
            "SELECT lower(title) t FROM recipes GROUP BY t HAVING COUNT(*) > 1 ORDER BY t"
        ).fetchall()
    ]

    renames: list[tuple[int, str, str]] = []
    collisions: list[tuple[str, int, str, str]] = []
    for base in dup_titles:
        members = cur.execute(
            "SELECT recipe_id, title FROM recipes WHERE lower(title) = ? ORDER BY recipe_id", (base,)
        ).fetchall()
        taken = {n for n in ROMAN_NUMERALS if f"{base} {n.lower()}" in all_lower_titles}
        available = [n for n in ROMAN_NUMERALS if n not in taken]
        for i, (recipe_id, title) in enumerate(members):
            if i >= len(available):
                collisions.append((base, recipe_id, title, "ran out of available numerals"))
                continue
            new_title = f"{title} {available[i]}"
            if new_title.lower() in all_lower_titles:
                collisions.append((base, recipe_id, title, f"candidate {new_title!r} already exists"))
                continue
            renames.append((recipe_id, title, new_title))
    return renames, collisions


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--dry-run", action="store_true", help="Report the plan without writing.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    renames, collisions = build_plan(cur)

    for recipe_id, old_title, new_title in renames:
        print(f"  {recipe_id}: {old_title!r} -> {new_title!r}")
    if collisions:
        print(f"\n{len(collisions)} recipe(s) skipped -- could not find a safe numeral:")
        for base, recipe_id, title, why in collisions:
            print(f"  {recipe_id} {title!r} (base {base!r}): {why}")

    print(f"\n{'Would rename' if args.dry_run else 'Renaming'} {len(renames)} recipe(s), "
          f"{len(collisions)} skipped.")
    if args.dry_run:
        return

    for recipe_id, _, new_title in renames:
        cur.execute("UPDATE recipes SET title = ? WHERE recipe_id = ?", (new_title, recipe_id))
    conn.commit()
    conn.close()


if __name__ == "__main__":
    main()
