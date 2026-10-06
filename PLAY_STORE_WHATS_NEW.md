# Play Store "What's new" text

This is the release-notes text for Google Play Console's "What's new" field (Release →
Production/testing track → release details). **Keep this file updated as work accumulates** —
whenever a work session adds something release-worthy, add a line to the draft below; whenever a
release actually ships, move the published version into the History section and clear the draft
back to a stub for the next cycle. See `CLAUDE.md`'s "Release versioning and changelog routine"
for how this relates to `versionCode`/`versionName` and the `playstore-v*` git tags.

**Google Play's limit is 500 characters per language.** Each draft below is checked against that
limit; count before publishing if you edit it (`wc -m`, or a quick `len()` in a scratch script —
emoji/accented characters can cost more than one character depending on encoding, so measure the
actual string, don't just eyeball it).

## Drafts — next three releases (none shipped yet)

The 9/30 work was originally cut as a single 1.3.0. It never reached Play Console, so it is going
out as **three sequential uploads** instead; each has its own text below. Move each block to
History as its upload actually happens, and correct the text there if it gets edited first.

### 1.3.0 (versionCode 4, playstore-v1.3.0-4)

```
New: a short guided tour on first launch, a proper About menu, and a "Rate this app" link.

Also improves screen-reader support and survives larger system font sizes throughout the app.
```

### 1.4.0 (versionCode 5, playstore-v1.4.0-5)

```
Recipe matching fixes: a recipe that uses one of your starred ingredients twice no longer outranks a better match, and category matching no longer credits your fridge with ingredients you don't actually have.
```

### 1.4.1 (versionCode 6, playstore-v1.4.1-6)

```
Fixed: the app's name now appears correctly in the title bar.
```

## History

### 1.2.0 (versionCode 3, playstore-v1.2.0-3, 2026-09-21)

```
Smarter fridge matching: fixed dozens of ingredient-recognition issues so more recipes can reach a full match (accented names, plurals, US/UK spelling like chile/chili, "butter or margarine"-style alternatives, and more).

Read-to-me directions are back, with adjustable speed and a step-by-step mode that pauses after each step so you can follow along while cooking.

Assorted recipe data fixes.
```

`1.1.0` (`playstore-v1.1.0-2`) predates this file's existence, so there's no recorded
"what's new" text for it here.

<!--
Template for adding an entry once a release ships:

### X.Y.Z (versionCode N, playstore-vX.Y.Z-N, YYYY-MM-DD)

<the exact text submitted to Play Console>
-->
