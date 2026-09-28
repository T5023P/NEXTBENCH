# NextBench Android — P0 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Stand up a multi-module Kotlin + Jetpack Compose Android project that launches to a themed 5-tab shell, connects to the live Firebase project `nextbench-a11ed`, and ships the full design system + motion kit + data-model layer that every later phase builds on.

**Architecture:** Single Gradle project, multi-module (`app`, `core:designsystem`, `core:common`, `data:model`, `data:firebase`). MVVM + Repository, Coroutines/Flow, Hilt DI, Navigation-Compose. Firebase Android SDK via BoM reused against the existing backend.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Coil, DataStore, Firebase (Auth/Firestore/Storage/Functions/Messaging), Gradle KTS + version catalog.

## Global Constraints
- `minSdk 26`, `targetSdk 35`, `compileSdk 35`, JDK 17.
- `applicationId = "com.nextbench.app"` (matches web Capacitor `appId`).
- Kotlin 2.0+ with Compose Compiler plugin; Compose BOM.
- No stock Material icon set for brand surfaces — custom `ImageVector`s.
- Color tokens, fonts, easing copied verbatim from web `DESIGN.md`.
- Shared easing `EaseOutQuart = CubicBezier(0.22f, 1f, 0.36f, 1f)`; entry 800ms, interaction 300ms, stagger 150ms.
- Avoid comments except to record non-obvious invariants.
- Atomic commits, one coherent change each.
- Build is run by the user (macOS): `./gradlew assembleDebug`. Sandbox cannot run Gradle.
- Secrets (`google-services.json`, Cloudinary preset, Giphy key) provided by user via files / `local.properties` → `BuildConfig`. Never hardcoded.

---

### Task 1: Gradle scaffold + version catalog + root config
**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `.gitignore`, `gradle/wrapper/gradle-wrapper.properties`
- Create module stubs: `app/build.gradle.kts`, `core/designsystem/build.gradle.kts`, `core/common/build.gradle.kts`, `data/model/build.gradle.kts`, `data/firebase/build.gradle.kts`

**Interfaces:**
- Produces: version catalog aliases (`libs.androidx.core.ktx`, `libs.compose.bom`, `libs.hilt.android`, `libs.firebase.bom`, `libs.coil.compose`, `libs.datastore.preferences`, etc.); module names `:app`, `:core:designsystem`, `:core:common`, `:data:model`, `:data:firebase`.

- [ ] **Step 1: `.gitignore`** — standard Android/Gradle ignores plus `google-services.json`, `local.properties`, `.idea/`, `build/`, `*.keystore`.
- [ ] **Step 2:** Write `gradle/libs.versions.toml` with versions: AGP 8.7+, Kotlin 2.0.21, Compose BOM 2024.10+, Hilt 2.52, Firebase BOM 33.5+, Coil 2.7, DataStore 1.1, Navigation-Compose 2.8, Coroutines 1.9, Google Services plugin 4.4.
- [ ] **Step 3:** `settings.gradle.kts` — `pluginManagement` + `dependencyResolutionManagement` (google, mavenCentral), `rootProject.name = "NextBench"`, `include(":app", ":core:designsystem", ":core:common", ":data:model", ":data:firebase")`.
- [ ] **Step 4:** Root `build.gradle.kts` — declare plugins `apply false` (android application/library, kotlin android, kotlin compose, hilt, google-services).
- [ ] **Step 5:** `gradle.properties` — AndroidX, Jetifier off, Kotlin code style official, JVM args, non-transitive R class.
- [ ] **Step 6:** Gradle wrapper `gradle-wrapper.properties` pinned to Gradle 8.9 (user runs `gradle wrapper` or copies wrapper jar; note in README).
- [ ] **Step 7: Commit** — `chore: gradle multi-module scaffold + version catalog`.

### Task 2: `core:common` module
**Files:**
- Create: `core/common/src/main/kotlin/com/nextbench/core/common/Result.kt`, `Dispatchers.kt`, `TimeFormat.kt`, `Money.kt`
- Create: `core/common/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `sealed interface NbResult<out T> { data class Success; data class Failure(val error: NbError) }`; `enum class NbError`; `interface DispatcherProvider { val io; val default; val main }` + `DefaultDispatcherProvider`; `fun formatRelativeTime(epochMs: Long): String`; `fun formatRupees(paise0: Int): String` → `"₹1,299"`.

- [ ] **Step 1:** `NbResult` sealed type + `runCatchingNb { }` extension that maps exceptions to `NbError`.
- [ ] **Step 2:** `NbError` enum: `Network, PermissionDenied, NotFound, RateLimited, Unauthenticated, Unknown` + message mapper mirroring web `firestore-errors.ts`.
- [ ] **Step 3:** `DispatcherProvider` + default impl.
- [ ] **Step 4:** `formatRelativeTime` (now/just now, m, h, d, then date) and `formatRupees`.
- [ ] **Step 5: Test** `TimeFormatTest`, `MoneyTest` (JVM unit tests, no Android).
- [ ] **Step 6:** Run `./gradlew :core:common:testDebugUnitTest` — expect PASS. (User runs; sandbox notes expected output.)
- [ ] **Step 7: Commit** — `feat(common): result type, dispatchers, formatters`.

### Task 3: `core:designsystem` — color tokens + theme
**Files:**
- Create: `core/designsystem/src/main/kotlin/com/nextbench/core/designsystem/theme/Color.kt`, `Theme.kt`, `Type.kt`, `Dimens.kt`, `Motion.kt`
- Create: `core/designsystem/src/main/res/font/` (Inter + Playfair) + `res/values/`

**Interfaces:**
- Produces: `NbColors` data class (all tokens from DESIGN.md), `LocalNbColors` CompositionLocal, `NbTheme(darkTheme, content)`, `NbType` (Inter/Playfair text styles), `NbMotion` (easing/durations), `NbDimens`.

- [ ] **Step 1:** `Color.kt` — `data class NbColors(...)` with every token; `lightNbColors()` and `darkNbColors()` from the DESIGN.md tables (surface-base `#F5F5F7`/`#0D0F14`, brand-pink `#FF375F`, brand-teal `#0071E3`/`#0A84FF`, brand-mint `#34C759`/`#30D158`, glass, border, overlay, nav-bg…).
- [ ] **Step 2:** `Motion.kt` — `val EaseOutQuart = CubicBezier(0.22f,1f,0.36f,1f)`; `object NbMotion { entry=800, interaction=300, stagger=150 }` (ms Int + tween helpers).
- [ ] **Step 3:** Download/add Inter (300–700) + Playfair Display (400/700/italic) as bundled font resources; `Type.kt` builds `Typography` + serif display styles.
- [ ] **Step 4:** `Theme.kt` — `NbTheme` provides `LocalNbColors`, drives Compose `MaterialTheme` with mapped scheme, animates color on theme change; edge-to-edge status bar tint.
- [ ] **Step 5:** `Dimens.kt` — spacing scale, radii, max content width, nav paddings.
- [ ] **Step 6: Commit** — `feat(designsystem): color tokens, typography, theme, motion`.

### Task 4: `core:designsystem` — custom icons + brand assets
**Files:**
- Create: `.../designsystem/icon/NbIcons.kt` (+ per-icon files), `.../designsystem/brand/Logo.kt`, `VerifiedBadge.kt`, `EmptyState.kt`

**Interfaces:**
- Produces: `object NbIcons { val Feed, Marketplace, Add, Messages, Profile, Bell, Search, Heart, Bookmark, Reaction*, Send, Mic, Camera, ... : ImageVector }`; `@Composable NbLogo(...)`, `NbVerifiedBadge(...)`, `NbEmptyState(...)`.

- [ ] **Step 1:** Author nav + action icons as `ImageVector` via `materialIcon`-style vector DSL (custom paths, not `Icons.Default.*`).
- [ ] **Step 2:** `NbLogo` composable (vector) + animated splash variant.
- [ ] **Step 3:** `NbVerifiedBadge` (mint check, matches web friend-badge), `NbEmptyState` illustration composable.
- [ ] **Step 4: Commit** — `feat(designsystem): custom icon set + brand vectors`.

### Task 5: `core:designsystem` — base components + motion primitives
**Files:**
- Create: `.../components/NbButton.kt`, `NbTextField.kt`, `NbCard.kt`, `NbAvatar.kt`, `NbBadge.kt`, `NbBottomSheet.kt`, `NbToast.kt`, `NbSkeleton.kt`, `NbGlassBar.kt`
- Create: `.../motion/PressScale.kt`, `StaggeredEntrance.kt`, `Shimmer.kt`

**Interfaces:**
- Produces: `NbButton(variant: Primary|Secondary|Round, ...)`, `NbTextField`, `NbCard`, `NbAvatar(url, ring)`, `NbBadge`, `NbSkeleton(Modifier)`, `Modifier.pressScale()`, `Modifier.shimmer()`, `LazyItemScope.staggeredEntrance(index)`.

- [ ] **Step 1:** `Modifier.pressScale()` (spring to 0.96 on press), `Modifier.shimmer()`, staggered entrance helper.
- [ ] **Step 2:** `NbButton` (pink→teal press, secondary outline, round CTA; label-caps option; haptic on press).
- [ ] **Step 3:** `NbTextField` (surface-soft bg, focus → brand-teal border, error state), `NbCard`, `NbAvatar` (Coil + optional story ring gradient), `NbBadge`.
- [ ] **Step 4:** `NbSkeleton`, `NbGlassBar` (blur/scrim), `NbBottomSheet`, `NbToast` host.
- [ ] **Step 5: Commit** — `feat(designsystem): base components + motion primitives`.

### Task 6: `data:model` — Kotlin data classes for every collection
**Files:**
- Create under `data/model/src/main/kotlin/com/nextbench/data/model/`: `User.kt`, `Post.kt`, `Product.kt`, `Message.kt`, `ChatRoom.kt`, `Club.kt`, `Story.kt`, `Notification.kt`, `Reaction.kt`, `Review.kt`, `Report.kt`, `Follow.kt`, `Enums.kt`

**Interfaces:**
- Produces: `@Keep data class` for each entity matching the spec's data-model section; enums `VerificationStatus`, `AccountType`, `MessageType`, `ProductStatus`, `StoryPrivacy`, `NotificationType`, `ReactionType(emoji, weight, label)`.

- [ ] **Step 1:** `Enums.kt` incl. `ReactionType` with the fixed 7 (`dead 💀`/2, `too_real 😭`/3, `exposed 👀`/2, `crazy 🤯`/2, `wholesome ❤️`/3, `spill_more ☕`/4, `respect 🫡`/2).
- [ ] **Step 2:** `User.kt` — all `UserData` fields from `AuthContext.tsx` (verified, verificationStatus, reputation, isAdmin, role, username, org* fields, fcmTokens…). Nullable-faithful; no-arg constructor for Firestore.
- [ ] **Step 3:** `Post.kt`, `Product.kt` with poll/reaction/status fields.
- [ ] **Step 4:** `Message.kt`, `ChatRoom.kt`, `Club.kt` (roles + settings + per-user arrays) matching `useChatEngine.ts`/`conversations.ts`.
- [ ] **Step 5:** `Story.kt` (+ TrayEntry, StoryViewer), `Notification.kt`, `Review.kt`, `Report.kt`, `Follow.kt`.
- [ ] **Step 6: Test** `ModelDefaultsTest` — every model has a Firestore-safe no-arg constructor.
- [ ] **Step 7: Commit** — `feat(model): Firestore data classes for all collections`.

### Task 7: `data:firebase` — providers + Cloudinary uploader + callables
**Files:**
- Create: `data/firebase/src/main/kotlin/com/nextbench/data/firebase/FirebaseModule.kt`, `FirestoreRefs.kt`, `CloudinaryUploader.kt`, `Functions.kt`, `FirestoreExt.kt`
- Create: `data/firebase/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: Hilt `@Provides` for `FirebaseAuth`, `FirebaseFirestore` (persistence + unlimited cache), `FirebaseStorage`, `FirebaseFunctions`, `FirebaseMessaging`; `object FirestoreRefs` (typed collection refs); `CloudinaryUploader.upload(bytes, folder): NbResult<UploadResult>`; `fun <T> Query.snapshotFlow(clazz): Flow<List<T>>` + `DocumentReference.snapshotFlow`.

- [ ] **Step 1:** `FirebaseModule` (Hilt) providing all services; Firestore settings with persistent cache (parity with web).
- [ ] **Step 2:** `FirestoreExt.snapshotFlow` via `callbackFlow` (the realtime backbone) + `awaitClose`.
- [ ] **Step 3:** `FirestoreRefs` typed refs for all collections + subcollections (`chatRooms/{id}/messages`, `clubs/{id}/messages`).
- [ ] **Step 4:** `CloudinaryUploader` — unsigned multipart POST to `api.cloudinary.com/v1_1/{cloud}/auto/upload` with preset from `BuildConfig`; returns secure URL. Mirrors web `storage.ts`.
- [ ] **Step 5:** `Functions.kt` — thin typed wrappers over `httpsCallable` for the reused callables (getDiscoveryFeed, searchDiscovery, sendAuthOtpEmail, verifyAuthOtpEmail, createNotification, getRecommendedProducts, getSuggestedUsers, createInviteCode, submitInviteCode, lookupReferralCode).
- [ ] **Step 6: Commit** — `feat(firebase): providers, snapshotFlow, cloudinary uploader, callables`.

### Task 8: `app` — Application, Hilt, Firebase init, manifest, config plumbing
**Files:**
- Create: `app/src/main/kotlin/com/nextbench/app/NextBenchApp.kt`, `MainActivity.kt`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/res/` (icons, themes.xml, strings), `app/proguard-rules.pro`
- Modify: `app/build.gradle.kts` (google-services plugin, BuildConfig fields from `local.properties`)
- Create: `app/google-services.json.example`, root `local.properties.example`

**Interfaces:**
- Consumes: all modules above.
- Produces: `@HiltAndroidApp NextBenchApp`, `MainActivity` hosting `NbTheme { NbApp() }`; `BuildConfig.CLOUDINARY_CLOUD_NAME/PRESET`, `GIPHY_API_KEY`.

- [ ] **Step 1:** `app/build.gradle.kts` — android app plugin, hilt, compose, google-services; read `local.properties` → `buildConfigField` for Cloudinary/Giphy.
- [ ] **Step 2:** `NextBenchApp` (`@HiltAndroidApp`); `MainActivity` (`@AndroidEntryPoint`, edge-to-edge, `setContent`).
- [ ] **Step 3:** `AndroidManifest.xml` — INTERNET perms, POST_NOTIFICATIONS, CAMERA, READ_MEDIA_*, app theme, single activity, deep-link intent filters for `/post`, `/product`, `/messages`, `/u`.
- [ ] **Step 4:** Adaptive launcher icon (NbLogo), splash theme, `strings.xml`.
- [ ] **Step 5:** `google-services.json.example` + `local.properties.example` + README note on where the user drops real files.
- [ ] **Step 6: Commit** — `feat(app): application, hilt, manifest, config plumbing`.

### Task 9: `app` — navigation graph + 5-tab shell + themed placeholders
**Files:**
- Create: `app/src/main/kotlin/com/nextbench/app/nav/NbNavHost.kt`, `NbRoute.kt`, `NbBottomBar.kt`, `NbApp.kt`
- Create: `app/src/main/kotlin/com/nextbench/app/shell/PlaceholderScreen.kt`, `SplashScreen.kt`

**Interfaces:**
- Consumes: designsystem components, `NbIcons`.
- Produces: `sealed class NbRoute(route)` (Splash, Onboarding, Login, Signup, OrgSignup, Verification, Feed, Marketplace, Sell, Messages, Profile, Notifications, Search…); `NbApp()` root composable; animated `NbBottomBar`.

- [ ] **Step 1:** `NbRoute` sealed routes (typed) covering all screens the web `App.tsx` exposes.
- [ ] **Step 2:** `NbBottomBar` — glass bar, 5 tabs (Feed/Marketplace/＋/Messages/Profile), animated selection indicator + pressScale + haptic.
- [ ] **Step 3:** `NbNavHost` with shared-axis screen transitions; `PlaceholderScreen(title)` themed empty state per tab.
- [ ] **Step 4:** Animated `SplashScreen` (NbLogo pulse + progress) as start destination, then routes to shell.
- [ ] **Step 5:** `NbApp` wires theme + nav + toast host; bottom bar hidden on auth/splash routes.
- [ ] **Step 6: Commit** — `feat(app): nav graph, animated 5-tab shell, splash, placeholders`.

### Task 10: Firebase connectivity smoke test + README
**Files:**
- Create: `app/src/main/kotlin/com/nextbench/app/health/ConnectivityProbe.kt`
- Create: `README.md`, `docs/SETUP.md`

**Interfaces:**
- Consumes: `FirebaseFirestore`, `snapshotFlow`.
- Produces: a debug-only probe that reads `schools` (public per rules) and logs count; surfaced as a tiny "connected" chip on Splash in debug builds.

- [ ] **Step 1:** `ConnectivityProbe` reads a public collection (`schools`) via callable/snapshot and returns reachable state.
- [ ] **Step 2:** Wire probe into Splash (debug only) — green dot = live backend reached.
- [ ] **Step 3:** `README.md` + `docs/SETUP.md` — exact steps: drop `google-services.json`, fill `local.properties` (Cloudinary/Giphy), `./gradlew assembleDebug`, run on device/emulator.
- [ ] **Step 4: Commit** — `feat(app): firebase connectivity probe + setup docs`.

---

## Self-Review
- **Spec coverage (P0):** gradle scaffold (T1), common utils/error map (T2), design tokens+theme+motion (T3), custom icons/brand (T4), base components+motion primitives (T5), full data model (T6), firebase providers+snapshotFlow+cloudinary+callables (T7), app/hilt/manifest/config (T8), nav shell+splash+placeholders (T9), connectivity+docs (T10). Covers every P0 exit item in the design spec.
- **Placeholder scan:** none — each task lists concrete files/types/values.
- **Type consistency:** `NbColors`, `NbTheme`, `NbMotion`, `NbIcons`, `snapshotFlow`, `NbResult`, `ReactionType` referenced consistently across tasks.
- Later phases (P1–P11) each get their own plan file; this plan intentionally scopes only P0 to keep tasks reviewable.
