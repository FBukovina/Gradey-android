# Gradey Android

Native Kotlin companion to [Gradey](https://github.com/FBukovina/Gradely), with Jetpack Compose, phone widgets and Wear OS.

The 0.2.0 update implements school/account flows, EduPage verification and student profiles, absence planning, Strava.cz ordering, grade-history summaries, notification preferences, Gradey AI and support purchases. Live integrations require configured services and account/device testing. See [release notes and verification](docs/next-version.md) for implementation details, parity limits and validation results.

Version 0.2.1 adds a direct APK updater: automatic background checks, Wi-Fi downloads, verified APKs, and an Android installation confirmation. Open **App updates** from Account, Gradey tools, or the sign-in screen. See [updater setup and release publishing](docs/updater.md).

[Download the 0.2.1 phone APK](https://github.com/FBukovina/Gradey-android/releases/download/v0.2.1/gradey-0.2.1-debug.apk) · [Release notes and checksums](https://github.com/FBukovina/Gradey-android/releases/tag/v0.2.1). This is a debug preview without live service keys; choose **Use demo data** to explore it. Install this version manually once to enable future in-app updates.

## Run locally

1. Open the project in Android Studio; Gradle provisions JDK 21 automatically.
2. Keep the Android SDK path in untracked `local.properties`.
3. For a preview, build without service keys and choose **Use demo data**. Demo data is isolated from live repositories.
4. For live services, follow [Android configuration](docs/android-configuration.md). Never commit service credentials.

```sh
./gradlew :app:assembleDebug :wear:assembleDebug test :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Build output for every module is redirected outside the checkout to `~/Library/Caches/GradeyAndroid-build/<module>/` by the root Gradle configuration.

Phone APK: `~/Library/Caches/GradeyAndroid-build/app/outputs/apk/debug/app-debug.apk`.
Watch APK: `~/Library/Caches/GradeyAndroid-build/wear/outputs/apk/debug/wear-debug.apk`.

The phone and watch use the same package and signing identity for the Wear Data Layer. Install each APK on its respective device.

## Modules

- `app`: phone navigation, session state, Google sign-in, push and companion sync
- `core-model`, `core-domain`, `core-network`, `core-data`, `core-ui`: models, calculations, provider clients, encrypted persistence, Compose components
- `feature-*`: school login, Today, marks, attendance/planning, timetable, meals, account and AI
- `glance-widgets`, `wear`: next-lesson widget and Wear OS companion
- `supabase`: copied backend with account/history/push support; local changes require separate deployment

Unit tests use provider fixtures and mocked HTTP servers. Compose integration tests exercise the demo app and skip builds with service configuration. No real school credentials, purchases or meal orders are needed for automated verification.
