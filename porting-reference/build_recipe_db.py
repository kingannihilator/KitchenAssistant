#!/usr/bin/env python3
"""
Build an offline SQLite recipe database from open recipe datasets.

Primary source:
  odunola/foodie (Hugging Face), Apache-2.0, 19,566 rows.
  The source contains English recipe text with ingredients and directions.

Optional source:
  josephrmartinez/recipe-dataset 13k-recipes.csv, CC BY-SA 3.0.
  Disabled by default because combining licenses requires care.

The resulting database is self-contained and has:
  recipes
  ingredients
  recipe_ingredients
  recipe_steps
  recipe_fts (FTS5)
  ingredient_fts (FTS5)
  recipe_cuisines / cuisines
  source_datasets

Usage:
  python build_recipe_db.py
Then open recipe_database.sqlite with any SQLite browser.

For ingredient search, examples are in example_queries.sql.
"""

import csv, io, json, re, sqlite3, sys, urllib.request
from pathlib import Path

DB = Path("recipe_database.sqlite")
HF_URL = "https://huggingface.co/datasets/odunola/foodie/resolve/main/recipes.csv?download=true"

SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS source_datasets(
  source_id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  url TEXT,
  license TEXT,
  notes TEXT
);

CREATE TABLE IF NOT EXISTS recipes(
  recipe_id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  local_name TEXT,
  country TEXT,
  region TEXT,
  cuisine TEXT,
  category TEXT,
  prep_time TEXT,
  cook_time TEXT,
  total_time TEXT,
  servings TEXT,
  description TEXT,
  instructions_raw TEXT,
  source_id INTEGER REFERENCES source_datasets(source_id)
);

CREATE TABLE IF NOT EXISTS ingredients(
  ingredient_id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  normalized_name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS recipe_ingredients(
  recipe_id INTEGER NOT NULL REFERENCES recipes(recipe_id) ON DELETE CASCADE,
  ingredient_id INTEGER NOT NULL REFERENCES ingredients(ingredient_id),
  original_text TEXT NOT NULL,
  amount TEXT,
  unit TEXT,
  preparation TEXT,
  position INTEGER,
  PRIMARY KEY(recipe_id, ingredient_id, position)
);

-- tier classifies each recipe_ingredients row relative to the recipe it belongs to:
--   DEFINING   ingredient phrase (or part of it) appears in the recipe title,
--              e.g. "garlic" in "Garlic Chicken", "black pepper" in "Black Pepper Beef"
--   SEASONING  a common seasoning/condiment/base word in a small quantity
--              (tsp/pinch/dash/"to taste") and not title-defining
--   SUPPORTING everything else: a real ingredient that's neither the dish's
--              namesake nor a plain seasoning (e.g. onion, rice, cabbage)

CREATE TABLE IF NOT EXISTS recipe_steps(
  recipe_id INTEGER NOT NULL REFERENCES recipes(recipe_id) ON DELETE CASCADE,
  step_no INTEGER NOT NULL,
  step_title TEXT,
  instruction TEXT NOT NULL,
  PRIMARY KEY(recipe_id, step_no)
);

CREATE TABLE IF NOT EXISTS cuisines(
  cuisine_id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS recipe_cuisines(
  recipe_id INTEGER REFERENCES recipes(recipe_id) ON DELETE CASCADE,
  cuisine_id INTEGER REFERENCES cuisines(cuisine_id),
  PRIMARY KEY(recipe_id,cuisine_id)
);

CREATE VIRTUAL TABLE IF NOT EXISTS recipe_fts USING fts5(
  title, description, instructions_raw, content='recipes', content_rowid='recipe_id'
);

CREATE VIRTUAL TABLE IF NOT EXISTS ingredient_fts USING fts5(
  name, normalized_name, content='ingredients', content_rowid='ingredient_id'
);

CREATE INDEX IF NOT EXISTS idx_recipe_ingredients_ingredient
  ON recipe_ingredients(ingredient_id);
CREATE INDEX IF NOT EXISTS idx_recipe_ingredients_recipe
  ON recipe_ingredients(recipe_id);
CREATE INDEX IF NOT EXISTS idx_recipes_country ON recipes(country);
CREATE INDEX IF NOT EXISTS idx_ingredients_normalized
  ON ingredients(normalized_name);
"""

def norm(s):
    s = (s or "").lower().strip()
    s = s.replace("&", " and ")
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()

def clean_title(s):
    s = (s or "").strip()
    s = re.sub(r"\s+", " ", s)
    return s

def _standalone_line(t, start, end):
    # True when the match occupies its own line (ignoring surrounding
    # spaces/tabs) -- i.e. it's a section header, not a word inside running
    # prose, e.g. "...recipe with the ingredients I had on hand..." should
    # NOT count even though "ingredients" matches \bIngredients\b there.
    before, after = t[:start], t[end:]
    before_ok = before == "" or before.rstrip(" \t").endswith("\n")
    after_stripped = after.lstrip(" \t")
    after_ok = after_stripped == "" or after_stripped.startswith("\n")
    return before_ok and after_ok

def split_sections(text):
    # The Foodie dataset generally concatenates title + Ingredients + Introduction +
    # directions. Keep the original text too; this parser is deliberately conservative.
    t = text.replace("\r", "\n")

    # Require a standalone "Ingredients" header line -- don't fall back to a
    # mid-sentence mention. Some recipes are free-form blog prose with no
    # Ingredients/Directions template at all; guessing from a stray mention
    # of the word there tends to capture directions text as fake ingredients,
    # which is worse than having none (wrong data poisons search results,
    # missing data is just neutral).
    candidates = list(re.finditer(r"\bIngredients\b", t, re.I))
    m = next((c for c in candidates if _standalone_line(t, c.start(), c.end())), None)

    if m:
        # The section after ingredients isn't always "Directions" -- the
        # dataset also uses "Instructions", "Introduction", "Method", etc.
        # These are sometimes glued directly onto the last ingredient with no
        # separator (e.g. "...2 tablespoons lardIntroduction"), which fails a
        # leading \b check -- so don't require one here, only a trailing one
        # (avoids matching a partial/longer word by accident).
        end_m = re.search(
            r"(?:Introduction\b|Instructions?\b|Directions?\b|Method\b|Steps?\b|Preparation\b)",
            t[m.end():], re.I)
        ing_text = (t[m.end():m.end() + end_m.start()] if end_m else t[m.end():]).strip()
        before = t[:m.start()].strip()
    else:
        ing_text = ""
        before = t[:200].strip()

    m2 = re.search(r"\bdirections\b(.*)$", t, re.I | re.S)
    directions = m2.group(1).strip() if m2 else ""
    # Usually the first line is the title.
    title = before.splitlines()[0].strip() if before else "Untitled recipe"
    return clean_title(title), ing_text, directions

def ingredient_lines(s):
    # The source dataset lists one ingredient per line (often with extra blank
    # lines between them); prefer that structure over guessing boundaries.
    lines = [ln.strip(" \t,;") for ln in s.split("\n")]
    lines = [ln for ln in lines if ln]
    if len(lines) >= 2:
        return lines
    # No newlines to work with (a handful of recipes use a different template)
    # -- fall back to the old quantity-boundary heuristic on the single blob.
    s = re.sub(r"\s+", " ", s).strip()
    parts = re.split(
        r"(?=(?:\d+(?:\.\d+)?|\d+/\d+|[½¼¾⅓⅔⅛⅜⅝⅞])\s*(?:cups?|tbsp|tsp|tablespoons?|teaspoons?|"
        r"ounces?|oz|pounds?|lbs?|grams?|g|kg|ml|liters?|l)\b)",
        s, flags=re.I)
    parts = [p.strip(" ,;") for p in parts if p.strip(" ,;")]
    return parts if parts else ([s] if s else [])

_FRACTION_CHARS = "½¼¾⅓⅔⅛⅜⅝⅞"
_NUM = rf"(?:\d+\s*\.\s*\d+|\d+\s*/\s*\d+|\d+|[{_FRACTION_CHARS}])"
_QTY_RE = rf"(?:{_NUM}(?:\s*(?:-|–|to)\s*{_NUM})?)"
_UNIT_ALTS = [
    r"tablespoons?", r"tbsp\.?", r"tbs\.?", r"teaspoons?", r"tsp\.?",
    r"cups?", r"fl\.?\s*oz\.?", r"ounces?", r"oz\.?", r"pounds?", r"lbs?\.?",
    r"kilograms?", r"kgs?", r"grams?", r"grs?", r"g\.?",
    r"milliliters?", r"mls?", r"liters?", r"litres?", r"l\.?",
    r"quarts?", r"pints?", r"gallons?", r"inch(?:es)?",
    r"cloves?", r"pieces?", r"pcs?\.?", r"cans?", r"jars?",
    r"packages?", r"pkgs?\.?", r"bottles?", r"boxes?", r"bags?",
    r"bunch(?:es)?", r"sprigs?", r"stalks?", r"heads?", r"slices?", r"strips?",
    r"pinch(?:es)?", r"dash(?:es)?", r"handfuls?", r"wineglassfuls?", r"glass(?:es)?",
]
_UNIT_RE = "(?:" + "|".join(_UNIT_ALTS) + ")"

def parse_amount_unit(s):
    s = s.strip()
    m = re.match(rf"^({_QTY_RE})\s*", s)
    if not m:
        return None, None, s
    amount = m.group(1).strip()
    rest = s[m.end():]

    # Unit immediately after the quantity, e.g. "6g Salt" / "2 tablespoons oil".
    um = re.match(rf"^({_UNIT_RE})\b\.?\s*", rest, flags=re.I)
    if um:
        return amount, um.group(1), rest[um.end():].strip(" ,")

    # A parenthetical secondary quantity before the unit, e.g. "1 (8 ounce) can corn".
    pm = re.match(r"^\(([^)]*)\)\s*", rest)
    if pm:
        after_paren = rest[pm.end():]
        um2 = re.match(rf"^({_UNIT_RE})\b\.?\s*", after_paren, flags=re.I)
        if um2:
            leftover = f"({pm.group(1)}) {after_paren[um2.end():]}".strip(" ,")
            return amount, um2.group(1), leftover

    # Source sometimes drops the space between a short unit and a Capitalized
    # ingredient name, e.g. "50gSugar", "3.5ozButter" -- the capital letter is
    # a safe cue that a new word starts there (unlike "500gboneless", where
    # lowercase continuation makes the boundary genuinely ambiguous). Match
    # the unit case-insensitively but check the next character's case with
    # a plain str.isupper() -- re.I would otherwise make [A-Z] match lowercase
    # too, since it applies to the whole pattern including the lookahead.
    um3 = re.match(rf"^({_UNIT_RE})", rest, flags=re.I)
    if um3 and rest[um3.end():um3.end() + 1].isupper():
        return amount, um3.group(1), rest[um3.end():].strip(" ,")

    # No recognizable unit word -- the quantity is attached straight to the
    # ingredient name (e.g. "1Eggs", "4Eggslightly beaten").
    return amount, None, rest.strip(" ,")

def ingredient_name(original):
    x = original.lower()
    x = re.sub(r"^(?:for|of)\s+", "", x)
    amount, unit, rest = parse_amount_unit(original)
    x = rest if amount else x
    # Remove common preparation clauses while retaining the core ingredient.
    x = re.sub(r"\([^)]*\)", " ", x)
    x = re.sub(r"\b(?:chopped|diced|minced|sliced|thinly sliced|julienned|cubed|melted|"
               r"freshly ground|ground|fresh|dried|optional|to taste|for serving|for garnish)\b", " ", x)
    x = re.sub(r"\s+", " ", x).strip(" ,.-")
    return x or original.strip()

def import_foodie(conn, csv_bytes):
    cur = conn.cursor()
    cur.execute("""INSERT OR IGNORE INTO source_datasets
      (source_id,name,url,license,notes) VALUES
      (1,'Global Food Recipes Dataset — odunola/foodie',?,
       'Apache-2.0','19,566 English recipe records; source dataset has a single text field.')""",
                (HF_URL,))
    reader = csv.DictReader(io.StringIO(csv_bytes.decode("utf-8", errors="replace")))
    n = 0
    for row in reader:
        text = row.get("texts") or row.get("text") or ""
        if not text.strip():
            continue
        title, ing_text, directions = split_sections(text)
        # Some rows contain multiple recipe records concatenated. Keep the complete
        # text in instructions_raw so no source information is lost.
        cur.execute("""INSERT INTO recipes
          (title,description,instructions_raw,source_id)
          VALUES(?,?,?,1)""", (title, "", text))
        rid = cur.lastrowid
        for pos, raw in enumerate(ingredient_lines(ing_text), 1):
            amount, unit, preparation = parse_amount_unit(raw)
            name = ingredient_name(raw)
            nn = norm(name)
            if not nn:
                continue
            cur.execute("INSERT OR IGNORE INTO ingredients(name,normalized_name) VALUES(?,?)",
                        (name, nn))
            iid = cur.execute("SELECT ingredient_id FROM ingredients WHERE normalized_name=? LIMIT 1",
                              (nn,)).fetchone()[0]
            cur.execute("""INSERT OR IGNORE INTO recipe_ingredients
              (recipe_id,ingredient_id,original_text,amount,unit,preparation,position)
              VALUES(?,?,?,?,?,?,?)""",
                        (rid,iid,raw,amount,unit,preparation,pos))
        # Keep directions as one or more logical steps when numbered/bulleted.
        steps = re.split(r"(?:\n+|\s{2,})(?=(?:\d+[\).]|[-•]))", directions)
        steps = [re.sub(r"^\s*(?:\d+[\).]|[-•])\s*", "", x).strip() for x in steps if x.strip()]
        if not steps and directions:
            steps = [directions]
        for no, step in enumerate(steps, 1):
            cur.execute("INSERT INTO recipe_steps(recipe_id,step_no,instruction) VALUES(?,?,?)",
                        (rid,no,step))
        n += 1
        if n % 1000 == 0:
            conn.commit()
    conn.commit()
    return n

def rebuild_fts(conn):
    conn.execute("INSERT INTO recipe_fts(recipe_fts) VALUES('rebuild')")
    conn.execute("INSERT INTO ingredient_fts(ingredient_fts) VALUES('rebuild')")
    conn.commit()

# --- ingredient tier classification (DEFINING / SUPPORTING / SEASONING) -----

TITLE_STOPWORDS = {
    "a", "an", "the", "with", "and", "or", "for", "style", "easy", "best",
    "quick", "simple", "homemade", "classic", "instant", "pot", "air",
    "fryer", "slow", "cooker", "grilled", "roasted", "baked", "fried",
    "ultimate", "perfect", "restaurant", "copycat", "authentic",
    "traditional", "recipe", "dish", "meal", "dinner", "lunch", "one",
    "skillet", "sheet", "pan", "oven", "crispy", "creamy", "spicy",
    "sweet", "sticky",
}

TIER_SEASONING_WORDS = [
    "salt", "pepper", "black pepper", "cayenne", "paprika", "cumin", "cinnamon",
    "nutmeg", "oregano", "basil", "thyme", "rosemary", "parsley", "cilantro",
    "chili powder", "curry powder", "bay leaf", "clove", "ginger", "garlic",
    "vanilla", "extract", "oil", "olive oil", "vegetable oil", "butter",
    "sugar", "honey", "syrup", "flour", "cornstarch", "baking powder",
    "baking soda", "yeast", "vinegar", "soy sauce", "hot sauce", "ketchup",
    "mustard", "mayonnaise", "worcestershire", "stock", "broth", "water",
    "wine", "breadcrumbs", "seasoning", "spice", "herbs",
]
TIER_SEASONING_RE = re.compile(r"\b(" + "|".join(re.escape(w) for w in TIER_SEASONING_WORDS) + r")\b")
TIER_SMALL_QTY_UNITS = {"tsp", "teaspoon", "teaspoons", "pinch", "dash"}
TIER_SMALL_QTY_PHRASES = ["to taste", "as needed", "for garnish", "or more to taste", "sprinkle"]

def title_phrases(title):
    toks = [t for t in norm(title).split() if t not in TITLE_STOPWORDS and len(t) > 2]
    bigrams = [f"{a} {b}" for a, b in zip(toks, toks[1:])]
    phrases, seen = [], set()
    for p in bigrams + toks:  # bigrams first so compound modifiers win over bare words
        if p not in seen:
            seen.add(p)
            phrases.append(p)
    return phrases

def classify_tier(ingredient_name, amount, unit, preparation, phrases):
    text = norm(ingredient_name)
    for phrase in phrases:
        if re.search(r"\b" + re.escape(phrase) + r"\b", text):
            return "DEFINING"
    small_qty = (unit or "").lower() in TIER_SMALL_QTY_UNITS or \
        any(p in (preparation or "").lower() for p in TIER_SMALL_QTY_PHRASES)
    if TIER_SEASONING_RE.search(text) and (small_qty or not amount):
        return "SEASONING"
    return "SUPPORTING"

def compute_tiers(conn):
    cur = conn.cursor()
    cur.execute("ALTER TABLE recipe_ingredients ADD COLUMN tier TEXT")
    updates = []
    for rid, title in cur.execute("SELECT recipe_id, title FROM recipes").fetchall():
        phrases = title_phrases(title)
        rows = cur.execute(
            """SELECT ri.rowid, i.name, ri.amount, ri.unit, ri.preparation
               FROM recipe_ingredients ri JOIN ingredients i ON i.ingredient_id = ri.ingredient_id
               WHERE ri.recipe_id=?""", (rid,)).fetchall()
        for rowid, name, amount, unit, prep in rows:
            tier = classify_tier(name, amount, unit, prep, phrases)
            updates.append((tier, rowid))
    cur.executemany("UPDATE recipe_ingredients SET tier=? WHERE rowid=?", updates)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_recipe_ingredients_tier ON recipe_ingredients(tier)")
    conn.commit()

def main():
    print("Downloading source dataset...")
    with urllib.request.urlopen(HF_URL, timeout=120) as r:
        data = r.read()
    print(f"Downloaded {len(data)/1024/1024:.1f} MB")
    if DB.exists():
        DB.unlink()
    conn = sqlite3.connect(DB)
    conn.executescript(SCHEMA)
    n = import_foodie(conn, data)
    print("Classifying ingredient tiers (defining / supporting / seasoning)...")
    compute_tiers(conn)
    rebuild_fts(conn)
    conn.execute("PRAGMA journal_mode=DELETE")
    conn.execute("VACUUM")
    conn.close()
    print(f"Created {DB.resolve()} with {n:,} recipes.")

if __name__ == "__main__":
    main()
