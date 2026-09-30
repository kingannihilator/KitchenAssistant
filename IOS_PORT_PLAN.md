# Porting Fridge Grub to iOS (Kotlin Multiplatform + Compose Multiplatform)

> **Status: PLAN ONLY — nothing implemented.** No repository files were created or modified for
> this port. This document exists so a future session can pick it up cold.
>
> **How to resume:** read `CLAUDE.md` (architecture, matching, corpus) and `HANDOVER.md` (recent
> history, open threads) first, then start at Stage 0. Three decisions are already settled with the
> user and do not need re-litigating: **share the UI via Compose Multiplatform**, **migrate
> incrementally** (the Android app must keep building and shipping after every stage), and **Mac
> access is undecided** (so the plan targets cloud/CI). The user has explicitly *not* approved
> beginning work — ask before starting Stage 0.
>
> **The single most important fact:** iOS binaries cannot be produced on this Windows machine.
> See "The hard constraint" below.

## Context

Fridge Grub is live on Play Store at 1.2.0 — a single-module Jetpack Compose Android app with no
iOS presence. The goal is an iOS app built from the same codebase, structured so a change made once
lands on both platforms rather than forking into two codebases that drift.

This project is unusually well positioned for that, for three reasons:

1. **Every line of UI is already Compose** (4,501 lines across 19 files) with zero use of Android
   Views, `android.graphics`, or any bundled image/font asset — all the art (mascot, open fridge,
   splash, scrollbar) is drawn in Compose `Canvas`/`DrawScope`. Compose Multiplatform can carry the
   UI across, which is the difference between one UI codebase and two.
2. **The highest-churn logic is already platform-independent.** `IngredientMatcher` (600 lines, an
   accuracy pass touched it ~13 times before 1.2.0) has no Android imports.
3. **The bundled data is already one shared artifact.** `recipe_database.sqlite` (7.27 MB, 4,779
   recipes) and `ingredients.db` (741 KB) are single git-tracked files. Corpus fixes reach both
   platforms at once — and there have been several dozen hand-patched rows.

### The hard constraint, stated plainly

**iOS apps cannot be built, run, or debugged from Windows.** Kotlin/Native can cross-compile
*library* klibs from any host behind an experimental flag, but final binaries for Apple targets,
cinterop, and CocoaPods work all require a macOS host with Xcode. The Kotlin Multiplatform plugin
for Android Studio is itself macOS-only. On Windows you can write and test all the shared code; you
cannot run the iOS app.

The practical consequence: **every iOS-specific change is verified through CI, not on your desk.**
That argues for keeping the iOS-specific surface as small as possible — exactly what maximum code
sharing buys. Stages 3–8 (the bulk of the work) are verifiable on Windows; only Stages 9–10 are
CI-only.

---

## Target layout

**Three Gradle modules plus one Xcode project.** Module location and Kotlin package are
independent, so **package names never change** — every file keeps
`com.pancakeworks.fridgegrub.*`, which means zero import churn across the moves.

```
KitchenAssistant/
├── settings.gradle.kts                 # include(":core", ":shared", ":androidApp")
├── gradle/libs.versions.toml
│
├── core/                               # KMP. NO Compose, NO Room, NO Android plugin.
│   ├── src/commonMain/.../             # model/, matcher, indexes, ranking, metadata
│   ├── src/{androidMain,iosMain}/      # 2-3 tiny actuals
│   └── src/commonTest/                 # the 4 pure test files (1,310 lines)
│
├── shared/                             # KMP + Compose Multiplatform + Room
│   ├── src/commonMain/.../             # App(), ui/ (19 files), viewmodel/, data/, platform/
│   ├── src/androidMain/                # Context/SharedPreferences/TTS/asset actuals
│   ├── src/iosMain/                    # NSUserDefaults/AVSpeechSynthesizer/bundle actuals
│   └── (assets staged from root)
│
├── androidApp/                         # com.android.application, thin shell
│   └── MainActivity.kt, AndroidManifest.xml, res/, keystore.properties
│
├── iosApp/                             # Xcode project (NOT a Gradle module)
├── assets/database/                    # canonical copy of the two .sqlite files
└── porting-reference/, new_db_workable/  # unchanged, stay at root
```

### Why `:core` is a separate module

Not the JetBrains `sharedLogic`/`sharedUI` split, which buys nothing with only two targets. A
`:core` split buys something specific to *this* codebase:

- `:core` has **no Compose compiler, no Android plugin, no Room, no platform toolchain** — so its
  tests run on a plain JVM in seconds on Windows, and it structurally *cannot* acquire a platform
  dependency. That matters because `IngredientMatcher` is the file most likely to keep changing.
- It makes the port's riskiest logic independently testable on the iOS simulator before any UI moves.

If `:core` later feels like overhead, the fallback is cheap: move its files into
`shared/src/commonMain` and delete the module. Nothing else changes.

**`:core` targets:** `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`. Skip `iosX64` — Apple
Silicon CI runners and current Xcode make Intel Macs irrelevant. Include `androidTarget()` and not
just `jvm()` so `:shared`'s `androidMain` resolves a proper Android variant.

**ViewModels stay in `:shared`**, not `:core`: they need `viewModelScope` and the platform storage
graph, which lives in `:shared`.

### The module split is mandatory, not stylistic

AGP 9.0+ removed support for one module being both a Kotlin Multiplatform module and an Android
application. The project is already on **AGP 9.3.3**, so `shared` (KMP library) + `androidApp`
(plain Android app) is the only available shape.

---

## Tools

### Install now (Windows, today)

| Tool | Why |
|---|---|
| Android Studio | Already have it. Still right for Android work and for editing `commonMain` — it indexes Kotlin without any plugin. |
| JDK 21 | Already configured via the foojay toolchain. No change. |

Note what you **cannot** usefully install on Windows: the Kotlin Multiplatform IDE plugin (project
wizard, iOS run configurations, Swift navigation) is macOS-only. On Windows you write shared code in
a plain project view and let Gradle/CI do the rest.

### Add when you have a Mac

| Tool | Why |
|---|---|
| **Xcode** | Non-negotiable — builds, runs, signs, archives. |
| **Kotlin Multiplatform plugin** (Android Studio / IntelliJ) | iOS simulator run configs, Swift navigation, cross-language debugging. |
| **kdoctor** | JetBrains' KMP environment sanity-checker. Catches Xcode/JDK misconfiguration before it wastes an afternoon. |
| **CocoaPods** | **Skip.** Use Gradle *direct integration* (`embedAndSignAppleFrameworkForXcode`). CocoaPods manages third-party Pod dependencies; this app has none, so it would add a Ruby toolchain requirement on both Mac and CI for zero benefit. |
| **Apple Developer Program** — $99/yr | Needed for TestFlight and the App Store. A free Apple ID sideloads to your own devices only, with 7-day provisioning profiles. |

### CI — this is your iOS build machine

There is **no CI in this repo today**, and that is the first thing to fix. Staging a restructure of
a live app with no regression net is the single biggest process risk here.

| Option | Cost | Notes |
|---|---|---|
| **GitHub Actions** | macOS ARM64 ≈ $0.102/min vs Linux $0.022/min; 2,000 free min/month shared across runners | ~35 min per CMP iOS build, billed at ~10× Linux. A few builds exhaust the free allotment. |
| **Codemagic** | Free tier: **500 macOS M2 min/month**, permanent | Denominated in macOS minutes, so it stretches much further for this exact workload — likely covers the entire migration at zero marginal cost. |

**Recommended split:** GitHub Actions for the cheap Linux/Android gate, Codemagic for iOS. Only pay
GitHub's macOS rate if you exceed the free tier.

---

## Migration stages

Each stage ends with the Android app building and shippable. Every stage should be its own commit
or small commit series, and each is independently revertible.

| Stage | Change | Risk |
|---|---|---|
| 0 | CI + baseline rollback tag | none |
| 1 | Kotlin 2.2.10 → 2.2.20, alone | R2 |
| 2 | Bump Android Compose off the stale BOM | R12 |
| 3 | Extract `:core` as a **plain Kotlin/JVM library** (zero code change) | trivial |
| 4 | `:core` → KMP; 2 expect/actual; tests → `commonTest` | R3 |
| 5 | `:androidApp` + `:shared` split (mechanical, no code change) | build config only |
| 6 | Shared `App()` shell | trivial |
| 7 | Settings / serialization / Room / `AppContainer` | **R1, R4** |
| 8 | Compose Multiplatform; UI → `commonMain`; substitutions | **R3, R5, R6, R7** |
| 9 | `iosApp/` + macOS CI | R8, R9, R10 |
| 10 | iOS TTS + store polish | R16 |

### Stage 0 — CI and a rollback tag

- Tag the current commit as the last known-good shipping state.
- Add `.github/workflows/ci.yml` with a Linux job: `./gradlew test assembleDebug`. This exists
  **before** any migration so every later stage has a cheap fail-fast gate.
- **Verify:** green on the untouched app.

### Stage 1 — Bump Kotlin, isolated

- `libs.versions.toml`: `kotlin = "2.2.10"` → **`"2.2.20"`**. Nothing else changes.
- The Compose compiler plugin tracks `version.ref = "kotlin"` automatically — leave it alone.

**Isolate this deliberately.** A Kotlin bump on the *current single-module Android app*, with no KMP
anything, means any breakage is unambiguously the Kotlin bump. Do not combine it with Stage 2.

The 2.2.20 figure is a *recommendation* for iOS/web, not a hard floor — CMP's floor is Kotlin 2.1.0.
Bump anyway: the cost is one commit, and the alternative is debugging iOS-only oddities you cannot
reproduce locally. **Do not jump further** (2.3/2.4/2.5) — each extra minor multiplies the alignment
surface with AGP 9.3.3 and KSP for no gain you need.

- **Verify:** `gradlew.bat test assembleRelease`.

### Stage 2 — Modernize Android Compose (still single-module)

The Compose BOM is `2024.09.00` (Compose ~1.7.x). CMP 1.12.1 ships Compose 1.12.1 — **five minor
versions ahead**. Bump the BOM and fix deprecations across 4,501 lines of UI *while still on a plain
Android module*.

Doing this bump at the same time as switching to CMP and moving UI to `commonMain` would give three
entangled failure causes. Doing it here converts a scary unknown into a boring diff.

- **Could break:** `TopAppBar`/`ExperimentalMaterial3Api`, icon deprecations, `TextUnit`/`Dp` drift.
- **Verify:** build + manual smoke of every screen.

### Stage 3 — Extract `:core` as a plain Kotlin/JVM library

Create `:core` with `kotlin("jvm")` only — **not** multiplatform yet. Move the 11 pure files and
their 3 test files into it; `:app` gets `implementation(project(":core"))`.

**This compiles unchanged**, because `java.text.Normalizer` and `java.util.UUID` are plain JVM APIs
and a Kotlin/JVM library is a valid Android dependency. That is the point: prove the extraction
before touching any platform code.

- **Could break:** `internal` visibility across the new module boundary. `IngredientViewModel.rankSuggestions`
  is `internal` on a companion, as are some `RecipeRanking`/`RecipeMetadata` members. Promote to
  `public` if needed — mechanical.

### Stage 4 — Make `:core` multiplatform

- Replace `kotlin("jvm")` with `kotlin("multiplatform")` + the three targets.
- Add `expect`/`actual` for the two JDK APIs:

| File | Problem | Fix |
|---|---|---|
| `data/IngredientMatcher.kt:3`, `:242` | `Normalizer.normalize(raw, NFD)` + combining-mark strip — one line, but load-bearing for match accuracy | `expect fun foldDiacritics(String): String`. **Prefer a common codepoint-decomposition table over platform actuals** so both platforms are byte-identical by construction (see R3). |
| `model/Ingredient.kt:3`, `:27` | `UUID.randomUUID().toString()` as a **default parameter value** | `expect fun randomUuidString(): String`. iOS `NSUUID().UUIDString` is uppercase — lowercase it to match. Default params can call `expect` functions. |

- Move the 4 test files (1,310 lines) to `core/src/commonTest`, converting JUnit 4 → `kotlin.test`.
- Extract `IngredientViewModel.rankSuggestions` (`IngredientViewModel.kt:63-76`) into
  `core/.../suggestions/IngredientSuggestionRanker.kt` so the 95-line `IngredientViewModelTest` can
  move too. That function was already written Application-free for testability — this matches
  existing design intent.

**Windows caveat:** adding Apple targets does **not** break Android builds — the Kotlin plugin
disables targets the host cannot build and skips their tasks. If configuration balks, set
`kotlin.native.ignoreDisabledTargets=true`. Do **not** enable
`kotlin.native.enableKlibsCrossCompilation` — it produces klibs, not binaries, and adds risk.

**This is the milestone that matters most:** the common test suite now running green on both JVM and
the iOS simulator proves the crown-jewel logic is genuinely portable before a single line of UI
moves.

### Stage 5 — Mechanical module split

A pure file shuffle with **zero edits**, so that if it breaks it is unambiguously build config.

- Rename `:app` → `:androidApp` (directory `app/` → `androidApp/`); update `settings.gradle.kts`.
- Create `:shared` as a KMP module with **`androidTarget()` only** — no iOS targets, no Compose
  Multiplatform plugin yet.
- Move everything except `MainActivity.kt`, `AndroidManifest.xml`, `res/`, `proguard-rules.pro`, and
  signing config into `shared/src/androidMain/` (the `data/`, `model/`, `viewmodel/`, `ui/` trees
  land verbatim — they still use Android APIs and the androidx Compose artifacts).
- Both `.sqlite` files move to `shared/src/androidMain/assets/` — a library module's assets merge
  into the consuming APK, so `createFromAsset("database/recipe_database.sqlite")` keeps working.

**`applicationId` and `namespace` must not change** — changing them breaks Play Store continuity and
every user's saved data.

- **Could break:** the `shared` KMP module needs `compileSdk`/`minSdk` in its `androidTarget` block;
  `buildFeatures.compose = true` is not needed on a KMP module; `kotlinOptions.jvmTarget` (used in
  the current build script) must become `compilerOptions`/`jvmTarget` on the Android target.
- **Verify:** build + manual smoke + the upgrade-in-place data test (see Verification).

### Stage 6 — Shared app shell

- Move `sealed class Screen` (`MainActivity.kt:24-66`) and the whole `setContent { }` body
  (`:72-151`) into `App.kt` as `@Composable fun App(...)`. `Screen` references only `model.Recipe`
  and is pure Kotlin.
- `MainActivity` shrinks to `ComponentActivity` + `enableEdgeToEdge()` + `setContent { Theme { App() } }`.

**Why here:** a single `App()` owning the whole UI tree is the precondition for the iOS entry point
(`ComposeUIViewController { App() }`). Doing it while Android-only is trivial; doing it mid-`commonMain`
move is not.

### Stage 7 — Persistence and DI abstraction (still androidTarget-only; Android still ships)

The largest non-UI stage, and fully testable on Windows.

1. **`AppContainer`** — hand-rolled DI, no framework. Holds `Settings`, the repositories, the
   `AssetStore`, the `SpeechSynthesizer`, `AppPaths`. Add `expect fun createSettings(): Settings` and
   `expect fun createAppPaths(): AppPaths`.
2. **SharedPreferences → multiplatform-settings.** Each of the 5 repositories changes constructor
   from `(context: Context)` to `(settings: Settings)`. **Keep the same preference file names**
   (`fridge_prefs`, `favorites_prefs`, `pantry_prefs`, `read_aloud_prefs`, `app_mode_prefs`) — on
   Android that means `SharedPreferencesSettings(context.getSharedPreferences("fridge_prefs", MODE_PRIVATE))`,
   so **existing installs keep their data verbatim**.
   *Why multiplatform-settings over DataStore:* the 5 repositories are synchronous read/write with no
   reactive layer, and `Settings` mirrors that API exactly, so the diff is mechanical. DataStore is
   suspend/`Flow`-based and would force a rewrite of all 5 repositories plus call sites for a
   capability the ViewModels already provide by layering `StateFlow` on synchronous reads.
3. **`org.json` → kotlinx.serialization** in `FridgeRepository.kt`. Same key names,
   `Json { ignoreUnknownKeys = true; encodeDefaults = true }`. **Data-continuity hazard — see R4.**
4. **`BundledDatabase` → common `AssetStore`** built on **okio** (`Path`, `FileSystem.SYSTEM`) so the
   copy/verify logic is common code. Replace `java.security.MessageDigest` (`BundledDatabase.kt:83-92`,
   no iOS equivalent) with a **pure-Kotlin SHA-256 in `:core`** — ~80 lines, unit-testable against
   known vectors. Keep the `$dbFile.sha256` sidecar and the `-wal`/`-shm` deletion semantics.
5. **Room KMP.** `.setDriver(BundledSQLiteDriver())`; replace `SupportSQLiteQuery`/`SimpleSQLiteQuery`
   with `RoomRawQuery` at all 7 DAO sites and `RecipeViewModel.kt:616`.
6. **`AndroidViewModel` → `ViewModel`** with an `(container: AppContainer)` constructor; replace
   `getApplication()` uses with container fields. Update the 6 `viewModel()` call sites to
   `viewModel { ... }` via `LocalAppContainer`.
7. **`android.util.Log`** (`RecipeViewModel.kt:4`) → `expect fun logDebug(tag, msg)`.
8. **`READ_ALOUD_ENABLED`** (`RecipeDetailScreen.kt:101`, a hardcoded `const`, not a Gradle flag) →
   `expect val platformSupportsReadAloud: Boolean`, so iOS can ship with read-aloud off and enable it
   in Stage 10.

### Stage 8 — Compose Multiplatform

- Apply `org.jetbrains.compose` to `:shared`; add the two iOS targets.
- Move `ui/` (19 files) and `viewmodel/` from `androidMain` → `commonMain`.
- Do the substitutions in the table below. Consider splitting into two commits: (8a) theme and the
  small art/leaf files (`Mascot.kt`, `FridgeIllustration.kt`, `RollingDoughIllustration.kt`,
  `RunningMascotScene.kt`, `Scrollbar.kt`, `LoadingScreen.kt`, `EmptyFridgeMessage.kt`,
  `RecipeEmptyMessage.kt`, `PeekingMascot.kt`, `FavoritesShortcutIcon.kt`, `AboutScreen.kt`,
  `theme/`) before (8b) the four large screens.
- Add `shared/src/iosMain/.../MainViewController.kt`:
  `fun MainViewController(): UIViewController = ComposeUIViewController { App() }`.

### Stage 9 — `iosApp` and direct framework integration

### Stage 10 — iOS TTS and store polish

---

## API substitution table

Line numbers verified against the current tree.

| # | Location | Current Android API | Replacement | Notes |
|---|---|---|---|---|
| 1 | `data/IngredientMatcher.kt:3, :242` | `java.text.Normalizer` NFD + combining-mark strip | `expect fun foldDiacritics(String): String` | See R3 — prefer a common codepoint table for byte-identical parity. |
| 2 | `model/Ingredient.kt:3, :27` | `UUID.randomUUID().toString()` **as a default param** | `expect fun randomUuidString(): String` | Lowercase iOS `NSUUID` output. |
| 3 | `data/FridgeRepository.kt:5-6` | `org.json.JSONArray`/`JSONObject` | kotlinx.serialization | **Preserve key names exactly — R4.** |
| 4 | 5 repos, ctor `(context: Context)` | `SharedPreferences` | multiplatform-settings `Settings` | Keep pref file names. `FavoritesRepository`'s `Set<String>` + lock → `getStringSet`/`putStringSet`; keep the lock. |
| 5 | `data/NewRecipeDao.kt:7-8, :111, :121, :137, :151, :178, :181` | `SupportSQLiteQuery` in `@RawQuery` | `androidx.room.RoomRawQuery` | **VERIFY exact class/ctor and `bindArgs` support in Room 2.8.4.** |
| 6 | `data/NewRecipeDao.kt:116, :127, :144, :160, :192`; `RecipeViewModel.kt:7, :616` | `SimpleSQLiteQuery(...)` | `RoomRawQuery(sql, bindArgs)` | If `bindArgs` is missing, these sites already interpolate typed IDs — ergonomic, not a security issue. |
| 7 | `data/NewRecipeDatabase.kt` | `createFromAsset("database/recipe_database.sqlite")` | `.setDriver(BundledSQLiteDriver())` + shared pre-copy | **Android-only API; biggest iOS unknown — R1.** |
| 8 | `data/BundledDatabase.kt:3, :43-48` | `Context` + `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` | okio-based common `AssetStore` + `expect` copy; open via driver | Keep read-only semantics via the driver flag. |
| 9 | `data/BundledDatabase.kt:6,7` | `java.io.File`, `InputStream` | okio `Path`/`FileSystem.SYSTEM` | okio already in graph via multiplatform-settings. |
| 10 | `data/BundledDatabase.kt:7, :83-92` | `java.security.MessageDigest` SHA-256 | Pure-Kotlin SHA-256 in `:core` | No iOS equivalent. |
| 11 | `ui/RecipeDetailScreen.kt:3-5, :176, :198, :237, :249-252, :255` | `TextToSpeech`, `UtteranceProgressListener`, `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH)` | `expect interface SpeechSynthesizer` + actuals, injected via `AppContainer` | Android actual wraps existing code unchanged. iOS: `AVSpeechSynthesizer` + delegate (word-boundary callbacks map to `UtteranceProgressListener`), `AVSpeechUtterance.rate`, and **`AVAudioSession` category `.playback`** or the silent switch mutes it. |
| 12 | `ui/RecipeDetailScreen.kt:238` | `Locale.getDefault()` | `expect fun defaultLanguageTag(): String` | iOS: `NSLocale.currentLocale.languageCode`. |
| 13 | `ui/RecipeDetailScreen.kt:76, :164` | `LocalContext.current` | removed — TTS handle from `AppContainer` | Strict simplification. |
| 14 | `viewmodel/RecipeViewModel.kt:4` | `android.util.Log` | `expect fun logDebug(tag, msg)` | — |
| 15 | **`ui/IngredientScreen.kt:121, :177, :780, :1032`** | **`SimpleDateFormat("MM/yy", ...)`, including as a function parameter type** | kotlinx-datetime + common `formatMonthYear(epochMillis)` | **Missed by the first inventory pass — largest uncounted substitution.** Params at `:780`/`:1032` become a common formatter type or a `(Long) -> String` lambda. |
| 16 | **`ui/IngredientScreen.kt:122, :1527-1534, :1584-1586`** | **`java.util.Calendar`** — year range, month/year picker state, millis zeroing | kotlinx-datetime `LocalDate` / `TimeZone.currentSystemDefault()` | Extract the picker arithmetic into a pure, unit-tested function in `:core`. |
| 17 | `ui/IngredientScreen.kt:123` | `java.util.Date` | kotlinx-datetime `Instant` | — |
| 18 | `ui/theme/Theme.kt:3` | `android.app.Activity` import | delete (unused) | Free. |
| 19 | `ui/theme/Theme.kt:12, :44-45` | `LocalContext` + `Build.VERSION.SDK_INT >= S` + dynamic color | `expect fun platformDynamicColorScheme(dark): ColorScheme?` | Android actual keeps the S+ check; iOS returns `null`, falling through to the existing static schemes. |
| 20 | `ui/PantryScreen.kt:3`, `RecipeDetailScreen.kt:6`, `FavoritesScreen.kt:3`, `RecipeScreen.kt:3` | `androidx.activity.compose.BackHandler` | CMP `BackHandler`, then **`NavigationBackHandler`** (`navigationevent-compose`) | `ui-backhandler`'s `BackHandler` was **deprecated in CMP 1.10+** and you'll be on 1.12.x. **VERIFY the exact current package/API — do not guess.** Navigation here is a hand-rolled `sealed class Screen`, not `NavHost`, so the iOS back gesture must be wired deliberately. |
| 21 | 6 sites: `MainActivity.kt:74`, `IngredientScreen.kt:158`, `PantryScreen.kt:55`, `RecipeDetailScreen.kt:119,120`, `FavoritesScreen.kt:68`, `RecipeScreen.kt:118` | `viewModel()` via `AndroidViewModelFactory` | KMP `lifecycle-viewmodel-compose` `viewModel { }` with a factory reading `LocalAppContainer` | **Fallback:** hoist both ViewModels into `App()` and pass as parameters — 6 edits, zero platform API, arguably cleaner given the hand-rolled navigation. |
| 22 | `viewmodel/IngredientViewModel.kt:3, :41`; `RecipeViewModel.kt` class decl | `Application` + `AndroidViewModel` | `ViewModel(container: AppContainer)` | — |
| 23 | `ui/RecipeDetailScreen.kt:101` | `private const val READ_ALOUD_ENABLED = true` | `expect val platformSupportsReadAloud: Boolean` | — |
| 24 | `ui/*` `navigationBarsPadding` (2 sites) | androidx insets | CMP WindowInsets | iOS home-indicator handling differs; verify on a notched device. |
| 25 | `MainActivity.kt:3-6, :71` | `ComponentActivity`, `setContent`, `enableEdgeToEdge` | **stays in `:androidApp`** | iOS entry is `ComposeUIViewController { App() }`. |
| 26 | `AndroidManifest.xml` `<queries>` TTS_SERVICE | — | **stays in `:androidApp`** | **Do not "clean this up"** — it fixes a real OEM engine-discovery bug (`RecipeDetailScreen.kt:99`). |

Everything else in the UI is genuinely CMP-portable, including all the custom art (`Canvas`,
`DrawScope`, `Path`, `Stroke`, `rememberTextMeasurer`/`drawText`) — which is why this app is a good
CMP candidate. `dynamicLight/DarkColorScheme` (row 19) is the only Android-only Material3 API.

---

## Dependency and version changes

```toml
[versions]
agp = "9.3.3"                     # unchanged
kotlin = "2.2.20"                 # was 2.2.10 — Stage 1
ksp = "2.3.6"                     # unchanged, VERIFY against Kotlin 2.2.20
composeMultiplatform = "1.12.1"   # NEW — replaces the BOM for shared UI
composeBom = "<2026.x>"           # androidApp only; VERIFY, must be >= Compose 1.12.1
room = "2.8.4"                    # unchanged — KMP since 2.7.0
androidxSqlite = "<2.6.x>"        # NEW — sqlite-bundled. VERIFY
lifecycle = "<2.9.x>"             # NEW — JetBrains KMP lifecycle artifact. VERIFY vs CMP 1.12.1
kotlinxSerialization = "<1.9.x>"  # NEW — VERIFY
kotlinxDatetime = "<0.7.x>"       # NEW — VERIFY
multiplatformSettings = "<1.3.x>" # NEW — VERIFY
okio = "<3.x>"                    # NEW — VERIFY
coroutines = "<1.10.x>"           # NEW — VERIFY
```

```toml
[plugins]
kotlin-multiplatform  = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization  = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
android-library       = { id = "com.android.library", version.ref = "agp" }
# keep: android-application, kotlin-android, kotlin-compose, ksp
```

**Critical:** the Compose Compiler plugin (`kotlin-compose`) must stay pinned to
`version.ref = "kotlin"` — JetBrains states this explicitly, and the catalog already does it. Do not
let a future editor give it the `composeMultiplatform` version.

**`:shared` dependency notes:** use the CMP plugin DSL accessors (`compose.runtime`, `compose.foundation`,
`compose.material3`, `compose.ui`, `compose.components.resources`, `compose.materialIconsExtended`)
and **drop the Compose BOM entirely** inside `:shared`. **Remove `androidx.room:room-ktx`** —
coroutines support moved into `room-runtime` in the KMP line and the ktx artifact is Android-only;
keep `ksp(room-compiler)` and drop `room-compiler` from `implementation`.

### Version-alignment risks

| Risk | Detail | Action |
|---|---|---|
| **AGP 9.3.3 ↔ Kotlin 2.2.20** | AGP 9 is very new and uses the new DSL (`compileSdk { version = release(36) }`). It has a supported-Kotlin range for its integrations even with `android.builtInKotlin=false` (set in `gradle.properties:32`). | Highest-uncertainty item. Bump Kotlin in isolation (Stage 1), prove `assembleRelease`, keep a rollback tag. |
| **KSP 2.3.6** | `2.3.6` is the KSP2 standalone-versioning line, decoupled from the Kotlin version — good, but its supported Kotlin *range* must include 2.2.20. | VERIFY against KSP release notes. A KSP bump cascades into Room. |
| **Room 2.8.4** | Already KMP and KSP-based (no KAPT anywhere). | Well positioned. **Avoid Room 3.0 alpha** — it renames packages to `androidx.room3.*` and would churn every DAO/entity for no benefit. |
| **Compose BOM vs CMP** | BOM `2024.09.00` is Compose ~1.7.x vs CMP's 1.12.1. | Stage 2 bumps it on Android alone; `:shared` drops the BOM for CMP accessors. |
| **Gradle 9.5.0** | Ahead, not behind. | None. |
| **`material-icons-extended`** | Deprecated/frozen upstream; CMP's equivalent follows. | ~25 icons used; vendor as `ImageVector`s if the artifact disappears. |

---

## The iOS app skeleton

### Integration: direct, not CocoaPods

```
iosApp/
├── iosApp.xcodeproj/
├── Configuration/Config.xcconfig        # BUNDLE_ID, TEAM_ID, APP_NAME
└── iosApp/
    ├── iOSApp.swift                     # @main SwiftUI App
    ├── ContentView.swift                # UIViewControllerRepresentable -> MainViewController()
    ├── Info.plist
    └── Assets.xcassets/                 # AppIcon, AccentColor
```

- Build phase **"Compile Kotlin Framework"**, before "Compile Sources":
  `cd "$SRCROOT/.." && ./gradlew :shared:embedAndSignAppleFrameworkForXcode`, with
  `FRAMEWORK_SEARCH_PATHS` pointing into `shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`.
- Set the iOS framework **`isStatic = true`** — nothing to embed or code-sign separately, which
  simplifies direct integration.
- `ContentView` returns `MainViewControllerKt.MainViewController()`. `iOSApp`'s body is
  `WindowGroup { ContentView().ignoresSafeArea(.all) }` — the `ignoresSafeArea` matters, or Compose
  gets double safe-area padding since it draws its own insets.

### Deployment target and Info.plist

- **iOS 15.0.** CMP's floor is iOS 14; 15.0 is safely inside support and buys modern UIKit APIs.
- `CFBundleDisplayName = Fridge Grub`; `CFBundleName = FridgeGrub`.
- `CFBundleShortVersionString = 1.2.0` (mirrors Android `versionName`), `CFBundleVersion = 1`
  (independent of Android's `versionCode = 3` — document that these now diverge).
- **`UILaunchScreen`** — a dictionary, even empty, is required on modern iOS or the app is letterboxed.
- `UIApplicationSceneManifest` with `UIApplicationSupportsMultipleScenes = false`.
- `ITSAppUsesNonExemptEncryption = false` — no cryptography beyond SHA-256 hashing, so this avoids
  the export-compliance prompt on every upload.
- `LSRequiresIPhoneOS = true`.

### Getting the SQLite files into the iOS bundle

Keep **one canonical copy in git** (`assets/database/`) and stage it into both builds, so the 7.27 MB
corpus cannot drift. Either a Gradle `Sync` task staging into `iosApp/iosApp/Resources/`, or
`sourceSets` + an Xcode "Copy Bundle Resources" entry. Add a **checksum test** asserting the two
staged copies are byte-identical.

Do **not** route the DB through `compose.resources` — it needs a real file path, not a `Res` stream.

iOS runtime flow mirrors `BundledDatabase` exactly: locate the read-only bundle resource via
`NSBundle.mainBundle.pathForResource`, copy into Application Support via the shared okio
`AssetStore`, SHA-256 verify against the sidecar, delete stale `-wal`/`-shm`, open via
`BundledSQLiteDriver`. Only path resolution is new — the copy-then-open design is already correct
for iOS.

### Complete iOS `actual` set

`:core/iosMain`: `foldDiacritics`, `randomUuidString` (or both live in `commonMain`).
`:shared/iosMain`: `createSettings` (NSUserDefaults), `createAppPaths` (`NSFileManager` Application
Support), asset-path resolution, `SpeechSynthesizer` (AVSpeechSynthesizer), `platformDynamicColorScheme`
→ `null`, `logDebug` (NSLog), `defaultLanguageTag` (NSLocale), `platformSupportsReadAloud`, and
`MainViewController()`.

**No `actual` is needed for Room** — `BundledSQLiteDriver` covers both platforms.

---

## Verification

### Windows does logic and Android; macOS does iOS

| What | Where | Command |
|---|---|---|
| `:core` common tests (1,310 lines) on JVM | Windows + Linux CI | `gradlew.bat :core:testDebugUnitTest` |
| `:core` common tests on **iOS sim** | macOS CI | `./gradlew :core:iosSimulatorArm64Test` |
| `:shared` common tests | Windows + macOS | `:shared:testDebugUnitTest` / `:shared:iosSimulatorArm64Test` |
| Android debug/release build | Windows + Linux CI | `:androidApp:assembleDebug`, `:androidApp:assembleRelease` |
| Android manual smoke | Windows | All 8 screens + cook mode + read-aloud |
| **Upgrade-in-place data test** | Windows | **Stages 5 and 7, mandatory** |
| iOS framework + app build | macOS CI or a Mac | `xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,...'` |
| iOS manual smoke | Mac or Codemagic | Stages 9–10 |

**Migrating the tests:** `IngredientMatcherTest.kt` (665), `RecipeRankingTest.kt` (390),
`RecipeMetadataTest.kt` (160), `IngredientViewModelTest.kt` (95) → `core/src/commonTest`; JUnit 4 →
`kotlin.test`. Delete the `ExampleUnitTest.kt` template; move `ExampleInstrumentedTest.kt` to
`:androidApp` or delete it.

The payoff: one source runs on three configurations (JVM host, iOS simulator arm64, CI Linux). Given
that the matcher is the highest-churn code here, having the full 665-line suite execute on the iOS
simulator is the mechanism that actually de-risks the port.

**Two new common tests to add:**

- `DiacriticsParityTest.kt` — golden vectors ("jalapeño", "crème fraîche", "purée", "pâté",
  "München") asserting identical fold output on every platform. This is how R3 gets caught before
  users do.
- `FridgeJsonRoundTripTest.kt` — parse a **captured real** `fridge_prefs` value written by the
  current `org.json` code and round-trip it through kotlinx.serialization, asserting no field loss.
  This is how R4 gets caught.

**The upgrade-in-place test is the single most important manual test of the migration.** Install the
current Play build, add fridge/favorites/pantry data, install the new build *over it*, confirm the
data survives. Run it at Stages 5 and 7 deliberately, not by assumption.

### CI shape

Three jobs in `.github/workflows/ci.yml`:

1. **`android-and-jvm`** — `ubuntu-latest`. `:core:testDebugUnitTest :shared:testDebugUnitTest
   :androidApp:assembleDebug`. Cheap, every push.
2. **`ios`** — `macos-latest`, **path-filtered** on `shared/**`, `core/**`, `iosApp/**`, `gradle/**`,
   and **gated on job 1 passing**. Runs the iOS-simulator tests and `xcodebuild`.
3. **`release`** — tag-triggered; `:androidApp:bundleRelease` with keystore from secrets.

**Gating iOS behind the cheap job is the biggest cost lever.** No expensive macOS minute should ever
be spent on a compile error Linux would catch in 90 seconds. Also: cache Gradle **and `~/.konan`**
(the Kotlin/Native toolchain is ~1 GB and dominates cold runs), set `timeout-minutes`, and enable
`concurrency: cancel-in-progress` so a push storm can't burn minutes.

**Route iOS to Codemagic's free 500 macOS M2 min/month** — likely covers the entire migration at zero
marginal cost.

**Where testing cannot happen:** the iOS app (CI-only without a Mac); instrumented Android tests; and
**read-aloud playback** — per standing project feedback the user tests TTS themselves, on both
platforms.

---

## Risk register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| **R1** | **`createFromAsset` is Android-only.** Room KMP's builder may expose no prepackaged-asset opening on iOS, and the whole 4,779-recipe corpus loads through it. `recipe_ingredients` is deliberately not an `@Entity` (nullable `position` in the composite PK), so all access is `@RawQuery` — there is no generated fallback path. | **High** | Don't assume. Pre-copy the DB with the shared `AssetStore` and open from a writable path (the pattern `BundledDatabase` already implements). **Verify `RoomRawQuery`'s signature and any asset/file creation API in 2.8.4 before writing code.** Prototype against a scratch DB in Stage 7 and get it running on the simulator in Stage 8 — not Stage 9. |
| **R2** | **Version alignment:** Kotlin 2.2.20 × KSP 2.3.6 × AGP 9.3.3 × Room 2.8.4 × CMP 1.12.1. Bad combinations fail in ways that look like migration bugs. | **High** | Bump Kotlin **alone** (Stage 1) and prove `assembleRelease`. Verify KSP 2.3.6's Kotlin range first. Keep a rollback tag. |
| **R3** | **`foldDiacritics` parity.** `java.text.Normalizer` NFD has no exact iOS twin; divergence would silently degrade matching accuracy **only on iOS** — in the code the user is actively tuning. | **High** | Implement the fold **once in `commonMain`** as an explicit codepoint-decomposition table so both platforms are byte-identical by construction. Guard with the 665-line matcher suite + the new parity test. The replaced surface is one line (`IngredientMatcher.kt:242`). |
| **R4** | **Data continuity.** `FridgeRepository` writes JSON via `org.json`; kotlinx.serialization can silently drop fields or fail to parse existing values, wiping fridges on upgrade. `FavoritesRepository`'s `Set<String>` has no native `NSUserDefaults` type. | **High** | Preserve pref file names, key names, and JSON key names exactly; `ignoreUnknownKeys = true`, `encodeDefaults = true`. Capture a real stored JSON value as a fixture and round-trip it. Run the upgrade test manually. Verify the `Set<String>` round-trip explicitly. |
| **R5** | **The `IngredientScreen.kt` date subsystem** — `SimpleDateFormat` as a parameter type at 2 sites, `Calendar` picker math at 4 sites, in a 1,594-line file. | Med-High | kotlinx-datetime in Stage 8; extract the picker arithmetic into a pure unit-tested `:core` function. |
| **R6** | **`viewModel()`/`AndroidViewModel` removal** touches 6 call sites and both ViewModels (492 + 743 lines). | Med-High | Introduce `AppContainer` + `LocalAppContainer` in Stage 7, *before* the UI moves. Fallback: hoist ViewModels into `App()` and pass as parameters. |
| **R7** | **CMP `BackHandler` API churn and iOS gesture semantics.** Navigation is a hand-rolled `sealed class Screen`, not `NavHost`. | Med | Verify the current package/API before implementing. All 4 sites are one-liners once confirmed. Test the gesture on a device, not just the simulator. |
| **R8** | **macOS CI cost**, from scratch (no CI exists today). | Med | Path-filter + gate the macOS job; `cancel-in-progress`; `timeout-minutes`; cache Gradle and `~/.konan`; route iOS to Codemagic's free tier. |
| **R9** | **Windows-only development** — no local iOS compile, no KMP IDE plugin. | Med | Keep iOS targets declared (inert on Windows). Never enable `enableKlibsCrossCompilation` — it yields no binary. Accept Stage 9 as CI-only. |
| **R10** | **The two SQLite files now ship in two bundles** and can drift; `porting-reference/` shows they're generated by Python scripts outside this repo. | Med | Single canonical copy in git, build-time staging to both, checksum test. The generation pipeline is host-agnostic, so it runs on Windows unchanged. |
| **R11** | **`BundledSQLiteDriver` changes the SQLite version on Android** under a DB generated by Python's `sqlite3`, and adds native libs to the APK. `NativeSQLiteDriver` needs `-lsqlite3`. | Med | Per-platform configurable — Android may keep framework SQLite if a regression appears. Run a query-level corpus test after switching; check APK size delta. |
| **R12** | **Compose 1.7 → 1.12 across 4,501 lines of UI** surfaces deprecations. | Med | Do it on the Android module alone (Stage 2), before CMP enters. |
| **R13** | **R8 full-mode with Room KMP + kotlinx.serialization.** `isMinifyEnabled = true` and `proguard-rules.pro` is an empty template with no keeps. | Med | Add keep rules for `@Serializable` companions/`serializer()` and Room's generated impls. **Run and smoke a release build** — the release path is currently unverified by any test. |
| **R14** | **`material-icons-extended`** deprecated/frozen upstream. | Low | ~25 icons; vendor as `ImageVector`s if it disappears. |
| **R15** | **iOS insets** — `navigationBarsPadding` and `enableEdgeToEdge()` are Android idioms. | Low-Med | CMP WindowInsets; verify on a notched device. |
| **R16** | **App Store process is entirely new work** — $99/yr, App Store Connect, TestFlight, icons, screenshots, App Review. With no Mac you also can't use the Xcode GUI for signing or upload. | Med | The existing privacy policy and "offline, no data collection" posture carry over cleanly; `AVSpeechSynthesizer` is on-device so read-aloud doesn't change the privacy claims. Budget for upload tooling on CI (`xcrun altool`/`fastlane`/Transporter). Treat this as its own project, not a footnote to Stage 9. |
| **R17** | **The TTS `<queries>` manifest entry exists for a reason** — a prior bug where engine discovery silently failed on some OEM packaging. | Low | It stays in `:androidApp` unchanged. Do not "clean it up" during the move. |

### Genuinely uncertain — verify before writing code

1. **Whether Room KMP 2.8.4 exposes `createFromAsset` or any equivalent on iOS** (R1) — the highest-uncertainty item, and it gates Stage 7.
2. **KSP 2.3.6's supported Kotlin range**, and whether AGP 9.3.3 accepts Kotlin 2.2.20 (R2).
3. **The exact current CMP `BackHandler`/`NavigationBackHandler` package and the KMP `viewModel()` overloads** (R6, R7).
4. **The JetBrains lifecycle-artifact version** that pairs with CMP 1.12.1 — the CMP compatibility page doesn't tabulate library versions.
5. **Every `<x.y.z>` marked VERIFY above** — version *shape* and artifact coordinates are given rather than invented numbers.

---

## Keeping both apps in step

The mechanism is a **single repository with a shared `:core` + `:shared`**, not two repos and not a
sync tool:

- **Logic, data, model, and UI have exactly one copy.** A matcher fix, a ranking tweak, a new screen,
  a colour change — one edit, both apps. Nothing to synchronise because there is no second copy.
- **Only three things are genuinely per-platform:** the `platform/` `expect`/`actual` set, the
  `androidApp` shell, and the `iosApp` shell. Diverge freely there.
- **The data is already shared** — one git-tracked corpus consumed by both, so hand-patched rows land
  on both platforms at once.
- **Versioning stays one track on Android.** One `versionCode`/`versionName`, and the `playstore-v*`
  tag convention in CLAUDE.md is unaffected. iOS gets its own version in the Xcode project; expect
  the two to drift, since App Store and Play review are independent.
- **`CLAUDE.md` needs a new section** describing the three-module layout and the `expect`/`actual`
  set, so a future session doesn't treat `:core`/`:shared` as mysterious. It currently documents a
  single `app` module.

**Branch discipline matters more than usual:** both apps build from `master`, so a half-migrated
shared module breaks both. Keep stages short-lived, land them one at a time, and keep the Stage-0 CI
green at each.

---

## Suggested first move

**Do Stage 0 and Stage 1 only** — CI plus an isolated Kotlin bump — as two small, verifiable changes.

That costs little, gets a regression net in place, and answers the version-alignment question (R2)
*before* any application code moves. Everything through Stage 8 is Windows-friendly work that
improves the Android app regardless of whether iOS ever ships.
