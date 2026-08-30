#!/usr/bin/env python3
"""
Fills in total_minutes_min/total_minutes_max for recipes whose time_text carries clear, specific
duration information that the upstream source dataset's own parser missed, in recipe_database.sqlite,
in place.

## Background

Auditing every recipe with a non-null time_text but a null total_minutes_min turned up 45 rows.
Most of the corpus's own parsing conventions are visible elsewhere in already-parsed rows -- e.g.
"About 45 minutes" -> 45, "about 2 hours" -> 120, "Slightly over 5 hours" -> 300 (the qualifier is
dropped, the base number is used) -- so the 45 gaps are a parser miss on specific phrasings, not a
sign the data itself is ambiguous. Of the 45, 39 have a clear, unambiguous number to extract (using
that same "qualifier dropped, base number kept" convention, plus day/week/month conversions to
minutes and same-line sum for two explicitly sequential phases, e.g. "10 mins plus 40 mins baking"
-> 50); 6 are genuinely not parsable with any confidence and are deliberately left alone:

  - "Overnight" / "all night" / "varies": no number at all.
  - "8–10" (Egg Casserole): a bare range with no unit -- could be servings, could be minutes;
    picking either would be a guess dressed up as data.
  - "6–8 days in summer12 days in winter" (Sauerkraut II): two different numbers for two different
    conditions run together with no separator -- there's no single "the" answer here.

For open-ended ranges ("4+ hours", "60+ minutes", "1+ days"), the given number is used as
total_minutes_min (a genuine lower bound, and the only thing `matchesKnownCookTime`/`matchesFilters`
actually read for the cook-time filter -- see RecipeMetadata.kt) with total_minutes_max set equal to
it, matching how every other single-value (non-range) row in this corpus already has min == max.
For explicit ranges ("3-6 days", "2 to 3 hrs"), min/max are set to the low/high ends respectively.
Day/week/month conversions use 1 day = 1440 min, 1 week = 7 days, 1 month ~= 30 days (an
approximation, same spirit as the source's own "about" qualifiers).

Usage:
  python fix_unparsed_time_text.py --db recipe_database.sqlite
  python fix_unparsed_time_text.py --db recipe_database.sqlite --dry-run
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")

DAY = 1440

# recipe_id -> (min_minutes, max_minutes, time_text, reasoning)
CORRECTIONS: dict[int, tuple[int, int, str, str]] = {
    62: (DAY, DAY, "1 day", "1 day = 1440 min"),
    65: (DAY, DAY, "1 day", "1 day = 1440 min"),
    116: (30 * DAY, 30 * DAY, "1 month", "1 month ~= 30 days"),
    120: (DAY, DAY, "1 day", "1 day = 1440 min"),
    462: (3 * DAY, 6 * DAY, "3–6 days", "range: 3-6 days"),
    472: (240, 240, "4+ hours", "open-ended lower bound: 4 hours"),
    544: (25, 25, "25 min", "explicit"),
    638: (25, 25, "prep 5 min,  cooking 20 min", "two sequential phases, summed: 5 + 20"),
    731: (4 * DAY, 4 * DAY, "About 4 days", "qualifier dropped: 4 days"),
    810: (8, 8, "⅛ hour", "1/8 hour = 7.5 min, rounded to 8"),
    884: (4 * DAY, 5 * DAY, "4–5 days", "range: 4-5 days"),
    963: (2 * DAY, 2 * DAY, "2 days", "explicit"),
    1120: (50, 50, "10 mins plus 40 mins baking", "two sequential phases, summed: 10 + 40"),
    1153: (45, 45, "prep: 45 min", "only time given, used as total"),
    1333: (10 * DAY, 10 * DAY, "10 days", "explicit"),
    1403: (65, 65, "1 hr 5 mins", "60 + 5"),
    1408: (2 * DAY, 2 * DAY, "2 days including advance preparation", "qualifier dropped: 2 days"),
    1505: (60, 60, "1+ hours", "open-ended lower bound: 1 hour"),
    1573: (5 * DAY, 5 * DAY, "About 5 days", "qualifier dropped: 5 days"),
    1657: (15, 15, "15 min.", "explicit"),
    1740: (120, 180, "2 to 3 hrs", "range: 2-3 hours"),
    1755: (40, 40, "40", "bare number, minutes by context (same convention as Snickerdoodles below)"),
    1799: (60, 60, "About an hour", "qualifier dropped: 1 hour"),
    1830: (20 * DAY, 80 * DAY, "20–80 days", "range: 20-80 days"),
    2130: (210, 210, "3 and half hours", "3.5 * 60"),
    2442: (30, 30, "0.5 hr", "0.5 * 60"),
    2514: (DAY, DAY, "1+ days", "open-ended lower bound: 1 day"),
    2719: (4 * DAY, 4 * DAY, "4 days", "explicit"),
    2844: (60, 60, "60+ minutes", "open-ended lower bound: 60 minutes"),
    3042: (30, 30, "30", "bare number, minutes by context"),
    3043: (30, 30, "30", "bare number, minutes by context"),
    3340: (21 * DAY, 21 * DAY, "About 3 weeks", "qualifier dropped: 3 weeks = 21 days"),
    3587: (60, 60, "Slightly less than an hour", "qualifier dropped: 1 hour (same convention as 'Slightly over 5 hours' -> 300 elsewhere in this corpus)"),
    4771: (20, 20, "About 20 minutes", "qualifier dropped: 20 minutes"),
    4778: (150, 150, "About 2 1/2 hours", "qualifier dropped: 2.5 * 60"),
    4779: (150, 150, "About 2 1/2 hours", "qualifier dropped: 2.5 * 60"),
    4780: (180, 180, "About 3 hours", "qualifier dropped: 3 hours"),
    4781: (20, 20, "About 20 minutes", "qualifier dropped: 20 minutes"),
    4794: (45, 45, "About 45 minutes", "qualifier dropped: 45 minutes"),
}

# Left null deliberately -- see module doc's "genuinely not parsable" list.
DELIBERATELY_SKIPPED_IDS = {404, 665, 1008, 1033, 1215, 2899}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--dry-run", action="store_true", help="Report what would change without writing.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    changed = 0
    mismatches = []
    for recipe_id, (new_min, new_max, expected_text, reason) in CORRECTIONS.items():
        row = cur.execute(
            "SELECT title, time_text, total_minutes_min FROM recipes WHERE recipe_id = ?",
            (recipe_id,),
        ).fetchone()
        if row is None:
            print(f"  SKIP {recipe_id}: not found in this database")
            continue
        title, time_text, old_min = row
        if time_text != expected_text:
            mismatches.append((recipe_id, title, expected_text, time_text))
            continue
        if old_min is not None:
            print(f"  SKIP {recipe_id} {title!r}: total_minutes_min already set to {old_min}, not overwriting")
            continue
        print(f"  {recipe_id} {title!r}: time_text={time_text!r} -> {new_min}/{new_max} ({reason})")
        if not args.dry_run:
            cur.execute(
                "UPDATE recipes SET total_minutes_min = ?, total_minutes_max = ? WHERE recipe_id = ?",
                (new_min, new_max, recipe_id),
            )
        changed += 1

    if mismatches:
        print(f"\n{len(mismatches)} recipe(s) had unexpected time_text (not corrected -- verify by hand):")
        for recipe_id, title, expected, actual in mismatches:
            print(f"  {recipe_id} {title!r}: expected {expected!r}, found {actual!r}")

    remaining_unparsed = cur.execute(
        "SELECT COUNT(*) FROM recipes WHERE total_minutes_min IS NULL AND time_text IS NOT NULL"
    ).fetchone()[0]
    expected_remaining = len(DELIBERATELY_SKIPPED_IDS)
    print(f"\nRecipes with time_text but no total_minutes after this pass: {remaining_unparsed} "
          f"(expected {expected_remaining} deliberately-skipped rows)")

    if args.dry_run:
        print(f"Dry run -- {changed} row(s) would be filled in, {len(mismatches)} skipped as mismatched.")
        return

    conn.commit()
    conn.close()
    print(f"Filled in {changed} row(s), {len(mismatches)} skipped as mismatched.")


if __name__ == "__main__":
    main()
