"""Rebuilds app/src/main/assets/ingredients.db as a MERGE of the existing OpenFoodFacts-derived
taxonomy with a cleaned, deduplicated vocabulary extracted from the bundled recipe corpus
(recipe_database.sqlite).

## Why

ingredients.db is the fridge-add autocomplete field's entire universe -- the Add button is gated
on an exact (case-insensitive) match against it, so an ingredient that isn't a row here literally
cannot be added. It has been a pre-built OpenFoodFacts-derived binary since this repo's first
commit, with no build script ever checked in (confirmed by searching the full git history), so
gaps in it (see patch_missing_ingredients.py -- "pork steak" had no bare entry at all) could only
be found by hand, one at a time.

This script instead mines the recipe corpus itself for what real recipes actually call ingredients,
so the autocomplete vocabulary tracks what the app can actually do something with -- while keeping
every existing OpenFoodFacts entry, so general food-taxonomy coverage the recipe corpus doesn't
happen to include isn't lost. (A full replace was considered and rejected: the corpus is a specific
16k-recipe sample, and an ingredient absent from it isn't necessarily uncommon in the real world,
just unrepresented in this dataset.)

This is a companion to app/.../data/IngredientPopularityIndex.kt and IngredientMatcher.kt, not a
replacement for either: the app separately tells a user, live, when an addable ingredient (from
either source) matches nothing in the recipe corpus at all -- see IngredientViewModel's
`recipeMatchHint`. That check reuses the exact matching logic real search uses (NewIngredientIndex),
so it can't disagree with what search actually finds. This script's frequency threshold only
controls what gets OFFERED as a suggestion, not what's addable (raise/lower it freely without
touching that separate correctness guarantee) or what gets the "not in our recipes" hint.

## Cleaning approach

recipe_database.sqlite's `ingredients.normalized_name` is not display-ready -- e.g. "medium zucchini
cut into 1 4 inch slices", "asparagus tough ends trimmed". This reuses IngredientMatcher's own
STOPWORDS/PART_WORDS/singularize logic (kept in sync BY HAND with the Kotlin source, same caveat as
extract_ingredient_heads.py) to strip preparation/quantity noise down to content words, plus an
additional MEASUREMENT_STOPWORDS list this script needs but the app doesn't (unit words like
"inch"/"tablespoon" only ever leak into this corpus's names, never into what a user types when
adding a fridge item, so IngredientMatcher itself has no reason to know them).

Distinct cleaned word-sets are grouped (e.g. "small carrots peeled and grated" and "carrots" both
reduce to {carrot}), and the *shortest* raw example in each group is used as the display string --
picking a real, naturally-phrased ingredient name from the corpus rather than reconstructing one
from the cleaned words, which risks producing an awkward phrase no recipe actually uses. Groups are
filtered to a minimum total corpus frequency (across all their raw variants) to cut singleton noise
-- typos, fused-token artifacts, brand names, and the odd dish name, which checking real samples
showed are concentrated almost entirely at frequency 1 (see the session this script was written in
for the frequency/quality tradeoff data).

Usage:
    python porting-reference/build_merged_ingredients_db.py [--min-frequency 2] [--dry-run]
"""

from __future__ import annotations

import argparse
import re
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RECIPE_DB = REPO_ROOT / "app" / "src" / "main" / "assets" / "database" / "recipe_database.sqlite"
INGREDIENTS_DB = REPO_ROOT / "app" / "src" / "main" / "assets" / "ingredients.db"

BLOB_NAME_LENGTH_THRESHOLD = 40  # mirrors RecipeViewModel.BLOB_NAME_LENGTH_THRESHOLD_NEW

# --- Mirrors IngredientMatcher.kt (kept in sync by hand -- see module docstring) ------------

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
    "removed", "quartered", "halved", "thawed", "crushed", "reserved", "crumbled",
    "toasted", "deveined", "chilled", "juiced", "undrained", "mashed", "warmed",
    "pitted", "sifted", "flaked", "cooled", "separated", "zested", "scrubbed",
    "unpeeled", "discarded", "cleaned", "stemmed", "pressed", "heated", "undiluted",
    "baked", "unwrapped", "blanched", "defrosted", "slivered", "boiled", "pureed", "squeezed",
}

RECIPE_ONLY_STOPWORDS = {"and", "or", "with", "without", "in", "for", "from", "into", "on", "at", "by"}

IRREGULAR_PLURALS = {
    "leaves": "leaf", "loaves": "loaf", "halves": "half", "calves": "calf",
    "knives": "knife", "geese": "goose", "shelves": "shelf",
}

NEVER_STEM = {"molasses", "asparagus", "hummus", "couscous", "watercress", "swiss", "anise", "haggis", "series"}

# --- New for this script: this corpus's normalized_name leaks unit/measurement and stray
# connector words that IngredientMatcher has no reason to know (they never appear in what a user
# types when adding a fridge item), plus a few residual junk words identified by sampling
# low-frequency groups by hand (e.g. "oil or as needed" -> "oil as" without "as" here). ------

MEASUREMENT_STOPWORDS = {
    "inch", "inches", "ounce", "ounces", "pound", "pounds", "cup", "cups",
    "tablespoon", "tablespoons", "teaspoon", "teaspoons", "gram", "grams",
    "quart", "quarts", "pint", "pints", "gallon", "gallons", "fluid",
    "package", "packages", "container", "containers", "jar", "jars", "can", "cans",
    "bag", "bags", "box", "boxes", "stick", "sticks", "bunch", "bunches", "head", "heads",
    "as", "more", "roughly", "s", "ground",
}

# A missing space in the source data glues a quantity word onto the ingredient that follows it
# ("teaspooncumin seeds", "gbacon") or a preparation word onto the ingredient before it
# ("onionchopped", "garlicfinely") -- the exact same class of fusion typo IngredientMatcher.kt's
# QUANTITY_PREFIXES already works around for matching (see its isBlockedModifier doc), but here it
# needs handling too: naively, the fused token survives as its own nonsense one-word "ingredient",
# and since it has no internal space to make it long, it can even look like the *shortest* --
# and therefore selected -- example for its group. Narrow, curated lists (mirroring
# QUANTITY_PREFIXES's own reasoning) rather than checking every STOPWORDS entry as a
# prefix/suffix, which risks false splits on real words that just happen to end in "as" or start
# with "can" (candy, canteloupe, ...).
QUANTITY_PREFIXES = [
    "tablespoons", "tablespoon", "tbsp", "tbl", "teaspoons", "teaspoon", "tsp",
    "cups", "cup", "cans", "can", "grams", "gram", "gr", "ounces", "ounce", "oz",
    "pounds", "pound", "lbs", "lb", "packages", "package",
    "containers", "container", "jars", "jar", "tubs", "tub", "pinch",
]
PREP_SUFFIXES = [
    "chopped", "minced", "sliced", "diced", "grated", "shredded", "crushed",
    "finely", "coarsely", "thinly", "quartered", "halved", "peeled", "trimmed",
    "ground", "lightly",
]

# Deliberately no single-letter prefixes ("g", "c" for grams/cups) here, unlike
# IngredientMatcher.kt's QUANTITY_PREFIXES: that Kotlin list only ever checks a fixed, curated set
# of BLOCK_MODIFIERS as the remainder, so it could safely special-case the two resulting real-word
# collisions (goat/coat) by name. This script has no such fixed remainder vocabulary -- the
# remainder can be *any* ingredient word -- so "remainder isn't a known stopword" is nowhere near
# enough to confirm a real fusion happened: it mangled "garlic" into "arlic" (a "g" + "arlic"
# false split) before this was caught. Missing a few genuine single-letter fusions like "gbacon"
# is a much smaller cost than corrupting real words wholesale.
_TOKEN_SEPARATOR = re.compile(r"[^a-z]+")


def _split_fused(token: str) -> list[str]:
    """Recovers the real word(s) from a token fused with a quantity prefix or prep-word suffix
    (see the module-level comment above QUANTITY_PREFIXES). Returns [token] unchanged if it
    doesn't look fused.

    No check that the remainder is itself a "real" word beyond the length margin below -- a
    remainder that happens to be a stopword (e.g. "teaspoonsground" -> "ground") is still the
    *correct* split, since the caller's normal stopword filter drops it right after, same as if it
    had never been fused in the first place. An earlier version rejected exactly that case, which
    silently broke as soon as "ground" was added to the stopword lists.
    """
    for prefix in QUANTITY_PREFIXES:
        if len(token) > len(prefix) + 2 and token.startswith(prefix):
            return [token[len(prefix):]]
    for suffix in PREP_SUFFIXES:
        if len(token) > len(suffix) + 2 and token.endswith(suffix):
            return [token[: -len(suffix)]]
    return [token]


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


def clean_words(raw: str) -> list[str]:
    ordered = []
    for raw_token in _TOKEN_SEPARATOR.split(raw.lower()):
        if not raw_token:
            continue
        for token in _split_fused(raw_token):
            if token in STOPWORDS or token in RECIPE_ONLY_STOPWORDS or token in MEASUREMENT_STOPWORDS:
                continue
            ordered.append(singularize(token))
    return ordered


def extract_corpus_vocabulary(min_frequency: int) -> list[str]:
    """Distinct, cleaned, frequency-filtered display names mined from the recipe corpus."""
    con = sqlite3.connect(RECIPE_DB)
    cur = con.cursor()
    rows = cur.execute(
        """SELECT i.normalized_name, COUNT(*) AS freq
           FROM ingredients i JOIN recipe_ingredients ri ON ri.ingredient_id = i.ingredient_id
           WHERE LENGTH(i.normalized_name) <= ?
           GROUP BY i.ingredient_id""",
        (BLOB_NAME_LENGTH_THRESHOLD,),
    ).fetchall()
    con.close()

    groups: dict[tuple[str, ...], dict] = defaultdict(lambda: {"freq": 0, "examples": Counter()})
    for normalized_name, freq in rows:
        words = clean_words(normalized_name)
        if not words:
            continue
        key = tuple(words)
        groups[key]["freq"] += freq
        groups[key]["examples"][normalized_name] += freq

    names = []
    for key, data in groups.items():
        if data["freq"] < min_frequency:
            continue
        # The shortest raw example reads as a real, naturally-phrased name -- e.g. "carrots" over
        # "small carrots peeled and grated" -- rather than reconstructing one from the word set,
        # which risks producing a phrase no recipe actually uses. But prefer one that isn't itself
        # an "X or Y"/"X and Y" alternatives list: dropping "or"/"and" as connectives is right for
        # grouping (so "coconut oil or vegetable oil" correctly doesn't invent a fake ingredient
        # distinct from either), but the wrong choice of *display* string when a group's shortest
        # raw example happens to be one of those un-split compound mentions rather than a plain
        # one-ingredient phrasing of the same group -- "pepper flakes or cayenne pepper" is not a
        # sensible name for one ingredient, even though it's short.
        candidates = [ex for ex in data["examples"] if not _CONNECTIVE_PHRASE.search(ex)]
        pool = candidates or list(data["examples"])
        best = min(pool, key=len)
        names.append(best.strip())
    return names


_CONNECTIVE_PHRASE = re.compile(r"\b(or|and)\b")


def slugify(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--min-frequency", type=int, default=2,
                     help="Minimum total recipe-corpus mentions for a cleaned ingredient group to be included.")
    ap.add_argument("--dry-run", action="store_true", help="Report counts without writing ingredients.db.")
    args = ap.parse_args()

    con = sqlite3.connect(INGREDIENTS_DB)
    cur = con.cursor()
    existing = [row[0] for row in cur.execute("SELECT name_en FROM ingredients").fetchall()]
    existing_lower = {name.lower() for name in existing}
    print(f"Existing ingredients.db: {len(existing)} entries")

    corpus_names = extract_corpus_vocabulary(args.min_frequency)
    print(f"Corpus-derived candidates (min_frequency={args.min_frequency}): {len(corpus_names)}")

    new_names = []
    seen_lower = set(existing_lower)
    for name in corpus_names:
        lower = name.lower()
        if lower in seen_lower:
            continue
        seen_lower.add(lower)
        new_names.append(name)

    print(f"New entries after de-duplication against existing: {len(new_names)}")
    print(f"Final merged size: {len(existing) + len(new_names)}")

    if args.dry_run:
        print("\n--dry-run: not writing. Sample of new entries:")
        for name in new_names[:30]:
            print(f"  {name!r}")
        con.close()
        return

    used_ids = {row[0] for row in cur.execute("SELECT id FROM ingredients").fetchall()}
    inserted = 0
    for name in new_names:
        entry_id = f"corpus:{slugify(name)}"
        # Extremely unlikely (would need two different display names slugifying identically),
        # but skip rather than crash on a primary-key collision.
        suffix = 2
        base_id = entry_id
        while entry_id in used_ids:
            entry_id = f"{base_id}-{suffix}"
            suffix += 1
        used_ids.add(entry_id)
        cur.execute("INSERT INTO ingredients (id, name_en) VALUES (?, ?)", (entry_id, name))
        inserted += 1
    con.commit()

    cur.execute("SELECT COUNT(*) FROM ingredients")
    total = cur.fetchone()[0]
    con.close()
    print(f"\nInserted {inserted} rows. ingredients.db now has {total} entries total.")


if __name__ == "__main__":
    main()
