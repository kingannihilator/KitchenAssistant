"""Removes ingredients.db rows that can never satisfy any recipe in the bundled corpus, using a
faithful Python port of IngredientMatcher.kt's direct word-matching rule (see that file's
docstring for the full rule -- equal heads, one word set containing the other, extra words not
BLOCK_MODIFIERS).

## Why direct matching alone is the correct and complete test

The app also expands matches through a category taxonomy (NewIngredientIndex.kt) -- e.g. fridge
"beef" reaching recipe "ribeye" with no shared word, because both are filed under Meat/Beef.
That expansion only ever adds *other*, already-differently-matched recipe ingredients on top of
a fridge item that already matched something directly; it can never make an otherwise-unmatched
fridge term itself become useful. So whether a given ingredients.db row is ever useful reduces
to: does IngredientMatcher.parseFridge(row) directly match IngredientMatcher.parseRecipe(x) for
at least one matchable recipe-corpus ingredient x? No category logic needs porting here.

"Matchable" mirrors NewRecipeDao.getMatchableIngredients: recipe-corpus ingredient rows longer
than BLOB_NAME_LENGTH_THRESHOLD (40 chars, un-stripped raw-text rows -- see
NEW_CORPUS_DATA_QUALITY.md) are excluded, same as what the app itself queries against.

Usage:
    python porting-reference/clean_unused_ingredients.py            # dry run, reports only
    python porting-reference/clean_unused_ingredients.py --apply    # actually deletes
    python porting-reference/clean_unused_ingredients.py --list-out unused.txt   # dump full list
"""

from __future__ import annotations

import argparse
import re
import sqlite3
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RECIPE_DB = REPO_ROOT / "app" / "src" / "main" / "assets" / "database" / "recipe_database.sqlite"
INGREDIENTS_DB = REPO_ROOT / "app" / "src" / "main" / "assets" / "ingredients.db"

BLOB_NAME_LENGTH_THRESHOLD = 40  # mirrors RecipeViewModel.BLOB_NAME_LENGTH_THRESHOLD_NEW

# --- Verbatim port of IngredientMatcher.kt's word lists (kept in sync BY HAND -- see that file) --

PART_WORDS = {
    "breast", "thigh", "wing", "leg", "drumstick", "liver", "fillet", "filet", "cutlet",
    "meat", "part", "half", "piece", "slice", "clove", "bulb", "stalk", "sprig", "leaf",
    "kernel", "floret", "chop", "loin", "rib", "shank", "tenderloin", "tip", "top", "stem",
    "root", "skin", "bone", "heart", "gizzard", "neck", "white", "yolk",
}

STOPWORDS = {
    "of", "the", "a", "an", "to", "all", "purpose", "w", "wo", "x", "c", "t",
    "fresh", "freshly", "frozen", "dried", "dry", "raw", "cooked", "uncooked", "chopped",
    "minced", "sliced", "diced", "grated", "shredded", "melted", "softened", "beaten",
    "peeled", "pared", "seeded", "cored", "trimmed", "rinsed", "drained", "packed", "canned",
    "large", "small", "medium", "med", "lge", "lg", "sm", "jumbo", "extra",
    "fine", "finely", "coarse", "coarsely", "thin", "thinly", "thick",
    "organic", "natural", "unsalted", "salted", "lightly", "well", "hot", "cold", "warm",
    "room", "temperature", "good", "quality", "best", "pure", "real", "plain", "regular",
    "light", "heavy", "firm", "soft", "reduced", "low", "nonfat", "free",
    "approximately", "about", "approx", "cut", "up", "new", "old", "assorted", "mixed",
    "prepared", "instant", "quick", "ready", "level", "little", "pat", "size", "sized",
    "type", "brand", "style", "divided", "needed", "taste", "desired",
    "removed", "quartered", "halved", "thawed", "crushed", "reserved", "crumbled",
    "toasted", "deveined", "chilled", "juiced", "undrained", "mashed", "warmed",
    "pitted", "sifted", "flaked", "cooled", "separated", "zested", "scrubbed",
    "unpeeled", "discarded", "cleaned", "stemmed", "pressed", "heated", "undiluted",
    "baked", "unwrapped", "blanched", "defrosted", "slivered", "boiled", "pureed", "squeezed",
}

RECIPE_ONLY_STOPWORDS = {"and", "or", "with", "without", "in", "for", "from", "into", "on", "at", "by"}

FRIDGE_CUT = {
    "and", "or", "with", "without", "including", "include", "from", "made", "plus", "such",
    "like", "etc", "in", "for", "optional", "use", "used",
}

BLOCK_MODIFIERS = {
    "peanut", "almond", "cashew", "hazelnut", "walnut", "pecan", "coconut", "soy", "soya",
    "oat", "hemp", "cocoa", "shea", "apple", "sour", "cream", "whipping", "whipped", "ice",
    "condensed", "evaporated", "spring", "clotted", "buttermilk", "tartar", "powdered",
    "malted",
}

QUANTITY_PREFIXES = [
    "tablespoons", "tablespoon", "tbsp", "tbl", "teaspoons", "teaspoon", "tsp",
    "cups", "cup", "cans", "can", "grams", "gram", "gr", "g", "c", "ounces", "ounce", "oz",
    "pounds", "pound", "lbs", "lb", "packages", "package",
    "containers", "container", "jars", "jar", "tubs", "tub",
]

PREFIX_FUSION_EXCEPTIONS = {"goat", "coat"}

IRREGULAR_PLURALS = {
    "leaves": "leaf", "loaves": "loaf", "halves": "half", "calves": "calf",
    "knives": "knife", "geese": "goose", "shelves": "shelf",
}

NEVER_STEM = {"molasses", "asparagus", "hummus", "couscous", "watercress", "swiss", "anise", "haggis", "series"}

_TOKEN_SEPARATOR = re.compile(r"[^a-z]+")


def singularize(word: str) -> str:
    if len(word) <= 3:
        return word
    if word in IRREGULAR_PLURALS:
        return IRREGULAR_PLURALS[word]
    if word in NEVER_STEM:
        return word
    if word.endswith("ies"):
        return word[:-3] + "y"
    if word.endswith("oes"):
        return word[:-2]
    if word.endswith(("ches", "shes", "xes", "zes")):
        return word[:-2]
    if word.endswith(("ss", "us", "is")):
        return word
    if word.endswith("s"):
        return word[:-1]
    return word


def effective_head(words: list[str]) -> str | None:
    i = len(words) - 1
    while i > 0 and words[i] in PART_WORDS:
        i -= 1
    return words[i] if 0 <= i < len(words) else None


class Term:
    __slots__ = ("words", "head")

    def __init__(self, words: frozenset[str], head: str | None):
        self.words = words
        self.head = head


def _parse(raw: str, cut: bool) -> Term:
    ordered: list[str] = []
    for token in _TOKEN_SEPARATOR.split(raw.lower()):
        if not token:
            continue
        if cut and ordered and token in FRIDGE_CUT:
            break
        if token in STOPWORDS:
            continue
        if not cut and token in RECIPE_ONLY_STOPWORDS:
            continue
        ordered.append(singularize(token))
    return Term(frozenset(ordered), effective_head(ordered))


def parse_fridge(name: str) -> Term:
    return _parse(name, cut=True)


def parse_recipe(canonical: str) -> Term:
    return _parse(canonical, cut=False)


def _is_blocked_modifier(word: str) -> bool:
    if word in BLOCK_MODIFIERS:
        return True
    if word in PREFIX_FUSION_EXCEPTIONS:
        return False
    for prefix in QUANTITY_PREFIXES:
        if len(word) > len(prefix) and word.startswith(prefix) and word[len(prefix):] in BLOCK_MODIFIERS:
            return True
    return False


def matches(fridge: Term, recipe: Term) -> bool:
    if fridge.head is None or recipe.head is None:
        return False
    if fridge.head != recipe.head:
        return False
    if recipe.words >= fridge.words:
        extra = recipe.words - fridge.words
    elif fridge.words >= recipe.words:
        extra = fridge.words - recipe.words
    else:
        return False
    return not any(_is_blocked_modifier(w) for w in extra)


# --- Script ---------------------------------------------------------------------------------

def load_matchable_recipe_terms() -> dict[str, list[Term]]:
    con = sqlite3.connect(RECIPE_DB)
    cur = con.cursor()
    rows = cur.execute(
        "SELECT DISTINCT normalized_name FROM ingredients WHERE LENGTH(normalized_name) <= ?",
        (BLOB_NAME_LENGTH_THRESHOLD,),
    ).fetchall()
    con.close()

    by_head: dict[str, list[Term]] = {}
    for (normalized_name,) in rows:
        term = parse_recipe(normalized_name)
        if term.head is None:
            continue
        by_head.setdefault(term.head, []).append(term)
    return by_head


def is_used(fridge_term: Term, by_head: dict[str, list[Term]]) -> bool:
    if fridge_term.head is None:
        return False
    candidates = by_head.get(fridge_term.head, [])
    return any(matches(fridge_term, candidate) for candidate in candidates)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="Actually delete unused rows (default: dry run).")
    ap.add_argument("--list-out", type=Path, default=None, help="Write the full list of unused names to this file.")
    args = ap.parse_args()

    print("Loading matchable recipe-corpus ingredients...")
    by_head = load_matchable_recipe_terms()
    total_recipe_terms = sum(len(v) for v in by_head.values())
    print(f"  {total_recipe_terms} matchable recipe ingredients across {len(by_head)} head buckets")

    con = sqlite3.connect(INGREDIENTS_DB)
    cur = con.cursor()
    rows = cur.execute("SELECT id, name_en FROM ingredients").fetchall()
    print(f"\nChecking {len(rows)} ingredients.db entries against the recipe corpus...")

    unused: list[tuple[str, str]] = []
    for entry_id, name_en in rows:
        fridge_term = parse_fridge(name_en)
        if not is_used(fridge_term, by_head):
            unused.append((entry_id, name_en))

    print(f"\nUnused (match nothing in the recipe corpus): {len(unused)} / {len(rows)}")
    print(f"Kept (satisfy at least one recipe ingredient): {len(rows) - len(unused)} / {len(rows)}")

    if args.list_out:
        args.list_out.write_text(
            "\n".join(f"{eid}\t{name}" for eid, name in sorted(unused, key=lambda p: p[1])) + "\n",
            encoding="utf-8",
        )
        print(f"\nFull unused list written to {args.list_out}")
    else:
        print("\nSample of unused entries:")
        for entry_id, name in sorted(unused, key=lambda p: p[1])[:40]:
            print(f"  {name!r}")

    if not args.apply:
        print("\nDry run only -- pass --apply to actually delete these rows.")
        con.close()
        return

    cur.executemany("DELETE FROM ingredients WHERE id = ?", [(eid,) for eid, _ in unused])
    con.commit()
    remaining = cur.execute("SELECT COUNT(*) FROM ingredients").fetchone()[0]
    con.close()
    print(f"\nDeleted {len(unused)} rows. ingredients.db now has {remaining} entries.")


if __name__ == "__main__":
    main()
