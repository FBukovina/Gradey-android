# Android Configuration

## App configuration

Use JDK 21 for local builds. The checked-in Gradle and Android plugin versions were verified with JDK 21.

Provide these settings through untracked local Gradle properties or CI configuration:

- `gradey.supabaseUrl`
- `gradey.supabaseAnonKey`
- `gradey.googleWebClientId`
- `gradey.appleOAuthClientId` (optional Apple Services ID; leave blank until the browser provider is configured)
- `gradey.revenueCatAndroidKey`
- `app/google-services.json`

Keep downloaded credential files and Google client secrets outside Git. Public project and OAuth client identifiers are documented below; the Google client secret belongs only in the hosted Supabase provider settings, never in the Android build.

When Supabase values are blank, the default Gradey ID graph uses mock repositories. **Use demo data** explicitly selects that graph. In the current source, **Continue without Gradey ID** uses a separate real local provider graph even without cloud configuration; it cannot use Gradey cloud history, alerts or AI. This guest support was added after the published 0.2.1 preview.

## Current local parity previews — 29 September 2026

The latest local previews are described in `artifacts/parity-preview/README.md` (ignored build output). The accounts preview includes the existing public Supabase/Google settings; the separate unconfigured preview offers mock data through **Use demo data** and real local school connections through the separate guest path. Neither is a production-ready release. Real sign-in remains unverified, and correct Firebase AI/push configuration, Apple browser identity, Android purchases and optional native support configuration are still required. Use the [current parity audit](ios-parity.md) for acceptance status rather than older provisional-build notes.

The phone currently targets API 36 and Wear targets API 36. This meets the target-level minimums published for new submissions on 29 September 2026: API 36 for phone apps and API 35 for Wear. Recheck at submission time. [Google Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-gb).

The current phone APK passes `zipalign -c -P 16 -v 4`; all four packaged 64-bit native libraries have 16,384-byte ELF LOAD alignment. The phone regression emulator also reports 16,384-byte pages. This covers emulator execution and static packaging, not physical-device or production AAB acceptance. Follow the [Android page-size verification guide](https://developer.android.com/guide/practices/page-sizes) for the final signed bundle and runtime check.

## Users cannot connect their school

The published `0.2.1` debug preview has empty Supabase and Google sign-in settings. It cannot connect real Bakaláři or EduPage accounts. **Use demo data** opens mock repositories; entering school credentials in that mode does not authenticate with the school. Signing out or changing the school password cannot enable live services in this APK.

To distribute a build that supports real accounts:

1. Set `gradey.supabaseUrl`, `gradey.supabaseAnonKey`, and `gradey.googleWebClientId` in your untracked Gradle properties, and complete the Google Sign-In configuration below.
2. Build an accounts-only acceptance APK with the normal `assembleDebug` task:

   ```sh
   ./gradlew :app:assembleDebug \
     -Pgradey.allowDemoRelease=false \
     -Pgradey.versionCode=4 -Pgradey.versionName=0.2.2
   ```

   Debug assembly does not require or validate live configuration. This accounts-only preview does not pass the current full-release gate: `:app:verifyReleaseConfiguration` additionally requires an Apple Services ID, the Android RevenueCat key and `app/google-services.json` from the deployed AI project. Run that gate with `gradey.allowDemoRelease=false` once every required setting is available. It checks presence, not OAuth registration or backend availability.
3. Verify Google sign-in and connection to a real school on the resulting APK. The demo tests do not exercise live authentication.
4. Follow [direct APK release publishing](updater.md) to distribute a newer version with the same signing key as the installed preview.

For configured builds, use the school's HTTPS Bakaláři address. Login-page links such as `https://school.example/bakalari/next/login.aspx` are normalized to the installation root, `https://school.example/bakalari`, before calling `/api/login`.

## Google Sign-In

The dedicated Google project **Gradey** (`gradey-9628e`) was created on the Firebase Spark no-cost plan, with no billing account linked and Analytics/Gemini disabled. As of 27 September 2026, its web and Android OAuth clients are configured and the hosted Supabase Google provider is enabled. This project is used for OAuth; the existing Gradey AI and push services are not deployed there, so do not point `app/google-services.json` at it for those features.

The configured public OAuth support email is `filip@openside.tech`. Google requires this address to be the signed-in Google account or a Google Group it manages. See [Google's support email requirements](https://support.google.com/cloud/answer/15549049).

Set `gradey.googleWebClientId` to the Google **web OAuth client ID** used as the server client ID by Android Credential Manager, and authorize that client ID in Supabase Auth's Google provider configuration. Separately register an Android OAuth client for `com.bukovinafilip.gradey` and each signing certificate used by the app, including debug and release SHA-1 fingerprints. The Android client identifies the installed app; it is not the value for `gradey.googleWebClientId`.

The hosted **Google** provider has the web client ID first and the Android client ID second, with the matching web client secret saved in Supabase. Nonce-check skipping and email-less sign-in are both disabled. Apple provider settings were preserved. Editing local `supabase/config.toml` does not change these hosted settings. The web client's authorized redirect URI is `https://ieonvnfzkbyybbkfxupq.supabase.co/auth/v1/callback`. See [Supabase's Google sign-in setup](https://supabase.com/docs/guides/auth/social-login/auth-google).

For the existing direct APK distribution, use:

- Supabase project: `ieonvnfzkbyybbkfxupq`
- Web OAuth client ID: `148928645189-7k5evpr73n6sr20ip6pdglfvsckb3qvt.apps.googleusercontent.com`
- Android OAuth client ID: `148928645189-pq5gl59knq4u53spqnv51eg87min1a7f.apps.googleusercontent.com`
- Web OAuth redirect: `https://ieonvnfzkbyybbkfxupq.supabase.co/auth/v1/callback`
- Android package: `com.bukovinafilip.gradey`
- Android signing SHA-1: `E0:5F:31:E0:C2:49:86:8B:C4:DB:32:F8:F0:4A:9E:07:BC:74:9F:04`

This fingerprint was verified against the published `0.2.1` APK. Preserve that signing key for compatible direct updates; a separately signed Play distribution needs its own Android OAuth registration.

Google's data-access configuration contains only `openid`, `userinfo.email` and `userinfo.profile`, with no sensitive or restricted scopes. Read-only checks on 27 September confirmed that `/auth/v1/settings` reports both Google and Apple enabled. The unauthenticated `/auth/v1/authorize?provider=google` endpoint returned HTTP 302 to Google's authorization endpoint with the configured web client, exact Supabase callback and default `email profile` scopes. The redirect was not followed and no user sign-in was performed by that check.

Google branding verification/publication and the real Android Google-sign-in → school-connection test remain pending. An earlier provisional `0.2.2` APK passed the historical three-setting configuration check and matched the `0.2.1` signing certificate. The current accounts preview includes those public Google/Supabase settings and has the same signature, but does not satisfy the expanded full-release gate described above. It has not been published. Basic identity-only sign-in has [exceptions to Google's testing-user and seven-day authorization limits](https://support.google.com/cloud/answer/15549945); those exceptions do not complete production branding or verify the app's live sign-in flow.

## Apple sign-in and existing iOS accounts

Android now implements Apple browser OAuth with PKCE, an encrypted one-use verifier, state validation, and a ten-minute pending-request lifetime. It returns to the existing `gradey://auth` Android deep link and preserves the Supabase user UUID. Keep `gradey.appleOAuthClientId` empty until an Apple Services ID is configured and associated with the existing iOS primary App ID. Never include an Apple OAuth secret or private key in the APK.

A read-only check on 29 September 2026 confirmed that Apple and Google are enabled, but the Apple authorization redirect still selects the native bundle ID `com.bukovinafilip.BakalariMarks`. That is a release gate for Android Apple sign-in: [Supabase requires the Services ID first for browser OAuth](https://supabase.com/docs/guides/auth/social-login/auth-apple), while retaining the native bundle ID for iOS token audiences. The Android button remains hidden until its configured Services ID matches the live redirect. Backend settings have not been changed by this implementation.

An iOS user who used Hide My Email cannot be assumed to have the same Gradey account after Google sign-in. Android does not merge account ownership using school credentials or email heuristics. Verify the Apple flow with an existing iOS account, including private relay email, before release. The Apple OAuth secret needs its documented rotation process, the Supabase callback must be registered with Apple, and `gradey://auth` must preserve the app state in the redirect allowlist. See [the full Android Apple setup and verification notes](apple-sign-in-android.md).

## Linked canteen accounts

Local Strava.cz sign-in and meal ordering remain separate from optional Gradey ID registration. The Account screen can explicitly link the current canteen session, list canteen links owned by that Gradey user, and remove a link without changing meal orders. Only the allowlisted session ID, service URL, canteen number/name, username and display name are sent; no password is sent.

The hosted `link-stravacz-account` function, verified read-only as version 7 on 29 September 2026, inserts a new link on every call and does not support idempotency keys. Android preflights the current account list, reuses a proven device binding, and refuses to guess whether an existing matching row belongs to the same canteen. After an interrupted link, refresh the account list and review/remove the old link before trying again. There is no automatic canteen-session activation on a new device. No live canteen registration was performed during development.

## Firebase Cloud Messaging

The Android app registers FCM tokens with `register-device` using:

```json
{
  "platform": "android",
  "environment": "sandbox",
  "token": "..."
}
```

The app selects the environment automatically: debug builds send `"sandbox"` and non-debug builds send `"production"`, matching the hosted database's accepted values.

Registration support does not imply push delivery. The hosted project currently runs an Apple-only APNs dispatcher. Android FCM delivery has not been deployed or verified; configuring `google-services.json` and accepting an Android token alone will not deliver Android mark alerts.

## Supabase Edge Function Secrets

The local copied backend contains FCM dispatch in `send-push`, in addition to the APNs path. It is not the source of truth for the currently deployed notification pipeline; see the hosted status below. FCM secrets are required only when that Android delivery integration is deployed.

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

The local source tree contains:

- `register-device`: accepts `ios`, `macos`, and `android` platforms.
- `poll-new-marks`: records grade history and invokes `send-push`.
- `send-push`: dispatches APNs for Apple platforms and FCM HTTP v1 messages for Android.
- `send-apns`: kept for backward compatibility with the copied iOS backend.

### Hosted status, 27 September 2026

On the connected project `ieonvnfzkbyybbkfxupq`:

- On 23 September, applied the additive `android_push_platform_support` migration so `device_push_tokens.platform` accepts `android` alongside `ios` and `macos`.
- On 23 September, deployed `send-apns` version 8 from the existing hosted version 7, adding an Apple-only platform filter both when creating delivery targets and when loading devices for pending deliveries. Android tokens are excluded from APNs. Existing authentication settings were preserved, and an unauthenticated POST returned HTTP 401.
- On 27 September, configured the Android/web Google OAuth clients and enabled the hosted Google provider. Public settings and the initial authorization redirect were checked successfully as described above. Google branding, real-account sign-in verification and release publication remain pending. The published APK is still the demo-only `0.2.1`; the provisional `0.2.2` has not been published.
- Android FCM delivery remains pending. No Android notification delivery is claimed by this migration or dispatcher patch.

The checked-in `send-apns` is an older implementation with one device query, already restricted to Apple platforms. Hosted version 8 has a newer per-device delivery pipeline and two device queries. Do not replace that hosted pipeline by deploying the older local function wholesale. The [scoped version 7-to-8 patch](../supabase/patches/send-apns-v7-apple-platform.patch) records the two added platform filters; it did not change the hosted shared files or delivery behavior for Apple devices.

## Migration Notes

`202607060001_android_push_platform.sql` widens the `device_push_tokens.platform` check constraint for existing projects. Fresh databases also get `android` in the initial copied platform migration.

The copied `20260710210430_existing_platform_security_hardening.sql` and `20260804161406_refresh_provider_secret_during_polling.sql` migrations retain the original repository's identifiers. Review existing migration history before deployment rather than replaying copied migrations or replacing hosted functions. The new `android_push_platform_support` migration records the additive change applied above. The broader local `send-push`/FCM pipeline has not been deployed from this workspace.

## Gradey AI

Add `app/google-services.json` from the Firebase project hosting the **current** Gradey AI callables in `europe-west1`. The OAuth-only `gradey-9628e` project is not that service. Register `com.bukovinafilip.gradey` and the signing SHA-256 used for the intended distribution, enable anonymous Firebase Authentication, and configure App Check Play Integrity. A debug emulator/mock test cannot establish production App Check readiness.

The Android client implements streaming replies, Stop, persisted request IDs, recovery before retry, conversation history/deletion, consent revocation, Compute pricing/catalog/caps, and refreshed Gradey ID access proof. It supports replies, tomorrow, priorities, selected weeks, subject review and Planner test preparation. Planner notes require separate opt-in. Context includes selected current school data and actual local observations with source timestamps; failed refreshes and ambiguous histories never manufacture grade changes.

Live verification must cover the deployed Compute catalog and consent revision, a limited-use App Check reply, stream interruption/recovery, same Gradey identity across Apple/Google as applicable, current-school isolation, deletion/revocation, and insufficient Compute. Unit tests and mock response UI tests do not establish callable deployment compatibility. Do not substitute another Firebase project's client file merely to make the build pass.

## Support purchases

The Android client now uses RevenueCat 10.16.0 with Play Billing 8.3.0, replacing RevenueCat 8.10.0 / Billing 7. Billing 7 reached its normal submission deadline on 31 August 2026. The current API calls compile against the upgraded SDK; this does not replace Play test billing acceptance. [Google billing timeline](https://developer.android.com/google/play/billing/deprecation-faq), [RevenueCat v9 migration](https://github.com/RevenueCat/purchases-android/blob/10.16.0/migrations/v9-MIGRATION.md), [v10 migration](https://github.com/RevenueCat/purchases-android/blob/10.16.0/migrations/v10-MIGRATION.md).

Configure repeatable tips as consumables without a lasting entitlement; consumed tips cannot be restored. Lifetime products, if introduced, must be non-consumable. Test restore with two Gradey IDs sharing one Play account against the intended RevenueCat transfer policy. Active cancelled subscriptions can remain eligible until their paid expiry; renewal intent is not an eligibility check.

Configure `gradey.revenueCatAndroidKey` and the Google Play products/offerings matching the Gradey support/tips catalog. Prices and purchase confirmation come from Google Play. Demo builds cannot make purchases. Restore and subscription management are available from Gradey tools → Support Gradey. Implementation uses the [RevenueCat Android SDK](https://www.revenuecat.com/docs/getting-started/installation/android).

## Release builds

Phone release packaging requires the Supabase URL/public anon key, Google web client ID, Apple Services ID, RevenueCat Android key and `app/google-services.json`. These checks catch missing client configuration; they cannot prove that hosted provider settings, App Check, billing products or push credentials are working. An intentional demo release must opt in with `gradey.allowDemoRelease=true`. Debug builds and tests do not need live configuration. Configure your own signing and Play Console release; this repository does not contain production signing keys.

For Google Play builds, also set `gradey.directUpdatesEnabled=false`. This omits the APK installation permission and disables the direct updater. Direct APK distributions default to the updater enabled; they read stable GitHub release assets from this repository and require the same signing key as the installed app. See [updater.md](updater.md) for release manifest generation and publishing. Optional `gradey.versionCode` and `gradey.versionName` properties override the checked-in phone version during a build.

## Android push staging, 29 September

The reviewed [FCM patch for hosted `send-apns` v8](../supabase/staged/send-push-v8/README.md) extends the current per-device delivery pipeline. Its source fingerprint, isolated dispatcher tests, configuration gates and rollback instructions are saved with it. It remains staged, and the Android receiver now deduplicates server dispatch IDs and checks the current owner and linked-school notification setting before display. Deploying the older top-level copied dispatcher is not part of this release path.

## Launcher shortcuts

Long-pressing the Android app icon exposes Add planner item, Timetable, Marks and Gradey AI. These use Android's [static app shortcut mechanism](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts). The shortcuts contain no school records or credentials. Authentication and onboarding still apply when entering a screen.

`gradey://planner/new?title=Homework&type=homework&due=2026-10-02` opens a bounded, unsaved draft. The supported types are homework, test, presentation, note and task; invalid types fall back to homework and invalid dates are ignored. Incoming links never save or change calendar events. An existing draft requires an explicit keep/discard choice before replacement. Android Assistant spoken school-data queries and system search indexing are separate capabilities and are not implemented by these launcher shortcuts.

## Wear artifact

The phone and Wear artifacts use `com.bukovinafilip.gradey` and must be signed with the **same production certificate** for the Play Services Data Layer to connect them. Account for the Play App Signing certificate when distributing either artifact outside Play; a locally signed upload-key APK does not automatically match the certificate on the installed Play app. Wear release signing and the Play Wear track must be configured before distribution. Override the independent Wear artifact version with `-Pgradey.wear.versionCode=<agreed-code> -Pgradey.wear.versionName=<release-name>`; checked-in defaults remain `2` / `0.2.0` until release versioning is agreed. See [Wear companion](wear-companion.md) for the protocol, privacy bounds and acceptance checks.
