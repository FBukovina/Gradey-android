# Gradey Android 0.2.0

Source comparison: [FBukovina/Gradely](https://github.com/FBukovina/Gradely), commit `a73bbd04bf65145d96064df0d40d3336fa3a88ea`.

## Implementation

- Google sign-in uses Android Credential Manager; demo mode is an explicit, isolated choice.
- School sign-in accepts Bakaláři and EduPage, including code/device verification and parent student selection.
- Cached marks survive refresh failures. Live sessions never fall back to sample grades.
- Marks support subject search, average filters, sorting and a local what-if calculator. Eligible Bakaláři subjects can request a school prediction; failures leave the local estimate visible with an explanation.
- Linked accounts use the existing Supabase endpoints, with account/student scoped local cache and encrypted credentials.
- Absence planning supports dates and individual upcoming timetable lessons, locally saved plans, estimated absence percentages and school-supplied threshold warnings when the required attendance data is available.
- Strava.cz supports sign-in, balance, menu, allergens/prices, order/cancel confirmation and disconnect. Changes follow the provider's staged/save/rollback protocol.
- Account settings include quiet hours, notification permission, export and permanent deletion confirmation.
- Grade-history summaries show average and mark-count changes for the selected linked account. Subject charts compute running averages from the available marks.
- The home-screen widget reads the phone's saved timetable and opens Timetable when tapped.
- Wear OS receives timetable snapshots, retains them offline, and requests refresh from its paired phone. School credentials are not sent over the data layer.
- Main navigation, school/account state and ongoing ViewModel requests survive activity recreation. Meals and AI reload their saved/provider data when their screens are recreated.

## Build and service setup

Follow [android-configuration.md](android-configuration.md). The debug APK uses isolated demo mode when live service configuration is absent. Provider and backend code can be verified with fixtures without logging into real accounts.

For Google sign-in, use the web OAuth client ID and register the Android package and signing fingerprints. Implementation follows [Android Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).

The phone and watch must have the same package ID (`com.bukovinafilip.gradey`) and signing certificate to exchange data with the [Wear Data Layer](https://developer.android.com/training/wearables/data/overview). Install the APK appropriate to each device; the watch APK is not a phone update. Version 0.1.0 used a separate watch package; remove that prototype manually before installing the new watch companion if both appear.

Copied backend changes are local until deliberately deployed. Notifications require Firebase configuration, Android notification permission, server FCM secrets, and a scheduled grade poller. Quiet hours use Europe/Prague (CET/CEST), matching the backend preference schema.

## Release validation

Verified on 22 September 2026:

- Phone and Wear debug APKs build successfully as version `0.2.0` / code `2`.
- All 84 JVM/unit tests and 26 backend helper tests pass.
- All 4 Android emulator tests pass: navigation/search through activity recreation, encrypted plan save/restore/delete, meal replacement/cancellation confirmations, and sign-out cleanup. Device: Medium Phone, Android 17.
- Android lint: 0 errors, 56 warnings. The remaining warnings include dependency/version updates and optional API/style suggestions.
- Release configuration guard rejects missing live settings; an explicit demo release passes, including with the Gradle configuration cache reused.
- Manual visual checks cover Today, the compact tools list, the planner, Meals, and the demo-only unavailable states for AI/support. Today distinguishes finished lessons from an unavailable timetable; attendance rings and labels fit without overlap.

The 0.2.0 phone/Wear APKs and verification captures were local previews in the ignored `artifacts/0.2.0/` directory. The current phone preview is [version 0.2.1](https://github.com/FBukovina/Gradey-android/releases/tag/v0.2.1), which includes these features and the [in-app updater](updater.md). Debug previews use isolated demo data unless live services are configured.

Real school sign-in, meal ordering, Google OAuth, Firebase AI, cloud account deletion/export, push delivery, and paired watch delivery require configured services and appropriate test accounts/devices. No external meals were ordered and no live user accounts were modified during implementation. Nothing has been published to Google Play.

## Parity limits and release prerequisites

- Absence plans are local what-if scenarios, not school absence submissions or cloud-synced plans. They preserve the selected timetable snapshot; later schedule changes do not automatically update saved lessons. Forecasts add each selected lesson to both missed and taught totals and exclude other future attendance, so they are estimates rather than end-of-term predictions.
- EduPage currently supplies daily attendance counts, without per-subject attendance totals or an absence limit. Its plans can count selected lessons, but cannot show per-subject percentages or threshold warnings. CAPTCHA challenges require the school website; device approval is checked when the user requests it.
- EduPage parent/multi-student accounts support local profile switching. Cloud polling/history for them is disabled because the shared provider cookie cannot safely identify which child is selected. Parent accounts must be connected separately on each device.
- Grade averages, charts and the local calculator support the numeric grading scale used by the app, including its `+`/`-` modifiers. Points and unsupported grade types remain visible but do not enter these calculations. Local estimates can use inferred or fallback weights; EduPage has no school-side what-if prediction.
- Gradey AI uses the existing Firebase backend with consent and App Check. Replies appear when completed; Android does not yet provide token streaming or the historical baseline needed for AI trend context.
- Meals sessions are encrypted locally; canteen account registration in the cross-device account hub is not part of this version.
- Wear refresh requires the paired phone; direct school credentials are intentionally absent from watch snapshots.
- AI, subscriptions, push delivery and live sign-in require the app/service configuration in [android-configuration.md](android-configuration.md). Backend changes and migrations have not been deployed by this task.
