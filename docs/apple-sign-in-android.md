# Android access to existing Apple Gradey IDs

The iOS app signs into Supabase with Apple's native identity token. Android originally offered Google only. Supabase account and school-link ownership uses the authenticated user's UUID. An Apple user who chose Hide My Email usually has a private relay email that differs from their Google email, so Google sign-in must not be presented as a guaranteed route to the same Gradey ID. Gradey does not merge accounts by school credentials or locally compare emails to transfer data.

## Implemented Android flow

The Android client now supports Apple browser sign-in with Supabase PKCE. It generates a random verifier and independent app state, saves the pending request in encrypted device storage before opening the browser, and exchanges the returned one-use authorization code with the matching verifier. Pending requests expire after ten minutes. Logout, explicit cancellation, a new provider sign-in, and successful or failed code exchange invalidate the attempt. A late exchange cannot restore a session after logout.

The callback is exactly `gradey://auth` with query parameters. An attempt-specific `gradey_state` query parameter must match the encrypted pending request. The client rejects unexpected hosts/paths, duplicate state or code, implicit token responses, stale requests, and replay. Access and refresh tokens are obtained only from the HTTPS token endpoint and are never passed through Android intents. The Supabase user UUID from the token response remains the account owner.

The button is available only when a nonempty `gradey.appleOAuthClientId` build property names the expected Apple Services ID, the live public auth settings enable Apple, and the backend's authorization redirect actually uses that exact Services ID on Apple's HTTPS authorization endpoint. The iOS native bundle ID is explicitly rejected as a web client ID.

## Live configuration observation, 2026-09-29

A read-only public settings request confirmed that Apple and Google are enabled. An authorization redirect inspection showed that Apple browser authorization currently uses the iOS bundle ID `com.bukovinafilip.BakalariMarks`. According to Supabase's documentation, native sign-in still works with that ID, but browser sign-in requires the Apple Services ID first in the provider's Client IDs list. The Android Apple button therefore remains unavailable in the current configuration. No provider settings or backend deployments were changed.

## Configuration needed before enabling

1. Create or confirm an Apple Services ID associated with the same primary Apple App ID as the existing iOS app, preserving the intended Apple identity grouping.
2. Configure that Services ID's domain and return URL for the existing Supabase project. Apple's return URL is `https://<project>.supabase.co/auth/v1/callback`.
3. In Supabase's Apple provider, put the Services ID first and retain the native iOS bundle ID as an accepted audience. Configure a valid Apple OAuth client secret and its required rotation process.
4. Allow the app redirect `gradey://auth` in Supabase's redirect configuration and verify that `gradey_state` is preserved through the complete flow.
5. Set `gradey.appleOAuthClientId` to that Services ID in the Android build. It is an identifier, not a client secret. Never package an Apple private key or OAuth client secret in the APK.
6. Verify a real iOS-created account, including a Hide My Email account, signs into Android with the same Supabase user UUID and linked school accounts. Also verify cancellation, browser return after process recreation, and a new Apple user's missing-name fallback. No live user authentication was performed during implementation.

Google identities with the same verified email may be linked automatically by Supabase. Different-email identities require an explicit authenticated identity-linking flow; that separate account-management feature is not implemented. Existing distinct Supabase accounts are not merged automatically.

Sources: [Supabase Apple sign-in](https://supabase.com/docs/guides/auth/social-login/auth-apple), [Supabase identity linking](https://supabase.com/docs/guides/auth/auth-identity-linking), [Supabase PKCE](https://supabase.com/docs/guides/auth/sessions/pkce-flow), [Apple environment configuration](https://developer.apple.com/documentation/signinwithapple/configuring-your-environment-for-sign-in-with-apple).
