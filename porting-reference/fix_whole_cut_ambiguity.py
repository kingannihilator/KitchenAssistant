#!/usr/bin/env python3
"""
Strips the word "whole" from a handful of ingredient normalized_name values where it describes a
*cut* ("whole chicken wings" -- uncut wing pieces, as opposed to wingettes/drumettes) rather than
the *animal* ("whole chicken" -- an uncut bird), in recipe_database.sqlite, in place.

## The bug

IngredientMatcher.kt's word-set matching has no concept of word order, so it can't tell these two
senses of "whole" apart: fridge "whole chicken" and recipe "whole chicken wings" share the literal
words {whole, chicken}, and since the recipe's word set is a superset, the subset rule marks it a
direct, exact match -- reintroducing the exact bug `whole` was removed from STOPWORDS to fix (see
IngredientMatcher.kt's STOPWORDS doc), just narrowed to the handful of corpus rows that happen to
repeat the word "whole" for the cut itself. Reported via "Hot Wings" (ingredient_id 7960, "whole
chicken wings") appearing as a direct/exact match for a fridge of just "whole chicken".

## Why a database fix instead of a parser change

A general fix needs word-order awareness (e.g. "is `whole` immediately followed, anywhere in the
name, by a cut word like thigh/wing/breast?") -- real added complexity and a new rule to test in
IngredientMatcher.kt, a function IngredientMatcherTest.kt already treats as load-bearing. Checked
how many corpus rows are actually affected first: only 5 ingredient identities, each used in 1-2
recipes. That's small and stable enough to correct by hand at the data level instead -- no parser
change, no risk to the general matching engine, same reasoning as the other hand-curated fixes in
this directory (fix_bare_fraction_time_bug.py, etc.).

Every other "whole <animal>" identity in the corpus (`-pound whole turkey`, `whole broiler/fryer
chicken`, `whole duck or goose`, `whole free-range chicken`, `whole roasted chicken`, `whole tender
chicken`, and the two "whole chicken or chicken parts" alternatives) genuinely describes a whole,
uncut bird and is left untouched -- fridge "whole chicken" is *supposed* to match those directly.

Only `normalized_name` (matching) is changed, never `original_text` (what's actually displayed on
the recipe detail screen, e.g. "12 whole chicken wings") -- this is purely a matching-identity
correction, invisible in the UI.

Usage:
  python fix_whole_cut_ambiguity.py --db recipe_database.sqlite
  python fix_whole_cut_ambiguity.py --db recipe_database.sqlite --dry-run
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

DB = Path("recipe_database.sqlite")

# ingredient_id -> (expected_current_name, corrected_name)
CORRECTIONS: dict[int, tuple[str, str]] = {
    1282: ("chicken wingettes or 18 whole chicken wings", "chicken wingettes or 18 chicken wings"),
    7945: ("whole bone-in chicken thighs", "bone-in chicken thighs"),
    7960: ("whole chicken wings", "chicken wings"),
    8034: ("whole pieces of smoked ham hock or smoked turkey", "pieces of smoked ham hock or smoked turkey"),
    8048: ("whole skin-on bone-in chicken breast", "skin-on bone-in chicken breast"),
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
    for ingredient_id, (expected, corrected) in CORRECTIONS.items():
        row = cur.execute(
            "SELECT normalized_name FROM ingredients WHERE ingredient_id = ?", (ingredient_id,)
        ).fetchone()
        if row is None:
            print(f"  SKIP {ingredient_id}: not found in this database")
            continue
        current = row[0]
        if current != expected:
            mismatches.append((ingredient_id, expected, current))
            continue
        print(f"  {ingredient_id}: {current!r} -> {corrected!r}")
        if not args.dry_run:
            cur.execute(
                "UPDATE ingredients SET normalized_name = ? WHERE ingredient_id = ?",
                (corrected, ingredient_id),
            )
        changed += 1

    if mismatches:
        print(f"\n{len(mismatches)} ingredient(s) had unexpected current text (not corrected -- verify by hand):")
        for ingredient_id, expected, current in mismatches:
            print(f"  {ingredient_id}: expected {expected!r}, found {current!r}")

    if args.dry_run:
        print(f"\nDry run -- {changed} row(s) would be corrected, {len(mismatches)} skipped as mismatched.")
        return

    conn.commit()
    conn.close()
    print(f"\nCorrected {changed} row(s), {len(mismatches)} skipped as mismatched.")


if __name__ == "__main__":
    main()
