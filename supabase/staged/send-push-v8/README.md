# Android FCM patch for hosted send-apns v8 — staged only

Nothing in this directory has been deployed. No migration, secret setting, device registration or notification send was performed. The Firebase project for the release Android app has not been identified or configured.

This changes the existing `send-apns` function without changing its URL, service-role/cron authentication, parent event claim/finalization RPCs or device delivery table. Keeping that function name avoids silently disconnecting the existing polling/cron callers. It sends Apple devices through APNs and Android devices through FCM HTTP v1.

## Source and review boundary

The baseline was retrieved read-only on 2026-09-29 from Supabase project `ieonvnfzkbyybbkfxupq`, `send-apns` version **8**, bundle SHA-256 `5b615c89acbc7f0d1165b3f1f58f40c58e508857fb34b35c6f437da4f66fb945`.

- `upstream/functions/` is the unchanged hosted source, with per-file SHA-256 in `upstream/source.json`.
- `functions/` is the proposed deployable source. Only `send-apns/index.ts` changes; the two new shared modules are `fcm-delivery.ts` and `push-routing.ts`. Other shared files retain the hosted contents.
- `send-apns-v8-fcm.patch` is a reviewable diff against that hosted baseline. It must **not** be blindly applied over the repository's older top-level `supabase/functions/send-apns/index.ts` or its preexisting patch.
- `deno.test.json` replaces the Supabase client with a fixture only for tests. Do **not** deploy this test import map, tests, or `upstream/`.

Before any authorized deployment, retrieve the live function again and check its version, bundle hash and full source:

```sh
python3 supabase/staged/send-push-v8/verify_source.py fresh-get-edge-function.json
```

The argument is the JSON object returned by a fresh read-only `supabase_get_edge_function` call for the same project/function, or that tool's text-content wrapper. Without the argument the script only checks saved baseline integrity; it does not confirm that production is unchanged. A mismatch requires rebasing and reviewing the patch against the newer source.

Read-only inspection of hosted `register-device` **v5** (bundle SHA-256 `3297643a9f02d9c68ee03c04e92f4ecd3f2bb538be0cbfe4d0ad39e2d72475eb`) showed that it accepts the supplied platform/environment, stores the token encrypted through `store_provider_secret`, and upserts by `token_hash`. Its function does not need an Android routing change. A read-only `pg_constraint` query on the same date confirmed the live `device_push_tokens_platform_check` already allows `ios`, `macos`, and `android`; `device_push_tokens_environment_check` allows `sandbox` and `production`. No platform migration is required on this project. Recheck if applying elsewhere; do not blindly reapply the local migration.

## Delivery behavior

Both target snapshots and lookup queries now include `android`, alongside `ios` and `macos`. Accepted or suppressed delivery rows stay terminal, so an Android failure does not resend already accepted Apple notifications. Quiet-hour events still defer before sealing their device targets; devices registered during the quiet window are included when the summary is released.

FCM uses a short-lived OAuth access token obtained with an RS256 service-account JWT, the `firebase.messaging` scope, a fixed Google token endpoint and fixed FCM endpoint. Requests disable redirects, time out after 15 seconds and never log or persist provider response messages, private keys or device tokens. The token cache refreshes at least 60 seconds before expiry and shares one in-flight refresh. These mechanics follow [Firebase's HTTP v1 authorization guide](https://firebase.google.com/docs/cloud-messaging/send/v1-api) and [Google's service-account JWT protocol](https://developers.google.com/identity/protocols/oauth2/service-account).

Android receives a **data-only** payload, with high priority and a one-hour TTL. There is no `notification` object that could bypass the app's identity, preference and privacy checks. The data includes `userID`, `dispatchID`, privacy-filtered `title`/`body`, `lockScreenDetail`, `count` and `url`; all values are strings. Every payload includes `accountIDs`, a JSON string array of the complete sorted unique linked-school IDs (at most 32 IDs, at most 128 safe ASCII characters per ID and at most 2,048 encoded bytes). Oversized or invalid scopes are rejected, never truncated. Single-school batches also include the compatible `accountID`; a summary spanning multiple accounts omits that single ID rather than assigning the first school. Android rejects missing/malformed/ambiguous scopes and requires every included ID to remain locally owned with alerts enabled. Legacy single-account `accountID`/`linkedAccountID` remains accepted. Single events include `eventID`; summaries include `summaryID`/`summaryCount`. `subjectName` and `markText` exist only for a single event when permitted by the saved detail preference. Unknown privacy settings fail closed. Android display text is clipped at complete Unicode code-point boundaries and further shortened, if necessary, to keep the complete encoded data object within 3,500 bytes. JSON escaping and UTF-8 bytes both count. Owner, dispatch, complete account scope, routing IDs and privacy metadata are never truncated; an oversized required envelope still fails. The captured APNs copy helper is unchanged. This uses Firebase's [client-handled data message](https://firebase.google.com/docs/cloud-messaging/customize-messages/set-message-type) format.

The existing per-device `apns_id` becomes Android `dispatchID`. Quiet summaries use the same stable hash of quiet-window key plus device ID as v8. Retries are at least once: Firebase acceptance and database update cannot form one transaction. The Android receiver must persistently deduplicate by owner plus `dispatchID`, use a stable notification identity and re-check current owner/preferences before display. A FCM `messageId` alone is insufficient. No FCM `collapse_key` is set: independent marks must not replace each other or exceed the [four simultaneously retained collapse keys](https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types).

Only an HTTP 404 with a typed `google.firebase.fcm.v1.FcmError` containing `UNREGISTERED` invalidates the device token. Generic 400/404, payload errors and project/credential errors retain it. 401/403/408/429/5xx use the existing bounded five-attempt retry policy; 401 also clears the OAuth cache. Retries honor `Retry-After`, use at least the existing backoff and add up to 15 seconds of jitter. Other permanent request failures suppress that delivery, not the token. This deliberately narrows invalidation to the unambiguous case in [Firebase's error contract](https://firebase.google.com/docs/cloud-messaging/error-codes).

With FCM disabled or structurally missing configuration, Android deliveries stay pending and reschedule in 15 minutes without consuming attempts; Apple delivery continues. `last_delivery_error=fcm_configuration_unavailable` makes this visible. Alert on that condition and clear the backlog deliberately before enabling an old staging deployment; enabling later can release pending alerts. Do not treat disabled FCM as a passing production setup.

## Configuration and release gates

Set these only through the deployment environment's protected secret configuration after the exact project is confirmed. No values belong in this repository or the Android APK:

| Environment variable | Required value |
| --- | --- |
| `FCM_ENABLED` | Exact `true` only once project, permissions and release receiver are ready |
| `FCM_PROJECT_ID` | Firebase project ID belonging to the release Android registration tokens |
| `FCM_CLIENT_EMAIL` | Service-account email permitted to send messages to that project |
| `FCM_PRIVATE_KEY` | Matching PKCS#8 private key; PEM newlines or escaped `\n` are accepted |

Existing APNs, Supabase, provider-secret and cron configuration remains required. The FCM HTTP v1 API must be enabled and the service account must have the Firebase Cloud Messaging send permission on the target project. The Android `google-services.json`/build configuration must identify the same sender/project. Keep service credentials server-side. Restart/redeploy workers when rotating credentials so the process-local OAuth client is replaced.

Remaining gates before publishing:

1. Recheck live source/schema and review the patch plus Android platform constraint migration. Preserve the current claim/finalization functions, row uniqueness and RLS. No schema mutation is included here.
2. Configure the confirmed Firebase project and secret values, and confirm the release Android receiver's owner checks, durable `dispatchID` dedupe, local quiet/privacy policy and runtime notification permission.
3. Obtain authorization for deployment and test sends, then test with explicit test accounts/devices: foreground/background, signed out and switched owner, disabled school/global alerts, all privacy levels, quiet-hours summary, token rotation/unregistered token and mixed Apple+Android delivery. This work has performed none of those live sends.
4. Confirm scheduling keeps invoking the existing `send-apns` endpoint and observe accepted/retried/suppressed counters without logging sensitive payloads. Track `fcm_configuration_unavailable`, OAuth/project failures and retry-limit suppressions. FCM acceptance is not proof of display or delivery while an app is force-stopped/offline.

## Local validation

```sh
deno check supabase/staged/send-push-v8/functions/send-apns/index.ts
deno test --config supabase/staged/send-push-v8/deno.test.json supabase/staged/send-push-v8/tests
python3 supabase/staged/send-push-v8/verify_source.py
```

The tests need **no Deno runtime permissions**. Fetch, environment access, server registration and Supabase calls are fixtures; RSA/ECDSA keys are generated in memory solely for signature verification. Type dependencies may be downloaded by Deno during module resolution. Nineteen tests pass, covering digest scope completeness, deduplication and bounds, maximum-scope Unicode/escaped display budgets and intact identity metadata, emoji-safe Android copy, the actual entrypoint's mixed-device targeting, partial retry, disabled transport, retry limit, owner isolation, account suppression and quiet-summary release, plus OAuth signature/scope/expiry/cache, privacy payloads, routing and FCM error classification. This is not a live push or database concurrency test.
