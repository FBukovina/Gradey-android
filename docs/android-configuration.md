# Android Configuration

## App Secrets

Use JDK 21 for local builds. The checked-in Gradle and Android plugin versions were verified with JDK 21.

Do not commit these values. Provide them through local Gradle properties or CI secrets:

- `gradey.supabaseUrl`
- `gradey.supabaseAnonKey`
- `gradey.googleWebClientId`
- `gradey.revenueCatAndroidKey`
- `app/google-services.json`

When Supabase values are blank, the app uses mock repositories with demo school data.

## Google Sign-In

Set `gradey.googleWebClientId` to the Google **web OAuth client ID** used as the server client ID by Android Credential Manager, and authorize that client ID in Supabase Auth's Google provider configuration. Separately register an Android OAuth client for `com.bukovinafilip.gradey` and each signing certificate used by the app, including debug and release SHA-1 fingerprints. The Android client identifies the installed app; it is not the value for `gradey.googleWebClientId`.

## Firebase Cloud Messaging

The Android app registers FCM tokens with `register-device` using:

```json
{
  "platform": "android",
  "environment": "debug",
  "token": "..."
}
```

The app selects the environment automatically: debug builds send `"debug"` and non-debug builds send `"production"`.

## Supabase Edge Function Secrets

The copied backend keeps the existing APNs path and adds FCM dispatch in `send-push`.

Required push secrets:

- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`
- `PROVIDER_SECRET_KEY`
- `CRON_SECRET` (must match the scheduled poller's `x-cron-secret` header)
- `APNS_TOPIC`
- `APNS_TEAM_ID`
- `APNS_KEY_ID`
- `APNS_PRIVATE_KEY_P8`
- `FCM_PROJECT_ID`
- `FCM_CLIENT_EMAIL`
- `FCM_PRIVATE_KEY`

Set secrets with the Supabase CLI or dashboard. Supabase Edge Functions automatically expose configured secrets through `Deno.env.get(...)`.

## Backend Functions

- `register-device`: accepts `ios`, `macos`, and `android` platforms.
- `poll-new-marks`: records grade history and invokes `send-push`.
- `send-push`: dispatches APNs for Apple platforms and FCM HTTP v1 messages for Android.
- `send-apns`: kept for backward compatibility with the copied iOS backend.

## Migration Notes

`202607060001_android_push_platform.sql` widens the `device_push_tokens.platform` check constraint for existing projects. Fresh databases also get `android` in the initial copied platform migration.

The copied `20260710210430_existing_platform_security_hardening.sql` and `20260804161406_refresh_provider_secret_during_polling.sql` migrations retain the original repository's identifiers. Review existing migration history before deployment. The poller now rejects requests when `CRON_SECRET` is absent or incorrect; these backend changes and migrations have not been deployed from this workspace.

## Gradey AI

Add `app/google-services.json` for the Firebase project hosting the existing Gradey AI callable functions in `europe-west1`. Enable anonymous Firebase Authentication and register this Android package and signing SHA-256 for App Check Play Integrity. AI remains unavailable in demo mode or without Firebase configuration. The Android client requires server consent before sending selected grade/timetable context, uses limited-use App Check tokens for replies, and displays the final response after the server saves it. It does not stream token-by-token or send historical trend context. Conversation deletion and consent revocation use the existing server functions.

## Support purchases

Configure `gradey.revenueCatAndroidKey` and the Google Play products/offerings matching the Gradey support/tips catalog. Prices and purchase confirmation come from Google Play. Demo builds cannot make purchases. Restore and subscription management are available from Gradey tools → Support Gradey. Implementation uses the [RevenueCat Android SDK](https://www.revenuecat.com/docs/getting-started/installation/android).

## Release builds

Phone release packaging requires the Supabase URL, public anon key and Google web client ID. An intentional demo release must opt in with `gradey.allowDemoRelease=true`. Debug builds and tests do not need live configuration. Configure your own signing and Play Console release; this repository does not contain production signing keys.

For Google Play builds, also set `gradey.directUpdatesEnabled=false`. This omits the APK installation permission and disables the direct updater. Direct APK distributions default to the updater enabled; they read stable GitHub release assets from this repository and require the same signing key as the installed app. See [updater.md](updater.md) for release manifest generation and publishing. Optional `gradey.versionCode` and `gradey.versionName` properties override the checked-in phone version during a build.
