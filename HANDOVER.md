# Project Handover

Written for a new Claude session picking up this repo cold. Read `CLAUDE.md` first (architecture,
build commands, recipe matching, corpus provenance) — this doc is about *recent history and open
threads*, not the app's overall shape. This file is meant to be kept current: update it (don't
just append) whenever a work session wraps up a notable chunk of work.

## Current release state

- **Cut, not yet uploaded: `versionCode 4` / `versionName "1.3.0"`**, tagged `playstore-v1.3.0-4` on
  commit `55e08b6` (2026-09-30). See the caveat below — this is the first release where the tag
  precedes the upload.
- Shipped: `versionCode 3` / `versionName "1.2.0"`, tagged `playstore-v1.2.0-3` (tag on commit
  `2f425a7`, which carries a 2026-09-20 commit date; released 2026-09-21, per the notes move in
  `ec1649d`).
- Previous releases: `1.1.0`/`versionCode 2` (`playstore-v1.1.0-2`, commit `2fe2d08`, 2026-08-31) and
  `1.0`/`versionCode 1` (`playstore-v1.0-1`, commit `37619f0`, the last upload before 1.1.0 —
  2026-08-15).
- **The 1.3.0 caveat — read before trusting the tag.** Normally the tag goes on at the moment of the
  Play Console upload, so `playstore-v*` means "this is what shipped". For 1.3.0 the machine that cut
  it has no `app/keystore.properties`, so `assembleRelease`/`bundleRelease` produce **unsigned**
  artifacts (`app-release-unsigned.apk`; `signingConfig` is null, see `app/build.gradle.kts`) and
  cannot be uploaded. The user elected to tag now and upload later from the machine holding
  `kitchen-assistant-release.jks`, so the 1.3.0 tag marks the freeze, not the upload. **Nothing
  release-worthy is left unreleased** — the next release after 1.3.0 starts empty.
- `PLAY_STORE_WHATS_NEW.md` (repo root) holds the actual Play Console "What's new" text — a
  polished, 500-character-limited public-facing draft for the *next* release, kept in sync with
  (but written very differently from) CLAUDE.md's internal engineering changelog. The draft is back
  to a stub and 1.3.0's text has moved into History. Add a line to the draft whenever new
  release-worthy work lands, same trigger as updating this file.

## What shipped in 1.2.0 (the work behind the current release)

All of the below is *released* as of `playstore-v1.2.0-3` — it's history now, not pending work. It's
kept here because it's the most recent substantive context for a new session.

**Ingredient-matching accuracy pass.** Triggered by a request to "find further data parsing gaps"
in `IngredientMatcher.kt`. Audited every ingredient name actually used by the bundled corpus with
a Python port of the matcher's parsing rules, found ~10 distinct classes of head-resolution bugs
(diacritics, bone-in cuts, parenthetical asides, missing "for" connective handling, quantity words
stealing the head from a part word, irregular plurals, missing spelling/regional aliases, missing
part-words, null-head ingredients not being suppressed, and "X or Y" alternative ingredients only
ever resolving to the last alternative), fixed each with its own commit and test cases in
`IngredientMatcherTest.kt`, then patched ~20 corpus rows directly (comma-split misfires, section-
header rows) the same way `65dd9b5`/`382d425` did before it — a one-off Python script run against
`app/src/main/assets/database/recipe_database.sqlite`, not committed (matches established
convention; see those two commits' messages for why). **If auditing this area again**, the Python
parser replica used for this pass is a reasonable starting point but was not saved anywhere
persistent — it would need to be re-derived from `IngredientMatcher.kt`'s current Kotlin, since the
two will drift the moment either changes.

**Read-aloud (TTS) feature.** Started from a Motorola-specific bug report ("some Motorola phones
won't show the Cook button") that turned out to be user configuration, not a bug (the button is
correctly hidden in Checklist mode by design — see `AppMode.kt`). While investigating that screen,
found `AndroidManifest.xml` had no `<queries>` declaration for `TTS_SERVICE`, a real Android 11+
package-visibility gap. Fixed that, then — at the user's request — re-enabled the read-aloud
feature (`READ_ALOUD_ENABLED`, off since the Fridge Grub rebrand), moved its control into the
Directions section header, and iterated based on live on-device feedback (the user tests
audio/TTS themselves — **do not attempt to test read-aloud playback yourself**, per standing
feedback) through several rounds: louder default volume (explicit `USAGE_MEDIA` audio-stream
routing, since `TextToSpeech`'s own volume parameter is already capped at 1.0 by the framework and
can't go louder), then a reported mid-utterance clicking artifact investigated by swapping
`CONTENT_TYPE_SPEECH` → `CONTENT_TYPE_MUSIC` as a test (`9dc4f76`) and back again (`6aa9f35`) once
the click persisted either way — confirming it's the device's own TTS engine/voice, not something
the app can fix, and not worth further code changes. Also added a persisted speed control
(`ReadAloudRepository`, defaults to 0.85x) and a step-by-step playback mode
(`ReadAloudPlaybackMode`) alongside the original "read straight through" mode.

**The 1.2.0 release cut.** Version bump in `2f425a7`, Play Console upload, tag
`playstore-v1.2.0-3`, and the published "What's new" text moved into
`PLAY_STORE_WHATS_NEW.md`'s History section (`ec1649d`).

**Docs.** `CLAUDE.md`'s "Recipe matching" and "Release versioning" sections were updated to match
the above; this file was added and has since been brought current for the shipped 1.2.0 release.

## What 1.3.0 carried (`playstore-v1.2.0-3`..`playstore-v1.3.0-4`)

Both halves of this are now released — it's history, kept here because it's the most recent
substantive context for a new session. The whole of it was written *and* cut in two work sessions,
and the last two commits on the way to the tag were a version bump (`55e08b6`) and the doc
corrections described at the end of this section.

**The tester-community review items — commit `9ac1d2a`.** The whole plan lives at the user's
`~/.claude/plans/graceful-soaring-scott.md` (not in this repo) and is implemented: About broadened
into a three-item menu with a permanent "Rate this app" entry, a first-run walkthrough carousel
reachable later as Help from About, hard-coded-height fixes for raised system font sizes, a
screen-reader/TalkBack pass, and the Play In-App Review prompt after the 3rd completed cook
(`InAppReview.kt`, `ReviewRepository.kt`, trigger in `RecipeDetailScreen`'s cook-mode toggle).
Notes worth keeping: Play enforces its own review quota, so the dialog silently not appearing is
required behaviour, never a bug; and Checklist-mode users can never reach the cook trigger at all
(by design — cook mode is Quantity-only), which is accepted because the permanent About entry
covers them. `FORCE_ONBOARDING_FOR_TESTING` (`MainActivity.kt`) is currently **`false`** — it was
`BuildConfig.DEBUG` during verification and was turned off afterward so debug launches don't walk
the carousel every time; flip it back to `BuildConfig.DEBUG` if the carousel needs another pass.

**The matching/ranking accuracy fix (the work this section was written for).** Two independent
search-correctness bugs, found by the user asking why a specific fridge's results were wrong, then
measured — not theorized about:

1. **Category expansion was seeded too widely.** `NewIngredientIndex`'s expansion pass added every
   ingredient sharing a *matched* row's `category_id`, and "matched" is deliberately generous — so
   the fridge was credited with whole categories it had nothing in. Measured cases: a fridge
   chicken (via `chicken or beef`, filed `Meat/Beef`) was credited all 138 `Meat/Beef` rows
   (`ribeye`, `chuck` …); a fridge tomato (via `sun-dried tomatoes in oil`, filed
   `Oils/Cooking Oil`) was credited all 189 Cooking Oil rows (`palm oil`, `bacon grease`). Fix:
   `IngredientMatcher.canSeedCategoryExpansion` — a row may only seed when it names the *same
   substance* as the fridge item at the same or coarser granularity (no `Term.alternatives`, and
   the fridge term's words contain the row's). Coarser still seeds, which is what keeps the
   intended `chicken breast` → `Meat/Chicken` → `chicken thighs` behavior.
2. **`prioritizedCount` counted rows, not fridge entries.** One starred chicken breast scored 2
   against a recipe listing both `chicken or beef` and `chicken or beef broth`, because the SQL
   returned `COUNT(DISTINCT ingredient_id)` of the starred matches. That single extra point lifted
   a 7/15 recipe above a genuine 5/5 full match. Fix: `prioritized` is now derived app-side like
   `matched`/`defining`, via the new pure `RecipeRanking.distinctOriginCount` /
   `prioritizedOriginCount` — the count of distinct *starred fridge entries* a recipe uses. The
   aggregate was removed from `NewRecipeDao` (`NewRecipeMatchRow.prioritized`) rather than fixed,
   since SQL can't see that two rows came from one fridge entry.

**Effects, measured on a 3-item fridge (onion, tomato, starred chicken breast), 1,554 recipes
considered:** the top result, Pan-Seared Chicken Breast, went from rank **#128** (behind 127
phantom recipes) → **#7** after fix 1 → **#2** after fix 2; the two recipes still above it are
promoted effectively-full matches that use more of the actual fridge. In "Most Complete" mode it is
**#1**. The measurement was taken with temporary logcat diagnostics in `RecipeViewModel` that have
since been **removed** (grep for `RankDebug` finds nothing) — reproducing it means re-adding
something similar, not finding a switch.

**Tests.** 141 unit tests green via `gradlew.bat test` (from the Bash tool, prefix
`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`). New coverage: 3 tests in
`IngredientMatcherTest` for the seeding rule, 9 in `RecipeRankingTest` for the origin-dedup counts
plus one ordering case pinning the double-count bug. One residual is accepted and pinned rather
than fixed — the seeding test runs on *parsed* words, so a row whose extra words are all
`STOPWORDS` (`canned diced tomatoes in juice`) still seeds, costing ~26 catch-all-category rows
from a fridge tomato (down from 217); closing it would require raw-name comparison, which would
wrongly reject `boneless skinless chicken breasts`.

**Docs updated for this work:** `CLAUDE.md`'s "Query strategy", "Ranking", and "Category taxonomy"
sections (the ranking key list there had drifted out of date — it now matches `recipeOrder`), plus
`PLAY_STORE_WHATS_NEW.md`'s 1.3.0 text and this file, and the release-state sections of both once
the version bump landed.

## Known non-issues (don't re-investigate these)

- **"Cook this recipe" button missing on some devices**: by design in Checklist mode
  (`AppMode.CHECKLIST` — presence-only fridge tracking has no quantities for cook mode to deduct
  from). Not a Motorola bug, not a rendering bug. Confirmed with the user for the specific report
  that prompted this.
- **Mid-utterance clicking sound during read-aloud**: confirmed (by testing `CONTENT_TYPE_MUSIC`
  vs `CONTENT_TYPE_SPEECH`, no difference) to be a characteristic of the device's own TTS
  engine/voice, not this app's audio routing or synthesis (the app doesn't synthesize audio itself
  — it hands text to the OS engine). The only lever is picking a different engine/voice in the
  phone's system settings.

## Open TODOs / ideas (from project memory, still unresolved)

- **iOS port — full plan written, nothing started.** `IOS_PORT_PLAN.md` (repo root) holds a
  staged KMP + Compose Multiplatform migration plan: target module layout (`:core` + `:shared` +
  `:androidApp`, plus `iosApp/`), tooling, 10 incremental stages that keep Android shipping, a
  26-row Android-API→multiplatform substitution table, and a 17-item risk register. **Planning
  only — no code has been written, and the user has not approved starting.** Two things to know
  before reading it: iOS binaries cannot be built on this Windows machine (CI/macOS required), and
  the plan's own "Suggested first move" is Stage 0 + Stage 1 only (CI setup, then an isolated
  Kotlin bump) — ask the user before beginning either.
- Fridge row's units button should get an editable dropdown, matching the add-ingredient flow's
  autocomplete-style unit picker.
- Fridge delete-undo only supports one pending item at a time; user wants multi-undo considered.
- Obscure/non-food autocomplete entries (e.g. "dexpanthenol") can outrank common food words when
  both have zero recipe-corpus popularity signal — no clean fix identified yet.
- **Resolved: `assembleRelease` under R8.** Checked during the 1.3.0 cut — `assembleRelease` and
  `bundleRelease` both build clean with `com.google.android.play:review` in the graph and
  `proguard-rules.pro` still the empty template, so Play Core survives minification. The artifacts
  are unsigned on this machine (no `app/keystore.properties`), which is a signing matter, not an R8
  one. What has *not* been verified is the review *entry* working in a release build on a device —
  only that the build produces.
- **Two UI issues spotted during the accessibility pass, both unfixed and unconfirmed:**
  `ui/IngredientScreen.kt` hard-codes the header string "Kitchen Assistant" while `app_name` is
  "Fridge Grub" (leftover from the rebrand); and the read-aloud control row in
  `RecipeDetailScreen.kt` is reported to collide/overlap at ~3× font scale. Neither has been
  verified on a device — they're candidates, not findings.
- (Resolved, no longer open: the "cook button + fridge deduction" idea from memory is now built —
  see "Cook this recipe" in `RecipeDetailScreen.kt`, gated to Quantity mode.)

## Suggested next steps

- **The 1.3.0 upload still has to happen** — from the machine with the release keystore, since this
  one has none. After it's up, confirm `PLAY_STORE_WHATS_NEW.md`'s 1.3.0 History text matches what
  was actually published (the draft was frozen at the tag; Play Console edits, if any, make that
  entry stale). Nothing else is pending release-wise.
- **Device passes that were never done**, and should happen before or soon after that upload: the
  onboarding carousel, About, and the rate entry (`9ac1d2a` was committed with
  `FORCE_ONBOARDING_FOR_TESTING = false`, so a normal launch goes straight to the fridge), and the
  review prompt after three completed cooks — note Play enforces its own quota, so the dialog not
  appearing proves nothing.
- Spot-check the ingredient-matching fixes' net effect on real recipe results if there's ever
  reason to doubt them — `IngredientMatcherTest.kt` covers each fix in isolation but nothing
  currently asserts on aggregate match-rate movement across the whole corpus.
