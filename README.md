# Kitchen Assistant

Android app that matches your fridge contents against an offline recipe corpus and ranks
recipes by how much of each one you can already make. No network calls — all data is local.

See [CLAUDE.md](CLAUDE.md) for architecture, the matching rules, and build commands.

## Setup: the recipe database is not in this repo

The app needs `app/src/main/assets/database/recipe_database.sqlite` (~95MB, odunola/foodie
corpus), which is excluded from version control — it's past GitHub's 100MB per-file hard limit,
and it's built via the scripts in `porting-reference/` (see
`porting-reference/INGREDIENT_MATCHING_CONCEPTS.md` and
`porting-reference/NEW_CORPUS_DATA_QUALITY.md`) rather than committed directly.

`app/src/main/assets/ingredients.db` (0.3 MB) is small enough to stay tracked in git.

### What happens if it's missing

The Gradle build still succeeds — a missing asset is not a compile error. The failure shows up
at runtime the first time you open the recipe screen.
