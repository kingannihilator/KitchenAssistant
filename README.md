# Fridge Grub

Android app that matches your fridge contents against an offline recipe corpus and ranks
recipes by how much of each one you can already make. No network calls — all data is local.
(Originally built as "Kitchen Assistant"; some internal class names still reflect that.)

See [CLAUDE.md](CLAUDE.md) for architecture, the matching rules, and build commands.

## The recipe database is in this repo

Both bundled data files are tracked in git and ready to use after a clone — no separate build
or download step is needed to run the app:

- `app/src/main/assets/database/recipe_database.sqlite` (~7MB, the `recipes_open_v1_4` corpus,
  4,779 recipes) — tracked since the `recipe-db-v1.4-cleanup` tag. See CLAUDE.md's "The recipe
  corpus" section for its provenance and the corpus it replaced.
- `app/src/main/assets/ingredients.db` (0.3 MB) — the fridge-autocomplete taxonomy.

`porting-reference/` holds the build scripts and historical write-ups for how the *previous*
corpus (odunola/foodie, ~88-95MB, too large for GitHub's 100MB per-file limit and so never
committed) was built and later swapped out — worth reading before touching the recipe-search
code path, but not needed just to build and run the app.
