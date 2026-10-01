# Direct APK updates

The phone app supports updates distributed through the public stable releases in [FBukovina/Gradey-android](https://github.com/FBukovina/Gradey-android/releases). This is the distribution channel for APKs installed directly. No release is published by building the app or generating its update manifest.

The updater is included from version 0.2.1 onward. New installations can download the [0.3 debug preview APK](https://github.com/FBukovina/Gradey-android/releases/download/v0.3/gradey-0.3-debug.apk) and [matching manifest](https://github.com/FBukovina/Gradey-android/releases/download/v0.3/gradey-update.json) from the [0.3 release](https://github.com/FBukovina/Gradey-android/releases/tag/v0.3). Version 0.3 uses code 6 and the existing debug certificate, allowing updates from public 0.2.2 (code 5), public 0.2.1 (code 3), and local previews (code 4). The account-configured preview still has the live-service limitations described in [android-configuration.md](android-configuration.md).

## What users see

The Updates screen lets users check manually and control automatic updates. In 0.3, open it from **Settings → Support & About → App updates**, Gradey tools, or the sign-in screen. When enabled, background work checks about every 12 hours and downloads new versions over an unmetered connection, normally Wi-Fi. Android may delay scheduled work to preserve battery. A manual check and download are available without waiting for the scheduled check.

A downloaded update is verified before it can be opened in Android's package installer. Android asks the user to allow Gradey to install apps when needed and to confirm installation. An ordinary Android app cannot silently replace itself. The updater keeps the user's existing app data when Android accepts the update.

The updater checks version code, package identity, phone target, minimum Android version, file size, SHA-256 checksum, and the APK's signing identity. The APK must be newer than the installed app and have exactly the same set of active signing certificates. Signing-key rotation is not supported by this direct updater. Failed or incompatible downloads are never offered for installation.

Phone and Wear OS currently share a package name for Wear Data Layer communication, so every update manifest must explicitly contain `"target": "phone"`. This feed updates the phone APK only; publish and install the Wear companion separately. The 0.3 release does not publish a Wear APK; Wear remains independently versioned at 0.2.0.

## Prepare a direct APK release

1. Increase the phone app's `versionCode` and `versionName` in `app/build.gradle.kts`, or set the `gradey.versionCode` and `gradey.versionName` Gradle properties for the build. Version 0.3 uses `-Pgradey.versionCode=6 -Pgradey.versionName=0.3`; choose a higher code for the next update. Version codes must increase for every published phone update.
2. Build a signed standalone phone APK. Keep the signing key used for earlier direct installations. A debug APK can only update compatible debug installations signed with that same debug key; a production key cannot update an existing debug installation.
3. Choose the GitHub release tag and the final APK asset filename. Generate the manifest from the exact signed APK that will be uploaded. For example, after building the version `0.3` debug preview:

   ```bash
   python3 scripts/create-update-manifest.py \
     --apk "$HOME/Library/Caches/GradeyAndroid-build/app/outputs/apk/debug/app-debug.apk" \
     --apk-url https://github.com/FBukovina/Gradey-android/releases/download/v0.3/gradey-0.3-debug.apk \
     --output artifacts/0.3/gradey-update.json
   ```

   The root Gradle build redirects outputs to `~/Library/Caches/GradeyAndroid-build`; replace the APK path if you have copied or signed the output elsewhere. An unsigned release output must be signed before generating the manifest. Add `--notes-file path/to/release-notes.txt` for optional plain-text notes and `--android-sdk /path/to/Android/sdk` if the SDK is outside the standard locations. The script uses `apkanalyzer manifest print`, or `aapt2 dump badging` when apkanalyzer is unavailable, to read the actual APK. It computes the SHA-256 and byte size from that file and rejects Wear or foreign-package APKs. It does not sign or upload the APK.

4. Attach the exact APK as `gradey-0.3-debug.apk` and the generated manifest as `gradey-update.json` to the same GitHub release. Keep it a draft while assembling the assets, then publish it as the latest stable release. Drafts and prereleases are excluded by the updater. The app requires the manifest's APK URL and size to match the release's actual asset. The stable distribution label makes the preview discoverable by the updater; it does not establish production readiness.
5. Check from an older installation made with the same signing key, complete the Android installation confirmation, and verify the new version and existing data.

Do not modify an APK after generating its manifest. Regenerate the manifest if the APK is rebuilt or signed again. The GitHub API is accessed without a token; rate limits and network errors are shown as retryable errors. The app accepts initial asset URLs only from this repository, and download redirects only from the allowed GitHub release-storage hosts over HTTPS.

## Manifest format

```json
{
  "target": "phone",
  "versionCode": 6,
  "versionName": "0.3",
  "packageName": "com.bukovinafilip.gradey",
  "minSdk": 26,
  "apkUrl": "https://github.com/FBukovina/Gradey-android/releases/download/v0.3/gradey-0.3-debug.apk",
  "sha256": "<64 hexadecimal characters generated from the APK>",
  "sizeBytes": 42000000,
  "releaseNotes": "Optional plain-text changes shown before installation."
}
```

The example checksum is a placeholder; generate the real manifest with the script. APKs are limited to 200 MiB, manifests to 64 KiB, and release notes to 8,000 UTF-16 code units. A release without `gradey-update.json` is treated as having no in-app update available. No downgrade or reinstall is offered.

## Google Play builds

Disable direct APK updates for Google Play distribution:

```properties
gradey.directUpdatesEnabled=false
```

Set this Gradle property for every Play build. That build omits the direct install permission and disables the direct updater. Play-distributed installations should receive updates through Google Play; a future Play in-app update flow should use Play's supported update APIs. Keep direct and Play signing/distribution arrangements explicit when preparing releases.

## Version 0.2.1 validation

Verified on 22 September 2026:

- Phone debug APK builds as version `0.2.1`, code `3`. All 118 JVM/unit tests pass, including 34 new tests for release manifests, download integrity/cancellation, and APK verification.
- All 16 Android emulator tests pass on Android 17. Coverage includes updates before sign-in, preference persistence, periodic-work cancellation, interrupted-state recovery, manual downloads overriding Wi-Fi constraints, and valid/invalid APK checks.
- A locally built version `0.2.2-updater-test`, code `4`, signed with the same debug key, was successfully installed through Gradey and Android's confirmation UI. Existing updater preferences survived the installation. The emulator was then returned to the final `0.2.1` preview and test fixtures were removed. That test version was not published.
- Android lint reports 0 errors and 70 warnings, including existing dependency and style suggestions. A manifest build with direct updates disabled was also checked to omit `REQUEST_INSTALL_PACKAGES`.
- The manifest-generation script produced the [release manifest](https://github.com/FBukovina/Gradey-android/releases/download/v0.2.1/gradey-update.json) from the final APK; its size and SHA-256 match the [checksums](https://github.com/FBukovina/Gradey-android/releases/download/v0.2.1/SHA256SUMS). Future releases still need their assets published on GitHub before clients can discover them.

Preview: [Updates screen](images/updates.png). Installer verification: [Android confirmation](images/installer-confirmation.png).
