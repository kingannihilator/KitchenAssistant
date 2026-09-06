# Rolling back the v1.4 recipe corpus swap

Tag `recipe-db-v1.4-swap` marks the commit that replaced the odunola/foodie recipe corpus
(16,090 recipes) with the "clean" v1.4 corpus (4,779 recipes, `new_db_workable/`). This doc is
for reverting that swap later — possibly much later, after unrelated app changes (UI, search
ranking, whatever) have piled on top of it. It's written so it stays correct even then: it
doesn't rely on line numbers or the current state of any file, only on things that don't drift
(a git tag, a backup file, and a schema-compatibility check you re-run yourself).

## Why this isn't a plain `git revert`

The recipe database file at `app/src/main/assets/database/recipe_database.sqlite` is gitignored
now (again) for the pre-swap odunola/foodie corpus, but that's not the whole story: it was
gitignored at swap time too (tag `recipe-db-v1.4-swap` itself doesn't contain it), then tracked in
git starting at tag `recipe-db-v1.4-cleanup` the next day, and has stayed tracked since — so
there IS git history for the *v1.4* side of this (`git log -- app/src/main/assets/database/
recipe_database.sqlite` shows every post-swap fix, this repo's own audits included). What there
is NOT, and never was, is any git history for the *odunola/foodie* side: that corpus was always
too large for GitHub's 100MB limit, so no commit ever contained it, and `git revert`/`git show`
on the swap commit has nothing to restore. That's specifically why the rollback below is a manual
file swap from a local backup, not a git operation (the tag is there to show you what *code*
changed alongside the data swap, via `git diff recipe-db-v1.4-swap^ recipe-db-v1.4-swap` or
`git show recipe-db-v1.4-swap`).

## What you need

**`porting-reference/legacy-recipe-path/recipe_database_odunola_backup.sqlite`** (39MB, 15,121
recipes — verified directly against the file, not assumed) — the exact odunola/foodie asset file
that was bundled before the swap.

**A fresh clone of this repo will NOT have this file.** It has never been in git — not before the
swap (always too large for GitHub's 100MB limit) and not after (explicitly gitignored once the
v1.4 corpus made the live asset small enough to track, so this backup wouldn't be too). A
`.gitignore` rule only stops it from being *added*; it does nothing to preserve a copy that
isn't there in the first place. Concretely: if you're doing this rollback on the same machine
where the swap happened and this file is still sitting on disk, follow the steps below. If you're
on a fresh clone (a different machine, a re-cloned working copy, a new session with no local
history), **this file will not exist and there is no command that recreates it** — go find
whoever has a copy (ask the project owner) before doing anything else. Don't delete your only
copy of it once you have one, and don't rely on git for redundancy — copy it somewhere outside
this repo too.

If no copy of this file can be found anywhere, there's no way to regenerate the odunola/foodie
corpus from scratch in this repo — it was a third-party dataset, not something built here, so a
missing backup means this specific rollback is no longer possible at all (reverting to some
*other* corpus is a different, unrelated question — see "If you're swapping to a *different*
future corpus instead" below).

## Steps

1. **Restore the asset file.**

   ```
   cp porting-reference/legacy-recipe-path/recipe_database_odunola_backup.sqlite \
      app/src/main/assets/database/recipe_database.sqlite
   ```

2. **Check schema compatibility before you build.** Whatever Room entities/DAO exist in the app
   *at the time you're doing this* must match the odunola/foodie schema, not the v1.4 schema. At
   swap time these were identical (that was the whole point — see `porting-reference/
   build_recipe_db_v1_4.py`'s docstring), but if any app changes since then added a new column,
   index, or query that assumes something only the v1.4 build has (see "Data differences" below),
   you'll need to either revert that specific change too or adapt it to tolerate the older
   schema. Verify directly rather than assuming:

   ```python
   import sqlite3
   def dump(db):
       con = sqlite3.connect(db); cur = con.cursor()
       return {t: {
           'cols': cur.execute(f'PRAGMA table_info({t})').fetchall(),
           'fk': cur.execute(f'PRAGMA foreign_key_list({t})').fetchall(),
       } for t in ['recipes', 'ingredients', 'recipe_steps', 'categories']}
   # Compare dump('app/src/main/assets/database/recipe_database.sqlite') against what the
   # current NewRecipeEntities.kt declares (its own docstring explains why this must match
   # exactly, and Room throws IllegalStateException at first launch if it doesn't).
   ```

3. **Decide what to do with the "Recipe Sources" / About screen** (`ui/AboutScreen.kt`, wired in
   from `IngredientScreen.kt`'s top bar and `MainActivity.kt`'s `Screen.About`). It was added
   specifically because ~77% of the v1.4 corpus is CC BY-SA 4.0 (Wikibooks) and needs attribution.
   The odunola/foodie corpus has no such requirement (check its own license before assuming
   otherwise). Either remove the screen and its entry point, or repoint it at whatever attribution
   the corpus you're reverting to actually needs — don't leave it crediting sources for a corpus
   that's no longer bundled, that's actively misleading.

4. **Clear (or warn about) users' favorites.** `FavoritesRepository` stores favorited recipes by
   integer `recipe_id`. That numbering is specific to whichever corpus build assigned it — it is
   *not* stable across corpus swaps in either direction. Reverting the asset without clearing
   favorites means a user's saved favorite IDs will silently resolve to different (probably
   unrelated) recipes in the restored corpus, not the ones they actually favorited. The fridge
   ingredient list (`FridgeRepository`) is unaffected — it's stored by ingredient *name*, not id.

5. **Rebuild and reinstall**, then sanity-check the same way the swap itself was verified: app
   launches with no Room schema-validation crash, recipe count reflects 15,121 (the backup file's
   actual count as of this writing — verify with `select count(*) from recipes` against whatever
   backup you're actually restoring, don't trust this number blindly if the backup file has
   changed since; CLAUDE.md's "~16,090 after dedupe" figure describes a *different* odunola/foodie
   snapshot, not this specific backup file, so don't expect them to match), a recipe detail screen
   renders ingredients and directions correctly.

## Data differences worth knowing if you're merging/adapting instead of reverting outright

- The v1.4 corpus actually populates `servings`/`category`/`cuisine`-equivalent data (folded into
  `recipes.title`/`categories`/`ingredients` by the build script, see `build_recipe_db_v1_4.py`);
  odunola/foodie always has these NULL. If any post-swap app code started relying on non-null
  values there, it needs a null-safe fallback for the older corpus.
- `porting-reference/head_categories.json`'s additions from the v1.4 taxonomy rebuild (see that
  file's newer entries, added the same day as tag `recipe-db-v1.4-swap`) are harmless to leave in
  place — they just won't match anything in the odunola/foodie ingredient vocabulary, so they're
  inert rather than wrong.
- Ingredient IDs, recipe IDs, and canonical ingredient names are entirely different numbering/
  vocabulary between the two corpora. Nothing in the app should hardcode either (nothing did, as
  of the swap), but check if that's changed before assuming it's still safe.

## If you're swapping to a *different* future corpus instead

Don't hand-edit the bundled asset. Follow the same pattern as this swap: write a build script
(model it on `porting-reference/build_recipe_db_v1_4.py`) that transforms the new source data into
the exact schema `NewRecipeEntities.kt`/`NewRecipeDao.kt` expect, verify with the schema-dump
check in step 2 above, then swap the asset in. That's what kept this swap from touching any Room
entity, DAO query, or matching/ranking code, and it'll do the same for the next one.
