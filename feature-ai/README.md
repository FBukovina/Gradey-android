# Gradey AI for Android

This module implements the latest iOS Gradey AI callable contract, including streamed replies, the six contextual actions and Gradey Compute. It is an Android client; it does not contain or deploy the model backend. Source and mock-test parity do not establish that the production services are configured or working.

## Implemented behavior

- General chat, tomorrow, study priorities, week summary, subject help and test preparation. Entry points can open a selected subject, Planner test or week without sending a request automatically.
- Firebase callable streaming with incremental formatted answers, copying, Stop, chat creation/loading/deletion, scoped delete-all, consent acceptance and revocation. The controller preserves an in-flight reply and draft across activity recreation.
- Server-provided Compute catalog, action availability/cost, remaining balance and reservation display. Each generation freezes its context, catalog version, maximum accepted cost and client message ID.
- Saved request recovery through `gradeyAIGetRequest`. A pending or completed request is checked before another generation, including when the remaining balance is zero. A confirmed failed/cancelled request gets a new ID only after an explicit retry. An uncertain response is never silently regenerated.
- Purpose- and school-scoped conversations; owner/student changes clear the old visible context. English, Czech and both iOS regional language variants use the selected app locale for prompts and requests.
- Paired Wear general-chat relay with existing phone consent, active recurring Support eligibility, the same Compute contract, owner/school guards, explicit cancellation and bounded sequenced responses. It shares no school records automatically. The phone service and watch presentation live in `app` and `wear`.

## Context and privacy

General chat attaches no school records. Other actions select only their required subjects, dates, Planner events and insight summaries. The context allowlist excludes credentials, raw provider responses, the school URL, account profile, teacher identities and attendance. A per-installation random salt produces an opaque school scope.

School context uses the shared prepared grade calculation, actual available history baselines and account-scoped observations captured when a school snapshot was loaded. The first snapshot is a quiet baseline. Missing history is not invented, and unavailable/stale sections carry explicit freshness metadata. Planner notes are included only for the selected test after a separate opt-in; other contextual actions strip notes.

The source context is bounded to 80 subjects, 80 recent marks overall and five marks per subject, and 120 lessons. Purpose selection further limits lessons to 60, events to 30 and insight summaries to five. Selected test notes are limited to 1,000 characters. The encoded context is limited to 96 KiB, the complete request to 128 KiB and the question to 2,000 Unicode code points.

Local recovery storage contains identifiers only, not prompt/reply text, school records or account tokens. The phone saves the reference durably before starting generation. Wear also saves a hash of the immutable question/locale for duplicate-request validation. Sign-out clears AI recovery metadata. Server chat retention and deletion remain governed by the deployed backend and the consent notice.

## Required release configuration

1. Supply `app/google-services.json` for the Firebase project hosting the existing Gradey AI functions in `europe-west1`; an unrelated OAuth-only project is insufficient.
2. Enable anonymous Firebase Authentication and register the Android package/release certificate for Firebase App Check with Play Integrity. The client uses the authenticated Firebase SDK transport, a 120-second callable timeout and limited-use App Check tokens for generations. No debug bypass is installed.
3. Verify the deployed endpoints: `gradeyAIGetStatus`, `gradeyAIAcceptConsent`, `gradeyAIRevokeConsent`, `gradeyAIListChats`, `gradeyAICreateChat`, `gradeyAILoadChat`, `gradeyAIDeleteChat`, `gradeyAIDeleteAll`, `gradeyAIGetRequest` and streaming `gradeyAIStreamReply`.
4. Verify the current Compute catalog/context-selection contract, fresh Gradey access-token proof, server-side entitlement validation and paid/quota settlement. The client sends the signed-in Gradey account proof; local eligibility checks do not replace backend authorization. Wear additionally requires an active recurring Support or Support Plus entitlement with a future expiry.
5. Exercise consent changes/revocation, streaming interruption/recovery, chat deletion, account/student switching, App Check and paired-watch delivery on release-signed builds with authorized test accounts.

Demo mode sends no AI requests. Missing configuration shows an unavailable state; disabled service, missing consent, unsupported catalog/actions and insufficient Compute prevent a new generation. No live AI generation, paid transaction or backend deployment has been verified by the local tests described below.

## Verification

```sh
./gradlew :feature-ai:testDebugUnitTest :feature-ai:lintDebug :feature-ai:assembleDebugAndroidTest
./gradlew :feature-ai:connectedDebugAndroidTest
```

Unit coverage includes wire formats and timestamps, streaming/controller lifecycle, purpose selection, privacy and byte/item bounds, real history/freshness projections, context isolation, immutable retry/recovery, storage-failure gating and Wear consent/support/quota/identity/protocol behavior. Mocked Compose tests exercise formatted replies, chat deletion and Stop/recovery. These tests do not attest a live Firebase project, release App Check, real Compute settlement, a real paired watch or backend deletion.

See [`docs/ios-parity.md`](../docs/ios-parity.md) for the wider audit and explicit external release gates.
