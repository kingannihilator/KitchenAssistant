#!/usr/bin/env python3
"""
Fixes a real ingredient-identity bug in new_db_workable/recipes_open_v1_4.sqlite, found while
porting it into the app (see new_db_workable/HANDOVER.md for the corpus's own history).

The bug: `ingredients.canonical_name` and `recipe_ingredients.ingredient_id` were assigned by
some upstream step that is NOT reliably consistent with `recipe_ingredients.ingredient_match_name`
(the per-row cleaned ingredient name -- verified correct against raw_text in every case checked).
Concretely, two failure modes were found:

1. **Truncated canonical names**: ~205 ingredient identities have a canonical_name missing its
   leading character (e.g. "arlic clove" instead of "garlic clove", "emon" instead of "lemon").
   raw_text and ingredient_match_name for these rows are intact -- only the identity-assignment
   step corrupted the name.
2. **Merge-bucket corruption**: some ingredient_ids group rows with wildly different
   ingredient_match_name values under one bogus identity (e.g. ingredient_id 35's canonical_name
   "arge" -- itself truncated from "large" -- actually contains onion, beets, cabbage, mango,
   shrimp, haddock, tomato, tortilla, and rabbit rows that have nothing to do with each other,
   because 9 further rows have a genuine upstream parsing bug where "N large, X" got parsed as
   ingredient "large" instead of X, and other unrelated rows collided into the same bucket).
3. **Empty-identity bucket** (found later, auditing the app's bundled recipe_database.sqlite):
   `true_key()`'s original fallback chain was `ingredient_match_name -> normalized_ingredient -> ""`
   with no further fallback to raw_text, so ~328 rows with both of the first two NULL -- despite a
   perfectly clean raw_text ("beef", "water", "rice", "curry powder", ...) -- collapsed into one
   bogus identity with an empty canonical_name (ingredient_id 1 in the app's bundled corpus). Since
   28 of those rows are tagged DEFINING, this permanently capped the recipes that called for them
   below 100% even with the exact ingredient in the fridge, and rendered as an incorrect red X in
   the detail screen. Fixed by adding raw_text as a third fallback to true_key(); already
   hand-patched directly in the app's bundled recipe_database.sqlite (300 of 328 rows had exactly
   one existing ingredient row with a matching name to remap to -- see the app repo's git history
   for that one-off script), so re-running this script from scratch should be spot-checked against
   that patch rather than assumed to reproduce it exactly.

Fix strategy: rebuild `ingredients` and `recipe_ingredients.ingredient_id` from scratch, keyed by
`ingredient_match_name` (falling back to `normalized_ingredient` for the ~328 rows where
match_name is NULL) rather than trusting the existing identity assignment at all. This is the
same "rebuild the derived layer from the reliable raw field" approach the project's own
`reparse_ingredients.py` used for the old corpus. Nothing in `recipe_ingredients` other than
`ingredient_id` is touched, and raw_text is never modified.

Before the rebuild, 11 rows with a distinct, narrower bug -- "N large, X" raw_text where the
extractor kept "large" as ingredient_match_name/normalized_ingredient instead of the real food X
-- are corrected by hand (verified individually against raw_text, matched to the closest existing
ingredient_match_name group already used elsewhere in the corpus, not invented).

Usage:
  python fix_ingredient_identity_v1_4.py --db ../new_db_workable/recipes_open_v1_4.sqlite
"""

from __future__ import annotations

import argparse
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path

DB = Path("../new_db_workable/recipes_open_v1_4.sqlite")

# recipe_ingredients.id -> corrected (ingredient_match_name, normalized_ingredient). Each verified
# by hand against its own raw_text (see the module docstring's failure mode 2) and matched to an
# ingredient_match_name value already used elsewhere in the corpus where one exists, rather than
# inventing a new one-off identity.
LARGE_COMMA_BUG_FIXES = {
    98: "ripe bananas",       # '4 large, overly ripe bananas (optional)'
    3288: "ripe plantain",    # 'Large, overripe, upeeled plantain'
    10871: "egg",             # '2 large, cold eggs'
    13647: "boiling potato",  # '5 ea. (750g-1kg) large, hard boiling potatoes'
    18226: "ripe tomatoes",   # '3 large, ripe tomatoes'
    18771: "green chile peppers",  # '4 large, mild green chile peppers'
    21853: "pear",            # "1 large, ripe D'Anjou pear"
    25258: "tomato",          # '2 large, or 6 small tomatoes, cored'
    28354: "tomato",          # '1 large, ripe tomato'
    28355: "mushrooms",       # '1 large, or 2 small culinary mushrooms'
    29162: "ripe tomatoes",   # '10 large, ripe tomatoes, washed and quartered'
    # A second, unrelated single-row bug found while verifying the rebuild's leftovers: the real
    # food sits later in raw_text but the extractor kept an early size adjective instead.
    7581: "crab meat",  # '1 lb (about 0.5 kg) jumbo or regular-size fresh white lump crab meat...'
}

# recipe_ingredients.id values whose raw_text is a single bare fragment ("bar", "side", "squares",
# "desiccated") with no recoverable food identity at all -- verified individually against their
# full raw_text and sibling rows in the same recipe (see the module docstring). Same treatment as
# the "narrative/citation text with no ingredient content" rows Phase 6 removed in the corpus's
# own history (see new_db_workable/recipes_open_v1_4_README.md): deleted outright rather than
# guessed at, since nothing in raw_text says what they actually were. Confirmed beforehand that no
# recipe is left with zero ingredients once these are gone.
UNRECOVERABLE_FRAGMENT_IDS = [
    40582, 40589, 40601, 40612, 40648, 40796,  # 'bar'
    40476,  # 'desiccated'
    33109, 33407, 34951, 35993, 36414, 36587, 37019, 37716, 38446, 39400, 39515, 39819, 40662, 40719,  # 'side'
    32436, 32516, 32531, 32694, 32729, 33335, 33373, 33583, 33617, 33887, 33901, 34006, 34698, 34851,
    35637, 35923, 36177, 36265, 36794, 37010, 37026, 37044, 37060, 37069, 37087, 37124, 37131, 37140,
    37883, 38098, 38120, 38630, 38786, 40382, 40424, 40463, 40483,  # 'squares'
]


def true_key(match_name: str | None, normalized: str | None, raw_text: str | None = None) -> str:
    # raw_text is the last resort, not a peer of the other two: match_name/normalized are already
    # cleaned, but a handful of rows (~328, found auditing the app's bundled recipe_database.sqlite
    # for the "beef"/"water"/"rice"/... empty-identity bucket this fallback used to produce) have
    # BOTH fields NULL despite a perfectly clean raw_text -- collapsing them all into one bogus ""
    # identity instead of falling back one step further. raw_text is used as-is (not re-cleaned)
    # since every case found so far already was a single clean word; a messier raw_text falling
    # back this far would still be better than "", but should be spot-checked like any other
    # ingredient identity this script produces.
    key = (match_name or normalized or raw_text or "").strip().lower()
    return key


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    conn.text_factory = lambda b: b.decode("utf-8")
    cur = conn.cursor()

    before_ingredients = cur.execute("SELECT COUNT(*) FROM ingredients").fetchone()[0]

    # Step 1: hand-verified fixes for the "N large, X" parsing bug (plus the single "jumbo" case).
    fixed_rows = 0
    for ri_id, corrected in LARGE_COMMA_BUG_FIXES.items():
        cur.execute(
            "UPDATE recipe_ingredients SET ingredient_match_name = ?, normalized_ingredient = ? WHERE id = ?",
            (corrected, corrected, ri_id),
        )
        fixed_rows += cur.rowcount

    # Step 2: delete rows with no recoverable food identity at all.
    cur.executemany(
        "DELETE FROM recipe_ingredients WHERE id = ?",
        [(ri_id,) for ri_id in UNRECOVERABLE_FRAGMENT_IDS],
    )
    deleted_rows = len(UNRECOVERABLE_FRAGMENT_IDS)

    # Step 3: rebuild ingredient identity from ingredient_match_name (fallback normalized_ingredient).
    rows = cur.execute(
        "SELECT id, ingredient_match_name, normalized_ingredient, raw_text FROM recipe_ingredients"
    ).fetchall()

    key_of_row: dict[int, str] = {}
    name_votes: dict[str, Counter] = defaultdict(Counter)
    for ri_id, match_name, normalized, raw_text in rows:
        key = true_key(match_name, normalized, raw_text)
        key_of_row[ri_id] = key
        name_votes[key][(match_name or normalized or raw_text or "").strip()] += 1

    keys_sorted = sorted(name_votes.keys())
    new_id_of_key = {key: i + 1 for i, key in enumerate(keys_sorted)}

    cur.execute("DELETE FROM ingredients")
    for key in keys_sorted:
        new_id = new_id_of_key[key]
        canonical_name = name_votes[key].most_common(1)[0][0]
        cur.execute(
            "INSERT INTO ingredients(id, canonical_name) VALUES (?, ?)", (new_id, canonical_name)
        )

    cur.executemany(
        "UPDATE recipe_ingredients SET ingredient_id = ? WHERE id = ?",
        [(new_id_of_key[key_of_row[ri_id]], ri_id) for ri_id, _, _ in rows],
    )

    conn.commit()

    after_ingredients = cur.execute("SELECT COUNT(*) FROM ingredients").fetchone()[0]
    integrity = cur.execute("PRAGMA integrity_check").fetchone()[0]
    fk_violations = cur.execute("PRAGMA foreign_key_check").fetchall()

    print(f"Hand-fixed rows (N-large-comma bug + jumbo): {fixed_rows}")
    print(f"Deleted unrecoverable-fragment rows: {deleted_rows}")
    print(f"Ingredient identities: {before_ingredients} -> {after_ingredients}")
    print(f"integrity_check: {integrity}")
    print(f"foreign_key_check violations: {len(fk_violations)}")

    # Spot-check the previously-broken cases resolve correctly and consistently now.
    print()
    print("Spot check:")
    for name in ["lemon", "lime", "garlic clove", "large", "side", "desiccated coconut"]:
        c = cur.execute("SELECT COUNT(*) FROM ingredients WHERE canonical_name = ?", (name,)).fetchone()[0]
        print(f"  canonical_name={name!r}: {c} ingredient row(s)")
    leftover_junk = cur.execute(
        "SELECT canonical_name FROM ingredients WHERE canonical_name IN ('arge','emon','ime','arlic clove','large','side','bar','desiccated','squares','jumbo')"
    ).fetchall()
    print(f"  leftover known-bad canonical_names: {leftover_junk}")

    conn.close()


if __name__ == "__main__":
    main()
