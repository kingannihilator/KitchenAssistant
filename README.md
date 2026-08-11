# Kitchen Assistant

Android app that matches your fridge contents against an offline recipe corpus and ranks
recipes by how much of each one you can already make. No network calls — all data is local.

See [CLAUDE.md](CLAUDE.md) for architecture, the matching rules, and build commands.

## Setup: the recipe database is not in this repo

The app needs two SQLite databases in `app/src/main/assets/`:

| File | Size | In git? |
|------|------|---------|
| `ingredients.db` | 0.3 MB | yes |
| `recipes_test.db` | 1.4 MB | yes |
| `recipes.db` | **~620 MB** | **no — you must supply it** |

`recipes.db` is excluded from version control. It is a single file well past GitHub's
100 MB per-file hard limit, and it is regenerated outside this repo, so committing it would
also mean committing a new 620 MB blob on every regeneration.

**Before your first build, place `recipes.db` at `app/src/main/assets/recipes.db`.**

<!-- TODO: document where the corpus is generated / how a new contributor obtains a copy. -->

### What happens if it's missing

The Gradle build still succeeds — a missing asset is not a compile error. The failure shows up
at runtime: `BundledDatabase.openReadOnly` calls `context.assets.open("recipes.db")`, which
throws `FileNotFoundException` the first time you open the recipe screen.

If you only need the app to launch and don't need real recipe results, `recipes_test.db` is a
small tracked subset with the same schema.

### Keep the schema in sync

`BundledDatabase` re-copies the asset into internal storage whenever its size differs from the
already-copied file, specifically so a device doesn't get pinned to a stale schema. If you swap
in a regenerated `recipes.db`, that refresh happens automatically on next launch — no need to
clear app data.
