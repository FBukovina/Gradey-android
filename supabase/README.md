# Gradey 2.0 Supabase Backend

This folder contains the Gradey ID platform backend:

- Android uses Supabase Auth with Google identity tokens. Enable Google in your Supabase Auth provider settings and configure the matching web OAuth client. Apple auth remains available for the iOS app.
- Linked provider sessions are stored as encrypted payloads through `store_provider_secret`. Bakaláři credentials are included so the cloud poller can establish and refresh an independent token family; EduPage passwords stay on the device.
- `poll-new-marks` baselines marks on first link, then inserts new mark events only for unseen fingerprints.
- `send-push` delivers APNs and Android FCM data messages; Android checks the current signed-in account before showing an alert. The legacy `send-apns` endpoint only addresses Apple devices.
- Global mark alerts, per-account preferences, and quiet hours are checked before delivery. Quiet hours currently use Europe/Prague; deferred events are reconsidered on later school polls for up to seven days.

## Remote project

- Project ref: `<your-project-ref>`
- Project URL: `https://<your-project-ref>.supabase.co`
- Region: `eu-central-1`
- App redirect URL: `gradey://auth`

This repository contains deployment sources. No remote project, credentials, migration state, or live delivery was verified or changed during the Android implementation. Apply and review the migrations, configure authentication and delivery secrets, and test against your own project before release.

Required project secrets:

```sh
supabase secrets set PROVIDER_SECRET_KEY="at-least-32-random-characters"
supabase secrets set CRON_SECRET="random-cron-secret"
supabase secrets set APNS_TEAM_ID="..."
supabase secrets set APNS_KEY_ID="..."
supabase secrets set APNS_TOPIC="com.bukovinafilip.BakalariMarks"
supabase secrets set APNS_PRIVATE_KEY_P8="$(cat AuthKey_XXXXXXXXXX.p8)"
```

Schedule polling with Supabase scheduled functions:

```sh
supabase functions deploy poll-new-marks --no-verify-jwt
supabase functions deploy send-push --no-verify-jwt
supabase functions deploy send-apns --no-verify-jwt
```

Run `poll-new-marks` every few minutes and pass `x-cron-secret: $CRON_SECRET`; the function chooses the next per-account poll time using the 15-minute daytime / hourly overnight rule.

The remote schedule is managed by `migrations/202607010001_gradey_scheduled_polling.sql`. It stores the project URL and cron secret in Supabase Vault, then calls `/functions/v1/poll-new-marks` every five minutes.

## Apple auth maintenance

Apple auth settings live in `config.toml` and are pushed with the Supabase CLI. The Apple client secret is a signed JWT generated from the Apple `.p8` key and expires after about six months, so rotate it before expiry and run `supabase config push --project-ref <your-project-ref> --yes` with `APPLE_CLIENT_SECRET` set in the shell environment.

Do not commit Apple private keys, client secret JWTs, Supabase service role keys, or cron secrets.

## Passkeys

Do not enable passkeys until Gradey has a stable HTTPS domain and Associated Domains entry for the passkey Relying Party ID. Changing the Relying Party ID later would strand already-created passkeys.

## Android push and verification

Set `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, and `FCM_PRIVATE_KEY` to the Firebase service-account details on the backend. The Android app needs its matching `google-services.json`; keep private keys out of the Android app and source control.

The original Gradey security-hardening and provider-token-refresh migrations are included with their original migration identifiers. They restrict privileged secret functions to `service_role`, enforce secret ownership, and add encrypted token rotation. They have not been applied to any database by this task.

Run the isolated backend contract tests with Node.js 24 or newer:

```sh
node --test supabase/functions/_shared/*.test.ts
```

These tests cover token-family isolation and renewal, safe provider URL validation, and Prague quiet-hour boundaries. They do not replace live Firebase/APNs and database integration checks.

EduPage parent sessions use a shared server cookie whose selected child changes when switching profiles. Background polling and cloud grade history are deliberately unavailable for parent/multi-student sessions until the fetched child identity can be verified. The phone still supports student selection, on-demand grades, attendance and timetable. This prevents one sibling's marks from being attributed to another.

Android also refuses to activate an EduPage parent cookie downloaded from the cloud unless it has an existing local session matching the school, parent identity and selected child. Sign in to EduPage directly on each device to establish that session. Cloud grade history is hidden for those accounts even when connected to an older backend.
