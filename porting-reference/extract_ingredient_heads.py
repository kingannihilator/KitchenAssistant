#!/usr/bin/env python3
"""
Rank the new corpus's (recipe_database.sqlite) ingredient vocabulary by algorithmic
head word and usage, producing the ordered worklist for hand-categorization into the
ingredient taxonomy (see NEW_CORPUS_DATA_QUALITY.md and INGREDIENT_MATCHING_CONCEPTS.md
in this directory for the background and rationale).

This is a *measurement/worklist* tool, not part of the app or the db build pipeline --
it doesn't write anything back to the database. Output is a ranked JSON file consumed
by the categorization pass.

The head algorithm below is a deliberate Python port of
app/src/main/java/com/example/kitchenassistant/data/IngredientMatcher.kt's word lists
and parse()/effectiveHead()/singularize() logic, kept in sync BY HAND -- there is no
shared source between the Kotlin app code and this offline script. If IngredientMatcher's
word lists change, re-sync the lists below before re-running this script, or the "head"
column will silently drift from what the app actually computes at match time.

Usage:
  python extract_ingredient_heads.py
  python extract_ingredient_heads.py --db recipe_database.sqlite --out ingredient_heads_ranked.json
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
from collections import Counter
from pathlib import Path

DB = Path("recipe_database.sqlite")
OUT = Path("ingredient_heads_ranked.json")

# A name this long is a strong signal of un-stripped raw text (quantity/unit/prep clause
# glued to the name), not a real ingredient -- see NEW_CORPUS_DATA_QUALITY.md. Excluded
# from head/usage ranking entirely; handled separately by the blob-recovery pass.
BLOB_NAME_LENGTH_THRESHOLD = 40

# --- Mirrors IngredientMatcher.kt --------------------------------------------------

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
    "whole", "large", "small", "medium", "med", "lge", "lg", "sm", "jumbo", "extra",
    "fine", "finely", "coarse", "coarsely", "thin", "thinly", "thick",
    "organic", "natural", "unsalted", "salted", "lightly", "well", "hot", "cold", "warm",
    "room", "temperature", "good", "quality", "best", "pure", "real", "plain", "regular",
    "light", "heavy", "firm", "soft", "reduced", "low", "nonfat", "free",
    "approximately", "about", "approx", "cut", "up", "new", "old", "assorted", "mixed",
    "prepared", "instant", "quick", "ready", "level", "little", "pat", "size", "sized",
    "type", "brand", "style", "divided", "needed", "taste", "desired",
}

RECIPE_ONLY_STOPWORDS = {"and", "or", "with", "without", "in", "for", "from", "into", "on", "at", "by"}

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


def parse_words(raw: str) -> list[str]:
    """Recipe-side parse (never truncates) -- normalized_name is already the recipe side."""
    words = []
    for token in _TOKEN_SEPARATOR.split(raw.lower()):
        if not token:
            continue
        if token in STOPWORDS or token in RECIPE_ONLY_STOPWORDS:
            continue
        words.append(singularize(token))
    return words


def effective_head(words: list[str]) -> str | None:
    i = len(words) - 1
    while i > 0 and words[i] in PART_WORDS:
        i -= 1
    return words[i] if words else None


def compute_head(normalized_name: str) -> str | None:
    return effective_head(parse_words(normalized_name))


# --- Main -----------------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--examples-per-head", type=int, default=10,
                     help="How many example ingredient names to keep per head, for categorization context.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    # ingredient_id -> (normalized_name, name, usage count in recipe_ingredients)
    usage = dict(cur.execute(
        "SELECT ingredient_id, COUNT(*) FROM recipe_ingredients GROUP BY ingredient_id"
    ).fetchall())

    rows = cur.execute("SELECT ingredient_id, name, normalized_name FROM ingredients").fetchall()

    total_ingredients = len(rows)
    blob_ids = set()
    head_usage = Counter()
    head_distinct_names = Counter()
    # head -> list of (usage_count, name) so we can keep the most-used examples per head
    head_examples: dict[str, list[tuple[int, str]]] = {}

    for ingredient_id, name, normalized_name in rows:
        n = usage.get(ingredient_id, 0)
        if len(normalized_name) > BLOB_NAME_LENGTH_THRESHOLD:
            blob_ids.add(ingredient_id)
            continue
        head = compute_head(normalized_name)
        if head is None:
            continue
        head_usage[head] += n
        head_distinct_names[head] += 1
        head_examples.setdefault(head, []).append((n, name))

    total_rows = sum(usage.values())
    blob_rows = sum(usage.get(i, 0) for i in blob_ids)
    clean_rows = total_rows - blob_rows

    worklist = []
    for head, count in head_usage.most_common():
        examples = sorted(head_examples[head], key=lambda t: -t[0])[: args.examples_per_head]
        worklist.append({
            "head": head,
            "usage_count": count,
            "distinct_names": head_distinct_names[head],
            "examples": [name for _, name in examples],
            # Filled in by the categorization pass; null means "not yet categorized".
            "category_path": None,
        })

    Path(args.out).write_text(json.dumps(worklist, indent=2, ensure_ascii=False), encoding="utf-8")

    # Coverage summary, so re-running after a word-list change shows the new numbers immediately.
    print(f"Total ingredients: {total_ingredients}  (blob/excluded: {len(blob_ids)}, "
          f"{100 * len(blob_ids) / total_ingredients:.1f}%)")
    print(f"Total recipe_ingredients rows: {total_rows}  (in blob ingredients: {blob_rows}, "
          f"{100 * blob_rows / total_rows:.1f}%)")
    print(f"Distinct heads: {len(head_usage)}")
    print()
    cumulative = 0
    for n in (50, 100, 200, 300, 500, 1000):
        cumulative = sum(c for _, c in head_usage.most_common(n))
        print(f"  top {n:>4} heads cover {100 * cumulative / clean_rows:5.1f}% of clean rows")
    print()
    print(f"Wrote {len(worklist)} ranked heads to {args.out}")


if __name__ == "__main__":
    main()
