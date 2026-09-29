# Gradey Android

Native Kotlin companion to [Gradey](https://github.com/FBukovina/Gradely), with Jetpack Compose, phone widgets and Wear OS.

Version 0.2.2 targets the iOS source from 29 September 2026. See [the active parity and release audit](docs/ios-parity.md) for implemented features, verification boundaries, and remaining live-service gates.

The 0.2.0 update implements school/account flows, EduPage verification and student profiles, absence planning, Strava.cz ordering, grade-history summaries, notification preferences, Gradey AI and support purchases. Live integrations require configured services and account/device testing. See [release notes and verification](docs/next-version.md) for implementation details, parity limits and validation results.

Version 0.2.1 adds a direct APK updater: automatic background checks, Wi-Fi downloads, verified APKs, and an Android installation confirmation. Open **App updates** from Account, Gradey tools, or the sign-in screen. See [updater setup and release publishing](docs/updater.md).

[Download the 0.2.2 phone APK](https://github.com/FBukovina/Gradey-android/releases/download/v0.2.2/gradey-0.2.2-debug.apk) · [Release notes and checksums](https://github.com/FBukovina/Gradey-android/releases/tag/v0.2.2). This direct-update preview includes public Google/Supabase account configuration and supports real local school connections through **Continue without Gradey ID**. Choose **Use demo data** to explore without credentials. Live AI, Apple browser sign-in, Android push and Play purchases remain unconfigured; real account/device acceptance is pending. It is not a completed production release.

Existing compatible installations can open **App updates → Check for updates**. Version 0.2.2 uses code 5 and the existing direct-download signing key, so it can update both public 0.2.1 (code 3) and local 0.2.2 previews (code 4) while retaining app data. New users can install the APK manually.

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

- `app`: phone navigation, local/Gradey ID session state, Google and Apple sign-in, reminders, push and companion sync
- `core-model`, `core-domain`, `core-network`, `core-data`, `core-ui`: models, calculations, provider clients, encrypted persistence, Compose components
- `feature-*`: school login, Today, marks, attendance/planning, timetable, meals, account and AI
- `glance-widgets`, `wear`: next-lesson widget and Wear OS companion
- `supabase`: copied backend with account/history/push support; local changes require separate deployment

Unit tests use provider fixtures and mocked HTTP servers. Compose integration tests exercise the demo app and skip builds with service configuration. No real school credentials, purchases or meal orders are needed for automated verification.
