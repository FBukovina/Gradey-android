# Working on Gradey Android

Native Kotlin/Compose port of Gradey for iOS. The iOS app is the reference for behaviour, wording and layout.

## Layout

- `core-model`, `core-domain` (pure Kotlin), `core-network`, `core-data`, `core-ui` (design system), `feature-*`, `app`.
- The iOS source is not in this repo. Ask for a checkout of `FBukovina/Gradely` when porting a feature.
- Features do not depend on each other. `app` wires them: screens take plain data and callbacks, and shared pieces are passed in as composable slots.
- Build output goes to `~/Library/Caches/GradeyAndroid-build`, not the repo.

## Checks before a commit

```sh
./scripts/check-design-tokens.sh                 # no raw colours, text sizes, radii or Material controls outside core-ui
./gradlew :core-domain:test :core-network:test   # pure Kotlin modules
./gradlew :app:testDebugUnitTest                 # Android modules use testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest         # needs an emulator; runs in demo mode
./gradlew :app:lintDebug
```

Add `--offline` when dependencies are cached. The `wear` module's dependencies are usually not, so leave it out of offline runs.

## Release build

```sh
./gradlew :app:assembleRelease -Pgradey.directPreview=true \
  -Pgradey.supabaseUrl=… -Pgradey.supabaseAnonKey=… -Pgradey.googleWebClientId=…
./gradlew :app:generateReleaseBaselineProfile -Pgradey.allowDemoRelease=true   # after changing screens
```

Releases must keep the signing key of earlier direct downloads. See `docs/updater.md` for the manifest and publishing steps.

## Conventions

- Strings come from the iOS catalog (`Localizable.xcstrings`) in five folders: `values`, `values-en`, `values-cs`, `values-en-rCO` and `values-cs-rUS` (the two lowercase "chronically online" variants). Never add a string to only one of them.
- A changed privacy notice means raising `PrivacyRevision` and rewriting `PrivacyChanges` in `WelcomeScreen.kt`.
- School data that only Bakaláři has (report cards, homework, Komens) must be hidden for EduPage accounts.
- Demo mode (`MockSchoolRepository`, `*DemoData`) needs sample data for every new feature; device tests rely on it.
- Device tests find controls by test tag. Close the on-screen keyboard and scroll to a button before tapping it; the device suite takes about ten minutes.
- Data that stays on the device (friends, the dance course) is cleared in `GradeyViewModel.clearSignedInState` on sign-out.
