#!/usr/bin/env python3
"""
Applies the hand-curated head_categories.json taxonomy to every ingredient in the new
corpus (recipe_database.sqlite), resolving each to a category path (or none, if its
head isn't categorized yet). Read-only / measurement tool -- writes nothing back to the
database. Used to check coverage after each categorization batch and to preview the
resulting category tree before the persist step.

Usage:
  python resolve_ingredient_categories.py
  python resolve_ingredient_categories.py --db recipe_database.sqlite --rules head_categories.json
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from collections import Counter
from pathlib import Path

# Mirrors extract_ingredient_heads.py -- see that file for why these lists exist and
# the caveat about keeping them hand-synced with IngredientMatcher.kt.
from extract_ingredient_heads import compute_head, BLOB_NAME_LENGTH_THRESHOLD

DB = Path("recipe_database.sqlite")
RULES = Path("head_categories.json")


def resolve_entry(head_categories: dict, head: str, _seen: frozenset = frozenset()) -> dict | None:
    """Looks up head's taxonomy entry, following alias_of chains (cycle-safe)."""
    entry = head_categories.get(head)
    if entry is None or head in _seen:
        return None
    alias_target = entry.get("alias_of")
    if alias_target:
        return resolve_entry(head_categories, alias_target, _seen | {head})
    return entry


def resolve_category(name: str, rules_entry: dict) -> tuple[str, ...] | None:
    if rules_entry.get("excluded"):
        return None
    lname = name.lower()
    for rule in rules_entry.get("rules", []):
        if any(kw in lname for kw in rule["contains_any"]):
            return tuple(rule["category_path"])
    default = rules_entry.get("default_category_path") or rules_entry.get("category_path")
    return tuple(default) if default else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--rules", default=str(RULES))
    ap.add_argument("--preview", type=int, default=0, help="Print N example (name -> category) resolutions.")
    args = ap.parse_args()

    head_categories = json.loads(Path(args.rules).read_text(encoding="utf-8"))
    head_categories = {k: v for k, v in head_categories.items() if not k.startswith("_")}

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()
    usage = dict(cur.execute("SELECT ingredient_id, COUNT(*) FROM recipe_ingredients GROUP BY ingredient_id").fetchall())
    rows = cur.execute("SELECT ingredient_id, name, normalized_name FROM ingredients").fetchall()

    total_rows = sum(usage.values())
    blob_rows = 0
    categorized_rows = 0
    uncategorized_head_usage = Counter()
    category_leaf_usage = Counter()
    examples_shown = 0
    preview_lines = []

    for ingredient_id, name, normalized_name in rows:
        n = usage.get(ingredient_id, 0)
        if len(normalized_name) > BLOB_NAME_LENGTH_THRESHOLD:
            blob_rows += n
            continue
        head = compute_head(normalized_name)
        if head is None:
            continue
        entry = resolve_entry(head_categories, head)
        if entry is None:
            uncategorized_head_usage[head] += n
            continue
        path = resolve_category(name, entry)
        if path is None:
            # Explicitly excluded (parser artifact) -- not an error, just not counted as categorized.
            continue
        categorized_rows += n
        category_leaf_usage["/".join(path)] += n
        if examples_shown < args.preview:
            preview_lines.append(f"  {name!r:60s} -> {'/'.join(path)}")
            examples_shown += 1

    print(f"Total recipe_ingredients rows: {total_rows}")
    print(f"  blob (excluded from categorization entirely): {blob_rows} ({100*blob_rows/total_rows:.1f}%)")
    print(f"  categorized: {categorized_rows} ({100*categorized_rows/total_rows:.1f}%)")
    print(f"  clean but not-yet-categorized (head has no taxonomy entry): "
          f"{sum(uncategorized_head_usage.values())} ({100*sum(uncategorized_head_usage.values())/total_rows:.1f}%)")
    print()
    print(f"Distinct category leaves so far: {len(category_leaf_usage)}")
    print()
    print("Top 20 uncategorized heads by usage (candidates for the next batch):")
    for head, n in uncategorized_head_usage.most_common(20):
        print(f"  {head:20s} {n}")

    if preview_lines:
        print()
        print(f"Preview ({len(preview_lines)} resolutions):")
        for line in preview_lines:
            print(line)


if __name__ == "__main__":
    main()
