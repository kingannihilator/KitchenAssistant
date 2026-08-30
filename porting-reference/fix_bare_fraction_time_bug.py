#!/usr/bin/env python3
"""
Corrects total_minutes_min/total_minutes_max for recipes whose time_text contains a bare Unicode
vulgar-fraction character ("½", "¾") -- i.e. one with no digit directly in front of it -- in
recipe_database.sqlite, in place.

## The bug

Traced upstream, into the source dataset itself (new_db_workable/recipes_open_v1_4.sqlite) --
build_recipe_db_v1_4.py only carries these two columns straight through, it doesn't compute them.
Whatever generated that corpus mishandled a *bare* fraction glyph: "1½ hours" (digit directly
adjacent) parses correctly to 90, but a bare "½ hour" -- no adjacent digit -- was recorded as a
flat 300 minutes (5 hours) instead of 30, and the one bare "¾ hour" case ("Thin Fish Fillets") was
recorded as 4500 minutes (75 hours) instead of 45. Confirmed by testing every recipe with a "¼"/
"½"/"¾" in its time_text directly: every digit-adjacent case (e.g. "1½ hours", "1¼ hours") already
matches its plain-English meaning; only the bare cases are wrong, and wrong by that same fixed
300/4500 regardless of what else is in the string -- e.g. "3 ½ hours" (should be 210) also lands on
300, and "Prep: 15 minutesBaking: ½ hour" (should total 45) lands on 315 = 15 + 300, showing the
+300 contribution is added on top of whatever else parses correctly.

## Why this is a hand-curated list, not a general parser

Only 31 recipes are affected -- small enough to read and correct by hand with real confidence,
rather than writing (and trusting) a general fraction/duration parser for a one-off, upstream-only
bug. Most are the simple case (time_text is *only* "½ hour" or "¾ hour" -- correct value is exactly
30 or 45). A handful are compound phrases needing a judgment call:
  - "Prep: ½ hour Total: 1 hour" / "Prep: ½ hour Total: 45 minutes": the explicit "Total:" label
    supersedes -- prep is a subset of total, not additional time on top of it.
  - "½ hour prep, ½ hour to bake" / "Prep: ½ hour Baking: 1 hour" / "Prep: 15 minutes Baking: ½
    hour": no "Total:" label: these are two distinct sequential phases, so they're summed.
  - "½ hour, plus a few minutes prep": "a few minutes" is too vague to add a specific number to,
    so the base 30 is kept as-is.

Usage:
  python fix_bare_fraction_time_bug.py --db recipe_database.sqlite
  python fix_bare_fraction_time_bug.py --db recipe_database.sqlite --dry-run
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")

# recipe_id -> (corrected_minutes, time_text, reasoning)
CORRECTIONS: dict[int, tuple[int, str, str]] = {
    169: (30, "½ hour", "bare fraction"),
    171: (30, "½ hour", "bare fraction"),
    198: (30, "½ hour", "bare fraction"),
    207: (90, "1 ½ hours", "1.5 * 60"),
    268: (210, "3 ½ hours", "3.5 * 60"),
    284: (30, "½ hour", "bare fraction"),
    336: (30, "½ hour", "bare fraction"),
    410: (30, "½ hour, plus a few minutes prep", "base only; 'a few minutes' too vague to add"),
    414: (60, "Prep: ½ hourTotal: 1 hour", "explicit Total supersedes prep"),
    415: (30, "½ hour", "bare fraction"),
    507: (30, "½ hour", "bare fraction"),
    654: (45, "Prep: ½ hourTotal: 45 minutes", "explicit Total supersedes prep"),
    705: (30, "½ hour", "bare fraction"),
    740: (30, "½ hour", "bare fraction"),
    1015: (60, "½ hour prep, ½ hour to bake", "two distinct phases, summed: 30 + 30"),
    1193: (30, "½ hour", "bare fraction"),
    1784: (30, "½ hour", "bare fraction"),
    2028: (30, "½ hour", "bare fraction"),
    2088: (30, "½ hour", "bare fraction"),
    2433: (30, "½ hour", "bare fraction"),
    2459: (30, "½ hour", "bare fraction"),
    2473: (30, "½ hour", "bare fraction"),
    2483: (30, "½ hour", "bare fraction"),
    2600: (90, "Prep: ½ hourBaking: 1 hour", "two distinct phases, summed: 30 + 60"),
    2745: (45, "Prep: 15 minutesBaking: ½ hour", "two distinct phases, summed: 15 + 30"),
    3123: (30, "½ hour", "bare fraction"),
    3276: (30, "½ hour", "bare fraction"),
    3382: (45, "¾ hour", "bare fraction"),
    3415: (30, "½ hour", "bare fraction"),
    3417: (30, "½ hour", "bare fraction"),
    3536: (30, "½ hour", "bare fraction"),
}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--dry-run", action="store_true", help="Report what would change without writing.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    changed = 0
    mismatches = []
    for recipe_id, (corrected, expected_text, reason) in CORRECTIONS.items():
        row = cur.execute(
            "SELECT title, time_text, total_minutes_min, total_minutes_max FROM recipes WHERE recipe_id = ?",
            (recipe_id,),
        ).fetchone()
        if row is None:
            print(f"  SKIP {recipe_id}: not found in this database")
            continue
        title, time_text, old_min, old_max = row
        if time_text != expected_text:
            mismatches.append((recipe_id, title, expected_text, time_text))
            continue
        print(f"  {recipe_id} {title!r}: time_text={time_text!r} minutes {old_min}/{old_max} -> {corrected} ({reason})")
        if not args.dry_run:
            cur.execute(
                "UPDATE recipes SET total_minutes_min = ?, total_minutes_max = ? WHERE recipe_id = ?",
                (corrected, corrected, recipe_id),
            )
        changed += 1

    if mismatches:
        print(f"\n{len(mismatches)} recipe(s) had unexpected time_text (not corrected -- verify by hand):")
        for recipe_id, title, expected, actual in mismatches:
            print(f"  {recipe_id} {title!r}: expected {expected!r}, found {actual!r}")

    if args.dry_run:
        print(f"\nDry run -- {changed} row(s) would be corrected, {len(mismatches)} skipped as mismatched.")
        return

    conn.commit()
    conn.close()
    print(f"\nCorrected {changed} row(s), {len(mismatches)} skipped as mismatched.")


if __name__ == "__main__":
    main()
