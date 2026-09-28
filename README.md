# NextBench Android

Native Android client for [NextBench](https://github.com/maryamfatimadesigns-ux/nextbench-1), the verified student marketplace and campus community.

The website is the source of truth for product behavior, Firebase contracts, security rules, and business logic. The Android client uses native Compose interaction and presentation rather than copying the website layout.

## Build

Requirements:

- Android Studio with Android SDK 35
- JDK 17

For a configuration-free debug compile:

```bash
./gradlew assembleDebug
```

Firebase-backed features require the private project configuration described in [docs/SETUP.md](docs/SETUP.md).

Release builds also require a locally configured release keystore. See the release
signing section in [docs/SETUP.md](docs/SETUP.md); signing credentials and key files are
ignored and must never be committed.

## Modules

- `app`: application shell, navigation, and system integrations
- `core:common`: shared result, error, dispatcher, and formatting utilities
- `core:designsystem`: Compose tokens, typography, motion, icons, and base components
- `data:model`: Firestore-compatible data models
- `data:firebase`: Firebase providers, callable contracts, realtime flows, and uploads

Feature and repository modules are added as complete vertical slices rather than empty scaffolds.
