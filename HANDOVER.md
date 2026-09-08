# Project Handover

Written for a new Claude session picking up this repo cold. Read `CLAUDE.md` first (architecture,
build commands, recipe matching, corpus provenance) — this doc is about *recent history and open
threads*, not the app's overall shape. This file is meant to be kept current: update it (don't
just append) whenever a work session wraps up a notable chunk of work.

## Current release state

- Shipped: `versionCode 2` / `versionName "1.1.0"`, tagged `playstore-v1.1.0-2` (commit `2fe2d08`,
  2026-08-31).
- Unreleased since then: 30 commits as of this writing (see CLAUDE.md's "Release versioning and
  changelog routine" section for the full grouped breakdown). Two batches of substance:
  1. A large `IngredientMatcher.kt` accuracy pass (~13 commits) plus 4 commits patching bad rows
     directly in the bundled `recipe_database.sqlite`.
  2. Read-aloud (TTS) re-enabled with a speed control and step-by-step playback mode (6 commits).
- The read-aloud batch is a genuine new feature, not just bug fixes — CLAUDE.md flags this as a
  MINOR-bump candidate (`1.1.0` → `1.2.0`) whenever the user wants to cut that release. Nothing
  forces it; don't bump `versionCode`/`versionName` or create the `playstore-v*` tag without the
  user explicitly asking — that happens at the moment of an actual Play Console upload they
  control, not automatically.

## What just happened (most recent session)

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
`CONTENT_TYPE_SPEECH` → `CONTENT_TYPE_MUSIC` as a test and back again once the click persisted
either way — confirming it's the device's own TTS engine/voice, not something the app can fix, and
not worth further code changes. Also added a persisted speed control (`ReadAloudRepository`,
defaults to 0.85x) and a step-by-step playback mode (`ReadAloudPlaybackMode`) alongside the
original "read straight through" mode.

**Docs.** `CLAUDE.md`'s "Recipe matching" and "Release versioning" sections were both updated to
match the above; this file is new.

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

- Fridge row's units button should get an editable dropdown, matching the add-ingredient flow's
  autocomplete-style unit picker.
- Fridge delete-undo only supports one pending item at a time; user wants multi-undo considered.
- Obscure/non-food autocomplete entries (e.g. "dexpanthenol") can outrank common food words when
  both have zero recipe-corpus popularity signal — no clean fix identified yet.
- (Resolved, no longer open: the "cook button + fridge deduction" idea from memory is now built —
  see "Cook this recipe" in `RecipeDetailScreen.kt`, gated to Quantity mode.)

## Suggested next steps

- Whenever the user is ready: cut the `1.2.0` release (version bump + Play Console upload +
  `playstore-v1.2.0-<code>` tag), per CLAUDE.md's routine.
- Spot-check the ingredient-matching fixes' net effect on real recipe results if there's ever
  reason to doubt them — `IngredientMatcherTest.kt` covers each fix in isolation but nothing
  currently asserts on aggregate match-rate movement across the whole corpus.
