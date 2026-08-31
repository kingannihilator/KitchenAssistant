"""Removes the subset of ingredients.db's OpenFoodFacts-baseline (`en:`-prefixed) rows that are
food-science/supplement chemical names (mineral salts, vitamin forms, additives, enzymes) AND
don't satisfy any recipe in the bundled corpus -- confirmed via clean_unused_ingredients.py's
IngredientMatcher.kt port, not guessed from the name alone.

## Why this is narrower than clean_unused_ingredients.py

That script flags 2607/8936 rows as unused in total, but most of those are NOT chemical names --
they're real foods this specific 16,090-recipe corpus just doesn't happen to call for (whiting,
armagnac, reindeer meat), or corpus-mined entries with a broken display string (a separate, known
bug -- see that script's docstring), or "near miss" real ingredients that only fail on a head-word
mismatch (kiwi vs. corpus "kiwifruit", portobello vs. corpus "portobello mushroom") and are NOT
redundant. This script intersects "unused" with a chemical/additive name pattern so only the
former gets removed -- see the session this was written in for the three-way breakdown
(116 chemical / 118 near-miss / 1504 genuinely-unrepresented) that justified stopping here.

Usage:
    python porting-reference/remove_chemical_additive_ingredients.py            # dry run
    python porting-reference/remove_chemical_additive_ingredients.py --apply    # actually deletes
"""

from __future__ import annotations

import argparse
import re
import sqlite3
from pathlib import Path

from clean_unused_ingredients import INGREDIENTS_DB, load_matchable_recipe_terms, matches, parse_fridge

# Mineral-salt forms (chloride/citrate/gluconate/carbonate/sulfate/phosphate/oxide/...), vitamin
# forms (tocopheryl/retinyl/pyridoxine/thiamin/biotin/folate/...), and food-science/additive terms
# (monophosphate, EDTA, enzymes, antioxidant/stabilizer/emulsifier/preservative) -- built and
# reviewed against the actual shipped ingredients.db content, not a generic chemistry wordlist.
CHEMICAL_NAME_PATTERN = re.compile(
    r"tocopheryl|palmitate|sulfate|sulphate|chloride|hydrochloride|methylsulfonyl|acetate|"
    r"succinate|bisglycinate|picolinate|\boxide\b|carbonate|gluconate|citrate|phosphate|"
    r"selenite|selenate|iodide|iodate|pantothenate|cyanocobalamin|riboflavin|pyridoxine|"
    r"pyridoxamine|thiamin|tocopherol|retinyl|\bfolate\b|biotin|niacinamide|glycinate|"
    r"hydroxide|^d-alpha|^dl-alpha|polyethylene glycol|ascorbyl|monophosphate|edta|"
    r"vitamin |dexpanthenol|hydroxocobalamin|lactobacillus|galactosidase|cellulase|"
    r"antioxidant|stabilizer|stabiliser|emulsifier|preservative"
)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="Actually delete matched rows (default: dry run).")
    args = ap.parse_args()

    print("Loading matchable recipe-corpus ingredients...")
    by_head = load_matchable_recipe_terms()

    con = sqlite3.connect(INGREDIENTS_DB)
    cur = con.cursor()
    rows = cur.execute("SELECT id, name_en FROM ingredients WHERE id LIKE 'en:%'").fetchall()

    to_delete: list[tuple[str, str]] = []
    for entry_id, name_en in rows:
        fridge_term = parse_fridge(name_en)
        used = fridge_term.head is not None and any(
            matches(fridge_term, candidate) for candidate in by_head.get(fridge_term.head, [])
        )
        if used:
            continue
        if CHEMICAL_NAME_PATTERN.search(name_en.lower()):
            to_delete.append((entry_id, name_en))

    print(f"\n{len(to_delete)} chemical/additive names to remove:\n")
    for entry_id, name in sorted(to_delete, key=lambda p: p[1]):
        print(f"  {name}")

    if not args.apply:
        print(f"\nDry run only ({len(to_delete)} rows would be deleted) -- pass --apply to actually delete.")
        con.close()
        return

    cur.executemany("DELETE FROM ingredients WHERE id = ?", [(eid,) for eid, _ in to_delete])
    con.commit()
    remaining = cur.execute("SELECT COUNT(*) FROM ingredients").fetchone()[0]
    con.close()
    print(f"\nDeleted {len(to_delete)} rows. ingredients.db now has {remaining} entries.")


if __name__ == "__main__":
    main()
