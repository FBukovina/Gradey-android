# Gradey Android

Native Kotlin companion to [Gradey](https://github.com/FBukovina/Gradely), with Jetpack Compose, phone widgets and Wear OS.

Version 0.3 refreshes the phone interface with the shared Gradey design system, a redesigned Today screen, a grouped Settings hub, and consistent cards, icons and navigation. See [the 0.3 release notes](docs/releases/0.3.md) for changes and verification. The feature parity baseline from 0.2.2 targets the iOS source from 29 September 2026; [the active parity and release audit](docs/ios-parity.md) records implemented features, verification boundaries, and remaining live-service gates.

The 0.2.0 update implements school/account flows, EduPage verification and student profiles, absence planning, Strava.cz ordering, grade-history summaries, notification preferences, Gradey AI and support purchases. Live integrations require configured services and account/device testing. See [release notes and verification](docs/next-version.md) for implementation details, parity limits and validation results.

Version 0.2.1 introduced a direct APK updater: automatic background checks, Wi-Fi downloads, verified APKs, and an Android installation confirmation. In 0.3, open **App updates** from **Settings → Support & About**, Gradey tools, or the sign-in screen. See [updater setup and release publishing](docs/updater.md).

[Download the 0.3 phone APK](https://github.com/FBukovina/Gradey-android/releases/download/v0.3/gradey-0.3-debug.apk) · [Release notes and checksums](https://github.com/FBukovina/Gradey-android/releases/tag/v0.3). This direct-update preview includes public Google/Supabase account configuration and supports real local school connections through **Continue without Gradey ID**. Choose **Use demo data** to explore without credentials. Live AI, Apple browser sign-in, Android push and Play purchases remain unconfigured; real account/device acceptance is pending. It is not a completed production release.

Existing compatible installations can open **App updates → Check for updates**. Version 0.3 uses code 6 and the existing direct-download signing key, so it can update public 0.2.2 (code 5), public 0.2.1 (code 3), and local 0.2.2 previews (code 4) while retaining app data. New users can install the APK manually. This release publishes the phone app only; Wear OS remains independently versioned at 0.2.0.

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

## Design system

`core-ui` mirrors the iOS `DesignSystem.swift`/`Components.swift` so both apps share one visual language:

- **Icons:** Hugeicons Stroke Rounded, rendered from the same `hgi-stroke-rounded.ttf` ligature font the iOS app bundles. Use `GradeyIcon(HugeIcons.Sun03, size = 18.dp)` (or the Material-`Icon`-shaped `HugeIcon(name, contentDescription, modifier, tint)`); icon names live in `HugeIcons` and must exist in the font's ligature table. Material icons are not a dependency any more.
- **Type:** Space Grotesk Bold (`GradeyType.display(size)`) for screen titles and hero greetings; `GradeyType.headline/subheadline/footnote/caption…` map to the iOS Dynamic Type styles.
- **Colour:** `MaterialTheme.colorScheme.primary/secondary` are the iOS `BrandPrimary`/`BrandSecondary` (teal/emerald in light, bright `#1AFFBE`/`#1FF98C` in dark); `GradeyColors.OnAccent` is the ink on the brand gradient; `MaterialTheme.gradeyColors` holds grouped backgrounds, fills, separators and Settings surfaces.
- **Surfaces:** `AuroraBackground` sits behind every signed-in screen (set once in `MainActivity`), `GradeyCard` (20 dp), `SettingsSurface` (28 dp), `SectionHeader`, `LinkPill`, `InfoRow`, `GradeBadge`, `StatusChip`, `GradeyPageHeader` (AI · title · refresh/settings) and the floating tab bar in `MainActivity`.
- `feature-*`: school login, Today, marks, attendance/planning, timetable, meals, account and AI
- `glance-widgets`, `wear`: next-lesson widget and Wear OS companion
- `supabase`: copied backend with account/history/push support; local changes require separate deployment

Unit tests use provider fixtures and mocked HTTP servers. Compose integration tests exercise the demo app and skip builds with service configuration. No real school credentials, purchases or meal orders are needed for automated verification.
