# Gradey AI for Android

This module ports the callable wire contract in the original iOS app's `Gradely/Platform/FirebaseGradeyAIClient.swift` and minimizes school context before sending it. It does not contain or deploy a model backend.

## Enable in a release build

- Supply an Android `app/google-services.json` for the Firebase project that hosts the existing Gradey AI functions in `europe-west1`.
- Enable anonymous Firebase Authentication in that project.
- Register the Android app and its release signing certificate with Firebase App Check / Play Integrity. Requests retain App Check enforcement; no debug bypass is installed.
- Deploy and configure the existing callable functions independently: `gradeyAIGetStatus`, `gradeyAIAcceptConsent`, `gradeyAIRevokeConsent`, `gradeyAIListChats`, `gradeyAICreateChat`, `gradeyAILoadChat`, `gradeyAIDeleteChat`, `gradeyAIDeleteAll`, and `gradeyAIStreamReply`.
- Verify consent terms, server quota enforcement, final callable responses, persisted history, and deletion behavior on a release-signed Android build before release.

The client uses the Firebase SDK's authenticated callable transport, a 120-second timeout, and limited-use App Check tokens for replies. With the pinned Android Firebase dependency set, reply calls consume the final `completed`/`failed` result and reload persisted conversation history; token-by-token streaming is not exposed. The backend must return that final result for non-streaming callable requests.

Demo mode never initializes this AI client or sends AI requests. Missing Firebase configuration presents an unavailable state. Server-disabled status, missing consent, and exhausted quota prevent sending.

## Privacy and request bounds

School context is opt-in per section and defaults to sharing neither marks nor timetable. A question and the existing chat history still go to the service after consent. A per-installation random salt produces an opaque school scope; school credentials, account profile, raw provider responses, attendance, and teacher identities are excluded from the context allowlist.

Selected marks are capped at 80 subjects, 80 recent marks overall, and five per subject. Timetable context is capped at 120 lessons across this week and next week. Text fields are bounded, context is limited to 96 KiB, full reply requests to 128 KiB, and prompts to 2,000 characters. Android's current history model lacks the baseline values required by the iOS trend contract, so trends are marked unavailable.

An uncertain reply retains its client message ID while retrying the same question to allow backend deduplication. The UI offers conversation reload after errors. It never substitutes local generated answers for server responses.

## Verification

`./gradlew :feature-ai:testDebugUnitTest :feature-ai:compileDebugKotlin`

Unit tests cover status defaults, alternate wire formats, timestamp mapping, context selection/privacy, byte/item limits, scope isolation, and persisted history parsing. Live Firebase calls, App Check attestation, and backend consent/deletion behavior require configured integration testing.
