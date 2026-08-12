#!/usr/bin/env python3
"""
Search recipe_database.sqlite by a short list of ingredients, ranking each
recipe ingredient into one of three tiers (computed at build time by
build_recipe_db.py):

  DEFINING   the ingredient (or part of it) appears in the recipe's title,
             e.g. "garlic" in "Garlic Chicken", "black pepper" in "Black
             Pepper Beef" -- title match overrides quantity/seasoning cues.
  SEASONING  a common seasoning/condiment/base word used in a small
             quantity (tsp/pinch/dash/"to taste") and not title-defining.
  SUPPORTING everything else -- a real ingredient that's neither the dish's
             namesake nor a plain seasoning (e.g. onion, rice, cabbage).

Usage:
  python search_recipes.py "chicken breast" "broccoli"
  python search_recipes.py "chicken breast" "broccoli" --mode defining_only
  python search_recipes.py "chicken breast" "broccoli" --match exact

  # Pantry mode: given N ingredients, list every non-empty subset (2**N-1
  # "dish options") from combining all of them down to each one standalone.
  python search_recipes.py "chicken breast" "broccoli" "rice" --fridge
"""

import argparse, re, sqlite3
from collections import defaultdict
from itertools import combinations
from pathlib import Path

DB = Path("recipe_database.sqlite")

def norm(s):
    s = (s or "").lower()
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()

def term_pattern(term):
    # Word-boundary match with an optional trailing 's' on the whole phrase,
    # e.g. "broccoli" matches "broccoli"/"broccolis" but not "broccolini";
    # "chicken breast" matches "chicken breast"/"chicken breasts".
    return re.compile(r"\b" + re.escape(norm(term)) + r"s?\b")

def _load_by_recipe(conn):
    rows = conn.execute("""
        SELECT ri.recipe_id, i.normalized_name, ri.tier
        FROM recipe_ingredients ri JOIN ingredients i ON i.ingredient_id = ri.ingredient_id
    """).fetchall()
    by_recipe = defaultdict(list)
    for rid, nname, tier in rows:
        by_recipe[rid].append((nname, tier))
    return by_recipe

def search(conn, terms, mode="defining_or_supporting", match="loose", by_recipe=None):
    """
    terms: list of ingredient search phrases, e.g. ["chicken breast", "broccoli"]
    mode:  "defining_or_supporting" -- terms just need to be real ingredients
           "defining_only"          -- terms must be what the dish is built around
    match: "loose" -- recipe must contain all terms; other ingredients allowed
           "exact" -- recipe's primary ingredients (per `mode`) are ONLY these terms
    """
    allowed_tiers = {"DEFINING", "SUPPORTING"} if mode == "defining_or_supporting" else {"DEFINING"}
    patterns = [(t, term_pattern(t)) for t in terms]
    by_recipe = by_recipe if by_recipe is not None else _load_by_recipe(conn)

    hits = []
    for rid, ings in by_recipe.items():
        matched_terms = set()
        other_primary = False
        for nname, tier in ings:
            if tier not in allowed_tiers:
                continue
            hit_term = next((t for t, pat in patterns if pat.search(nname)), None)
            if hit_term:
                matched_terms.add(hit_term)
            else:
                other_primary = True
        if len(matched_terms) == len(terms) and not (match == "exact" and other_primary):
            hits.append(rid)
    return hits

def _is_blob(name):
    # A row that's actually several unsplit ingredients (parsing failure).
    # "introduction" leaking in is a strong tell: the source text's intro
    # paragraph got glued onto the ingredient list.
    return len(name) > 100 or "introduction" in name.lower()

def _extras(by_recipe, rid, patterns):
    """Names of the OTHER non-seasoning ingredients a recipe needs beyond the searched terms."""
    return [
        n for n, t in by_recipe[rid]
        if t in ("DEFINING", "SUPPORTING") and not any(p.search(n) for p in patterns)
    ]

def _has_blob(by_recipe, rid):
    return any(_is_blob(n) for n, t in by_recipe[rid])

def _tier(n_extra, blob=False):
    if blob:
        return "?", "ingredient list didn't parse cleanly -- verify manually, don't trust this count"
    if n_extra == 0:
        return "A", "feasible now"
    if n_extra <= 2:
        return "B", "secondary, needs a couple more items"
    return "C", "needs a shopping list"

def combo_report(conn, terms, mode="defining_or_supporting", top=5):
    """
    Given N pantry ingredients, enumerate every non-empty subset (2**N - 1
    "dish options") -- from using all N together down to each ingredient on
    its own -- and list the simplest matching recipes for each, labeled by
    feasibility tier:
      A  0 extra non-seasoning ingredients needed -- feasible right now
      B  1-2 extra items needed (named) -- secondary, check if you have them
      C  3+ extra items needed (named) -- treat as a shopping list, not a match
      ?  recipe has an unparsed/blob ingredient row -- extras count is unreliable
    Tier ? sorts after A/B/C: a recipe we can't trust the count for shouldn't
    outrank ones we can.
    """
    by_recipe = _load_by_recipe(conn)
    cur = conn.cursor()
    title_of = lambda rid: cur.execute("SELECT title FROM recipes WHERE recipe_id=?", (rid,)).fetchone()[0]

    subsets = []
    for k in range(len(terms), 0, -1):          # biggest combos first
        subsets.extend(combinations(terms, k))

    print(f"{len(subsets)} possible dish option(s) from {len(terms)} ingredients: {', '.join(terms)}\n")

    for i, subset in enumerate(subsets, 1):
        subset = list(subset)
        patterns = [term_pattern(t) for t in subset]
        hits = search(conn, subset, mode=mode, match="loose", by_recipe=by_recipe)

        def sort_key(rid):
            blob = _has_blob(by_recipe, rid)
            return (1 if blob else 0, len(_extras(by_recipe, rid, patterns)))
        hits.sort(key=sort_key)

        label = " + ".join(subset)
        print(f"--- Dish option {i}: {label} ({len(subset)} ingredient{'s' if len(subset) > 1 else ''}) ---")
        if not hits:
            print("  (none found)")
        for rid in hits[:top]:
            extras = _extras(by_recipe, rid, patterns)
            blob = _has_blob(by_recipe, rid)
            tier_code, tier_name = _tier(len(extras), blob=blob)
            detail = f"needs also: {', '.join(extras)}" if extras else "nothing else needed"
            print(f"  [Tier {tier_code} - {tier_name}] [{rid}] {title_of(rid)}  ({detail})")
        print()

def main():
    ap = argparse.ArgumentParser(description="Search recipes by primary ingredients.")
    ap.add_argument("ingredients", nargs="+", help="Ingredient phrases, e.g. \"chicken breast\" broccoli")
    ap.add_argument("--mode", choices=["defining_or_supporting", "defining_only"],
                     default="defining_or_supporting",
                     help="defining_or_supporting: real ingredients, not just seasoning (default). "
                          "defining_only: must be what the dish is named/built around.")
    ap.add_argument("--match", choices=["loose", "exact"], default="loose",
                     help="loose: recipe contains all ingredients (default). "
                          "exact: recipe's primary ingredients are ONLY these.")
    ap.add_argument("--fridge", action="store_true",
                     help="Pantry mode: given N ingredients, enumerate all 2**N-1 subset "
                          "combinations (\"dish option 1\", \"dish option 2\", ...) from using "
                          "all of them together down to each one standalone. Ignores --match.")
    ap.add_argument("--top", type=int, default=5, help="Results to show per dish option (--fridge only).")
    ap.add_argument("--db", default=str(DB))
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)

    if args.fridge:
        combo_report(conn, args.ingredients, mode=args.mode, top=args.top)
        conn.close()
        return

    hits = search(conn, args.ingredients, mode=args.mode, match=args.match)

    print(f"mode={args.mode} match={args.match} terms={args.ingredients}")
    print(f"{len(hits)} recipe(s) found\n")
    cur = conn.cursor()
    for rid in hits:
        title = cur.execute("SELECT title FROM recipes WHERE recipe_id=?", (rid,)).fetchone()[0]
        print(f"[{rid}] {title}")
    conn.close()

if __name__ == "__main__":
    main()
