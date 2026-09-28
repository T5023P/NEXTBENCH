# Android Setup

## 1. Local Android configuration

Copy `local.properties.example` to `local.properties`. Keep the Android SDK path written by Android Studio and add:

```properties
CLOUDINARY_CLOUD_NAME=your_cloud_name
CLOUDINARY_UPLOAD_PRESET=your_unsigned_preset
GIPHY_API_KEY=your_key
```

`GOOGLE_WEB_CLIENT_ID` is read from `google-services.json`. You can set it in `local.properties` only when you intentionally need to override that generated value.

For local testing, client-safe values can also come from the ignored root `.env`; Android accepts both `CLOUDINARY_CLOUD_NAME` and the website-style `VITE_CLOUDINARY_CLOUD_NAME` names.

Only client-safe identifiers belong in this file. Never place Cloudinary API secrets or Firebase Admin credentials in the Android project.

## 2. Firebase Android app

In Firebase project `nextbench-a11ed`, use the Android application with package name `com.nextbench.app`. Download its `google-services.json` and place it at:

```text
app/google-services.json
```

The example JSON documents the expected shape but cannot replace the file downloaded from Firebase. Debug builds compile without it so UI work remains unblocked; Firebase-backed features report that configuration is unavailable. Release builds require it.

Enable the same Firebase products used by the website:

- Authentication: email/password and Google
- Firestore
- Storage
- Cloud Functions
- Cloud Messaging

The Android app consumes the website's existing deployed backend. This repository also owns an isolated `android-push` Functions codebase for Android FCM token registration and direct-message delivery. It does not deploy Firestore rules and cannot replace the website's default Functions codebase.

Build and deploy only that isolated codebase with:

```bash
npm install --prefix functions-android
npm test --prefix functions-android
firebase deploy --only functions:android-push
```

## 3. Release signing

Debug builds do not need a signing key. Release APKs and AABs must be signed with the
same keystore used for the Play Console application. Keep the keystore outside git and
add these values to `local.properties` or the CI secret environment:

```properties
RELEASE_STORE_FILE=/absolute/path/to/nextbench-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=nextbench
RELEASE_KEY_PASSWORD=...
```

The Gradle release tasks fail early when Firebase, Cloudinary, or release signing is
missing. Never commit the keystore, passwords, or `app/google-services.json`.

## 4. Build and test

```bash
./gradlew test assembleDebug
```

Use JDK 17. Android Studio's embedded JDK is also supported when it is version 17 or newer and compatible with the configured Android Gradle Plugin.
