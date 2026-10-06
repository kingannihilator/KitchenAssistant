# Project Handover

Written for a new Claude session picking up this repo cold. Read `CLAUDE.md` first (architecture,
build commands, recipe matching, corpus provenance) — this doc is about *recent history and open
threads*, not the app's overall shape. This file is meant to be kept current: update it (don't
just append) whenever a work session wraps up a notable chunk of work.

## Current release state

- **Cut, not yet uploaded — three releases, in upload order** (the 9/30 work, originally one 1.3.0,
  split into three before any upload at the user's request):
  - `versionCode 4` / `versionName "1.3.0"`, tag `playstore-v1.3.0-4` on commit `5adaeb2` (branch
    `release/1.3.0`): About menu, first-run walkthrough, in-app review prompt, a11y pass.
  - `versionCode 5` / `versionName "1.4.0"`, tag `playstore-v1.4.0-5` on commit `916ba96` (branch
    `release/1.4.0`): the recipe matching/ranking accuracy fix.
  - `versionCode 6` / `versionName "1.4.1"`, tag `playstore-v1.4.1-6` on commit `9c5db0e` (branch
    `release/1.5.0`): the fridge-screen title fix. Bumped as `1.5.0` (`0e8a069`) first, then renamed
    to `1.4.1` in a follow-up commit (`9c5db0e`) since `0e8a069` was already pushed.
- Shipped: `versionCode 3` / `versionName "1.2.0"`, tagged `playstore-v1.2.0-3` (tag on commit
  `2f425a7`, which carries a 2026-09-20 commit date; released 2026-09-21, per the notes move in
  `ec1649d`).
- Previous releases: `1.1.0`/`versionCode 2` (`playstore-v1.1.0-2`, commit `2fe2d08`, 2026-08-31) and
  `1.0`/`versionCode 1` (`playstore-v1.0-1`, commit `37619f0`, the last upload before 1.1.0 —
  2026-08-15).
- **The three-release caveats — read before trusting the tags.** Normally the tag goes on at the
  moment of the Play Console upload, so `playstore-v*` means "this is what shipped". Here the work
  was cut as one `1.3.0` (versionCode 4) first, tagged `playstore-v1.3.0-4` on `bf745b7`, with
  nothing uploaded; it was then re-cut as three short-lived release branches (`release/1.3.0`,
  `release/1.4.0`, `release/1.5.0`) off `700b9c6`, each cherry-picking only its own commits and
  adding its own version bump, and `master` was merged with `release/1.5.0` (`eeddb02`). So
  `playstore-v1.3.0-4` was **moved a second time**, from `bf745b7` to the new 1.3.0 bump `5adaeb2`
  (nothing had been uploaded, and a build containing all three groups was no longer what 1.3.0
  meant). All three tags were created **before** their uploads, so as before they mark the freeze,
  not the upload — a new session on a machine that fetched an older tag would still have it.
  **Unlike the earlier note, this machine has `app/keystore.properties`**, so the three AABs were
  built and signed here (`app/build/release-artifacts/fridgegrub-…-vc{4,5,6}.aab`) and the upload can
  happen from here. **Nothing release-worthy is left unreleased** — the next release after 1.4.1
  starts empty.
- `PLAY_STORE_WHATS_NEW.md` (repo root) holds the actual Play Console "What's new" text — polished,
  500-character-limited public-facing drafts kept in sync with (but written very differently from)
  CLAUDE.md's internal engineering changelog. Because of the three-way split there are **three
  pending drafts**, one per release, instead of the usual single stub; 1.2.0's published text is
  already in History. Move each draft into History as its upload actually happens, and add a line to
  a draft whenever new release-worthy work lands (same trigger as updating this file).

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

## What the three 9/30 releases carry (`playstore-v1.2.0-3`..`playstore-v1.4.1-6`)

Written in two work sessions and originally cut as one `1.3.0`, then split into the three releases
listed under "Current release state": `1.3.0` carries the tester-community review items, `1.4.0` the
matching/ranking fix, `1.4.1` the title fix. All three are **cut but not uploaded**, kept here
because they're the most recent substantive context for a new session.

**The tester-community review items — commit `9ac1d2a` (ships in `1.3.0`).** The whole plan lives at the user's
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

**The matching/ranking accuracy fix — commit `c38de99` (ships in `1.4.0`).** Two independent
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
  `proguard-rules.pro` still the empty template, so Play Core survives minification. Also verified
  *running*: the release APK was signed with `~/.android/debug.keystore` and installed on
  `emulator-5554` (`apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android
  --key-pass pass:android --ks-key-alias androiddebugkey --out <out> <unsigned-apk>`, then
  `adb install -r`), cold-started clean, no `FATAL`/`ClassNotFound` in logcat. **That's the recipe
  for smoke-testing a release build locally** — the debug key's signature also matches the debug
  build, so it installs in place over one and keeps app data. It cannot be uploaded to Play, and a
  Play-signed install on the same device would need an uninstall first. What is still unverified is
  the review *entry* actually working in a release build, not just the build producing.
- **One UI issue still unfixed and unconfirmed:** the read-aloud control row in
  `RecipeDetailScreen.kt` is reported to collide/overlap at ~3× font scale. Not verified on a
  device — a candidate, not a finding.
- (Resolved: the fridge screen's "Kitchen Assistant" title, fixed in `bf745b7` and confirmed on the
  release build. Resolved, no longer open: the "cook button + fridge deduction" idea from memory is
  now built — see "Cook this recipe" in `RecipeDetailScreen.kt`, gated to Quantity mode.)

## Suggested next steps

- **The three uploads still have to happen**, in order (`1.3.0` → `1.4.0` → `1.4.1`) — Play holds
  only one production release at a time. The AABs are already built and signed in
  `app/build/release-artifacts/`; to rebuild, check out each `release/*` branch and run
  `bundleRelease` (this machine has the keystore). After each goes up, move that release's draft
  text into `PLAY_STORE_WHATS_NEW.md`'s History and correct it if Play Console edits differed.
  Nothing else is pending release-wise.
- **Device passes that were never done**, and should happen before or soon after that upload: the
  onboarding carousel, About, and the rate entry (`9ac1d2a` was committed with
  `FORCE_ONBOARDING_FOR_TESTING = false`, so a normal launch goes straight to the fridge), and the
  review prompt after three completed cooks — note Play enforces its own quota, so the dialog not
  appearing proves nothing.
- Spot-check the ingredient-matching fixes' net effect on real recipe results if there's ever
  reason to doubt them — `IngredientMatcherTest.kt` covers each fix in isolation but nothing
  currently asserts on aggregate match-rate movement across the whole corpus.
