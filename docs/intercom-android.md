# Native support messenger

Android includes the optional `feature-support` module. Like the current iOS `IntercomIdentity.presentMessenger`, each explicit launch logs out the prior support session and starts a fresh anonymous conversation. Gradey ID and school identities are never passed to the SDK. The Help Center remains available without SDK configuration, in demo mode, before age eligibility is confirmed, and after a chat failure.

## Configuration and integration

Supply **Android** installation credentials from the Intercom workspace, never the iOS key or an Intercom server secret:

- `gradey.intercomAndroidApiKey`: public installation key beginning with `android_sdk-`.
- `gradey.intercomAppId`: workspace app ID.
- Optional `gradey.intercomRegion`: `us`, `eu` or `aus`, matching the workspace's hosting region. Leaving it blank selects the SDK’s documented US default; it must not be guessed from the user’s location.

The app passes the two public fields to `SupportConfiguration.from(...)` and calls `GradeySupport.initialize(application, configuration)` only from `Application.onCreate`. Invalid, blank and unresolved-placeholder configuration produces an unavailable messenger without initializing an SDK client. The SDK's included content provider only registers lifecycle tracking for later initialization; it does not log in a contact.

The app calls `SupportHelpDialog(onDismiss, onOpenHelpCenter, allowed, isDemo, sessionKey)`. Displaying this dialog never logs into Intercom. Only the **Start anonymous chat** action does. The dialog explains the anonymous session, technical app/device information and user-selected messages/attachments. The local `sessionKey` only cancels outdated UI requests; it is not sent to Intercom.

Call `GradeySupport.reset()` before account/school ownership changes, sign-out, entering demo mode, or revoking age eligibility. Reset and dialog disposal revoke in-flight presentation. Because the SDK cannot cancel login, a cancelled request blocks another login until its callback settles; late callbacks are logged out and never open the messenger. A 20-second UI timeout keeps the Help Center usable. No custom user attributes, identity registration, account JWT, school payload, analytics events, or Intercom push-token registration are implemented.

The SDK is initialized without login, its custom launcher and in-app messages are hidden, proactive carousel/survey content is suppressed, and its theme follows the system. Any stored anonymous login is cleared at process initialization. Once explicitly opened, Intercom can process its own normal session/device telemetry and support messages. Initialization itself is not promised to be network-free.

The app build resolves `gradey.intercomRegion` into Intercom’s supported integer metadata under `<application>`:

```xml
<meta-data android:name="io.intercom.android.sdk.server.region"
    android:value="@integer/intercom_server_region_eu" />
```

The corresponding `_us` or `_aus` SDK resource is selected for those regions. An empty property explicitly selects `_us`, matching the SDK default. Enable Android Messenger traffic in the Intercom workspace before a real support smoke test.

## SDK choice and dependency impact

Pinned `io.intercom.android:intercom-sdk-base:18.9.5`, the official stable release dated 2026-09-28, without Intercom FCM. Its declared dependencies are honored, including Compose BOM `2026.06.01`, Activity `1.13.0`, Lifecycle `2.10.0`, Core `1.18.0`, Navigation `2.9.8`, AppCompat `1.8.0`, and Paging `3.5.1`. No resolution strategy downgrades or excludes required AndroidX libraries.

Downloaded official AAR metadata confirms Core 1.18.0 and Activity 1.13.0 require compile SDK 36 and AGP 8.9.1; the project already uses compile SDK 36 / AGP 9.2.1. The SDK requires min SDK 23 while the app uses 26. Earlier stable 17.4.3 would still upgrade Compose/Lifecycle and omit newer fixes; no toolchain downgrade is needed to retain the latest SDK. The root app must run its full UI regression after this dependency change.

The base SDK manifest includes its non-exported UI activities/providers and optional attachment/audio permissions. The integration does not request microphone or storage permissions itself; any attachment action is a user action in the vendor UI. Intercom notification handling is not installed or mixed with Gradey FCM.

## Validation and operational bounds

`SupportMessengerTest` uses only a fake SDK seam. It covers Android-key validation, absent configuration, no login on construction, explicit anonymous startup, cancellation/sign-out during a suspended login, duplicate callbacks, retries, and presentation failure. The isolated module compile, AAR dependency checks, all seven unit cases, and lint passed on 2026-09-29 (`/tmp/gradey-support-validation.log`). Two fake-only dialog instrumentation cases cover the explicit action, dismissal, demo and age gates; device execution is pending the root integration run. Unit tests and lint must not initialize the real SDK or create a support contact. Device regression should use empty optional Intercom configuration unless a real support test is separately authorized.

Real anonymous chat availability still depends on correct Android credentials, workspace Android traffic settings, network access and the workspace's identity policy. There is no identified-user path: enabling identified conversations later requires a server-issued Intercom JWT and an explicit privacy/product decision. Do not substitute the iOS key or mint a JWT in the app.

Official references, checked 2026-09-29:

- [Android installation](https://developers.intercom.com/installing-intercom/android/installation/)
- [Android SDK usage and anonymous login](https://developers.intercom.com/installing-intercom/android/using-intercom/)
- [Hosting region configuration](https://developers.intercom.com/installing-intercom/android/data-hosting-region-configuration/)
- [Messenger identity security](https://developers.intercom.com/installing-intercom/android/secure-your-messenger/)
- [18.9.5 release](https://github.com/intercom/intercom-android/releases/tag/18.9.5)
- [Published 18.9.5 POM](https://repo.maven.apache.org/maven2/io/intercom/android/intercom-sdk-base/18.9.5/intercom-sdk-base-18.9.5.pom)
