#!/usr/bin/env python3
"""
Persists the hand-curated head_categories.json taxonomy into recipe_database.sqlite as
real tables:

  categories(category_id, name, parent_id)      -- self-referencing tree
  ingredients.category_id                        -- nullable FK, added to the existing table

Nothing is deleted. Blob-name ingredients and heads with no taxonomy entry simply get
category_id = NULL -- they still work via the existing string-based IngredientMatcher at
query time, they just don't get the taxonomy's cross-head matching boost yet. See
INGREDIENT_MATCHING_CONCEPTS.md and NEW_CORPUS_DATA_QUALITY.md for why.

Safe to re-run: the categories table is rebuilt fresh from head_categories.json each time
(it's entirely derived data, not something to accumulate edits into), and every
ingredient's category_id is recomputed, so re-running after editing head_categories.json
(e.g. after the deferred blob-recovery pass) just updates the assignment.

Usage:
  python apply_categories.py --db recipe_database.sqlite
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path

from extract_ingredient_heads import compute_head, BLOB_NAME_LENGTH_THRESHOLD
from resolve_ingredient_categories import resolve_entry, resolve_category

DB = Path("recipe_database.sqlite")
RULES = Path("head_categories.json")


def get_or_create_category(cur: sqlite3.Cursor, cache: dict, path: tuple[str, ...]) -> int:
    """Walks/creates the category path node by node, returning the leaf's category_id."""
    parent_id = None
    for name in path:
        key = (parent_id, name)
        if key in cache:
            parent_id = cache[key]
            continue
        cur.execute(
            "INSERT INTO categories(name, parent_id) VALUES (?, ?)", (name, parent_id)
        )
        new_id = cur.lastrowid
        cache[key] = new_id
        parent_id = new_id
    return parent_id


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(DB))
    ap.add_argument("--rules", default=str(RULES))
    args = ap.parse_args()

    head_categories = json.loads(Path(args.rules).read_text(encoding="utf-8"))
    head_categories = {k: v for k, v in head_categories.items() if not k.startswith("_")}

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    # Schema: fresh categories table each run (derived data); category_id column added
    # once and left in place (SQLite can't easily drop/re-add columns cheaply, and it's
    # harmless to leave -- values get overwritten below).
    cur.execute("DROP TABLE IF EXISTS categories")
    cur.execute("""
        CREATE TABLE categories(
            category_id INTEGER PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            parent_id INTEGER REFERENCES categories(category_id),
            UNIQUE(parent_id, name)
        )
    """)

    existing_cols = {row[1] for row in cur.execute("PRAGMA table_info(ingredients)").fetchall()}
    if "category_id" not in existing_cols:
        cur.execute("ALTER TABLE ingredients ADD COLUMN category_id INTEGER REFERENCES categories(category_id)")

    rows = cur.execute("SELECT ingredient_id, name, normalized_name FROM ingredients").fetchall()

    category_cache: dict[tuple, int] = {}
    updates = []
    categorized = 0
    blob = 0
    uncategorized = 0

    for ingredient_id, name, normalized_name in rows:
        if len(normalized_name) > BLOB_NAME_LENGTH_THRESHOLD:
            blob += 1
            updates.append((None, ingredient_id))
            continue
        head = compute_head(normalized_name)
        entry = resolve_entry(head_categories, head) if head else None
        path = resolve_category(name, entry) if entry else None
        if path is None:
            uncategorized += 1
            updates.append((None, ingredient_id))
            continue
        category_id = get_or_create_category(cur, category_cache, path)
        updates.append((category_id, ingredient_id))
        categorized += 1

    cur.executemany("UPDATE ingredients SET category_id = ? WHERE ingredient_id = ?", updates)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_ingredients_category ON ingredients(category_id)")

    conn.commit()

    total = len(rows)
    n_categories = cur.execute("SELECT COUNT(*) FROM categories").fetchone()[0]
    n_leaves = cur.execute("""
        SELECT COUNT(*) FROM categories c
        WHERE NOT EXISTS (SELECT 1 FROM categories c2 WHERE c2.parent_id = c.category_id)
    """).fetchone()[0]

    print(f"Ingredients: {total}")
    print(f"  categorized:   {categorized} ({100*categorized/total:.1f}%)")
    print(f"  blob (NULL):   {blob} ({100*blob/total:.1f}%)")
    print(f"  uncategorized (NULL, no taxonomy entry yet): {uncategorized} ({100*uncategorized/total:.1f}%)")
    print()
    print(f"Category tree nodes: {n_categories} total, {n_leaves} leaves")
    print(f"Wrote to {args.db}")

    conn.close()


if __name__ == "__main__":
    main()
