# Project Handover

Written for a new Claude session picking up this repo cold. Read `CLAUDE.md` first (architecture,
build commands, recipe matching, corpus provenance) — this doc is about *recent history and open
threads*, not the app's overall shape. This file is meant to be kept current: update it (don't
just append) whenever a work session wraps up a notable chunk of work.

## Current release state

- Shipped: `versionCode 3` / `versionName "1.2.0"`, tagged `playstore-v1.2.0-3` (tag on commit
  `2f425a7`, which carries a 2026-09-20 commit date; released 2026-09-21, per the notes move in
  `ec1649d`).
- Previous releases: `1.1.0`/`versionCode 2` (`playstore-v1.1.0-2`, commit `2fe2d08`, 2026-08-31) and
  `1.0`/`versionCode 1` (`playstore-v1.0-1`, commit `37619f0`, the last upload before 1.1.0 —
  2026-08-15).
- Unreleased since `1.2.0`: **nothing release-worthy.** The only commit after the tag is `ec1649d`,
  which moved the 1.2.0 "What's new" text into `PLAY_STORE_WHATS_NEW.md`'s History section
  (doc-only). The next release is therefore `1.2.1` (fixes only) or `1.3.0` (any new feature),
  whenever real work has accumulated — don't bump `versionCode`/`versionName` or create the
  `playstore-v*` tag without the user explicitly asking, since tagging happens at the moment of an
  actual Play Console upload they control.
- `PLAY_STORE_WHATS_NEW.md` (repo root) holds the actual Play Console "What's new" text — a
  polished, 500-character-limited public-facing draft for the *next* release, kept in sync with
  (but written very differently from) CLAUDE.md's internal engineering changelog. Its draft is
  currently a stub; the 1.2.0 text is in its History section. Add a line to the draft whenever new
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
- (Resolved, no longer open: the "cook button + fridge deduction" idea from memory is now built —
  see "Cook this recipe" in `RecipeDetailScreen.kt`, gated to Quantity mode.)

## Suggested next steps

- No release is due yet — nothing release-worthy has landed since `playstore-v1.2.0-3`. When work
  does accumulate, follow CLAUDE.md's routine (version bump → Play Console upload → tag → move the
  "What's new" draft into `PLAY_STORE_WHATS_NEW.md`'s History).
- Spot-check the ingredient-matching fixes' net effect on real recipe results if there's ever
  reason to doubt them — `IngredientMatcherTest.kt` covers each fix in isolation but nothing
  currently asserts on aggregate match-rate movement across the whole corpus.
