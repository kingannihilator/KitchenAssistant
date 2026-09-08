# Play Store "What's new" text

This is the release-notes text for Google Play Console's "What's new" field (Release →
Production/testing track → release details). **Keep this file updated as work accumulates** —
whenever a work session adds something release-worthy, add a line to the draft below; whenever a
release actually ships, move the published version into the History section and clear the draft
back to a stub for the next cycle. See `CLAUDE.md`'s "Release versioning and changelog routine"
for how this relates to `versionCode`/`versionName` and the `playstore-v*` git tags.

**Google Play's limit is 500 characters per language.** The draft below is checked against that
limit; count before publishing if you edit it (`wc -m`, or a quick `len()` in a scratch script —
emoji/accented characters can cost more than one character depending on encoding, so measure the
actual string, don't just eyeball it).

## Draft for the next release (1.2.0, unpublished)

```
Smarter fridge matching: fixed dozens of ingredient-recognition issues so more recipes can reach a full match (accented names, plurals, US/UK spelling like chile/chili, "butter or margarine"-style alternatives, and more).

Read-to-me directions are back, with adjustable speed and a step-by-step mode that pauses after each step so you can follow along while cooking.

Assorted recipe data fixes.
```

(396 characters — room to spare under the 500 limit if more lands before this ships.)

## History

Nothing published from this file yet — `1.1.0` (`playstore-v1.1.0-2`) predates this file's
existence, so there's no recorded "what's new" text for it here.

<!--
Template for adding an entry once a release ships:

### 1.2.0 (versionCode N, playstore-vX.Y.Z-N, YYYY-MM-DD)

<the exact text submitted to Play Console>
-->
