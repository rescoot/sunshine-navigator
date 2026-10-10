# Sunshine Navigator

Send a place from your map app to your scooter through [Sunshine](https://sunshine.rescoot.org).

Navigator has native Android (Kotlin/Compose) and iOS (SwiftUI/MapKit) clients with OAuth sign-in, scooter selection, saved places and destination previews. Share a location to **Send to scooter**, check the destination, then tap a connected scooter to send it. Selecting a scooter, signing in or opening a shared place does not send a command.

## Features

- Google Maps links, `geo:` links, coordinates and address search
- Exact-coordinate previews with density-aware OpenStreetMap tiles on Android and native MapKit on iOS
- Personal favourites, recent destinations and scooter-saved places
- Scooter artwork, connection and vehicle state, and licence plates
- OAuth with PKCE, refresh-token rotation and platform-secure credential storage
- Persistent pending-request recovery and server receipt checks
- Light and dark themes

A successful response means Sunshine accepted the request. Check the vehicle dashboard to confirm receipt.

## Android builds

Open [Actions → Android](https://github.com/rescoot/sunshine-navigator/actions/workflows/android.yml), select a successful run and download its `android-builds-<commit>` artifact. GitHub requires sign-in to download workflow artifacts.

Normal runs upload an installable `sunshine-navigator-debug.apk` and `SHA256SUMS`. For optimized builds, choose **Run workflow → release_builds**; that archive also includes `sunshine-navigator-release-unsigned.apk` and `sunshine-navigator-release-unsigned.aab`.

**Signing:** CI debug builds use disposable runner-generated keys. They are not upgrade-compatible with privately signed builds, and keys can differ between CI runs. Uninstalling an incompatible build removes its local app data. Release artifacts must be signed before distribution. CI has no production signing keys and does not publish to an app store.

Android 8.0 (API 26) or newer is required. The application ID is `dev.ligustah.lsnav`.

## Build locally

Requirements: JDK 17, Android SDK platform 36 and build-tools 35.0.0. Set `ANDROID_HOME` or configure `sdk.dir` in an untracked `local.properties` file.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease :app:bundleRelease
```

APK output is under `app/build/outputs/apk/`; bundle output is under `app/build/outputs/bundle/`.

The API client is generated during the build from `openapi/sunshine-api.json`.

## Checks

```sh
# JVM tests, native-rendered preview fixtures and lint
./gradlew :app:recordRoborazziDebug :app:lintDebug

# Connected emulator/device UI tests
./gradlew :app:connectedDebugAndroidTest
```

## CI budget

Both workflows run only for their platform's source/build files, on pull requests and pushes to `main`. Documentation-only changes start neither workflow. Superseded runs are cancelled; no scheduled builds or build matrices run.

| Workflow | Automatic checks | Manual options |
| --- | --- | --- |
| Android | Debug APK, unit tests, lint | `release_builds`: optimized unsigned APK/AAB; `ui_tests`: API 34 emulator; `visual_fixtures`: preview captures |
| iOS simulator | Core tests on the macOS host, app and share-extension compilation | `ui_tests`: boot iPhone simulator for hosted unit and UI tests |

Gradle task outputs and dependencies are cached. iOS compiler caches are scoped to the runner architecture, Xcode version and project manifests; pull requests cannot update that shared cache. Normal iOS builds compile only the runner's simulator architecture and do not boot a simulator.

Build artifacts are uploaded on `main` and manual runs, not pull requests, and retained for seven days. Detailed reports are uploaded for failures or requested diagnostics. Preview fixtures and simulator tests are not physical-device acceptance tests.

## iOS

The iOS client targets iOS 17 and newer. It includes a **Send to scooter** share extension, a native vector map preview, saved places, scooter selection, account sign-in and receipt-only command recovery. Sign in through the main app before sending directly from the share sheet. Shared places received while signed out are retained for the next app launch.

App and extension use a shared Keychain access group for OAuth credentials and an App Group for pending requests. Cross-process file locks serialize token rotation and command reservation. Selecting or restoring a place never sends it.

On a Mac with Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
cd ios
swift test --parallel
xcodegen generate
open SunshineNavigator.xcodeproj
```

Choose the `Navigator` scheme and an iPhone simulator. The [iOS simulator workflow](https://github.com/rescoot/sunshine-navigator/actions/workflows/ios.yml) uploads a simulator `.app` archive, not an iPhone-installable IPA. No Apple distribution certificates or store credentials are configured.

Physical-device builds require your Apple team to provision:

- `org.rescoot.sunshine-navigator`
- `org.rescoot.sunshine-navigator.share`
- App Group `group.org.rescoot.sunshine-navigator`
- Shared Keychain group `$(AppIdentifierPrefix)org.rescoot.sunshine-navigator`

Authenticated device testing and store submission are separate from simulator CI.

## Connection

Use **Continue with Sunshine** for the default server, or configure an HTTPS server and public OAuth client in connection settings. The callback is `dev.ligustah.lsnav:/oauth2redirect`; the app requests `read`, `write` and `scooter_control` scopes.

Android also supports manual API tokens. Never commit access tokens, signing keys or account data.

## Credits

- Based on [LSNav by ligustah](https://github.com/ligustah/lsnav).
- Sunshine branding and scooter artwork come from the Sunshine project.
- Android map data and tiles: © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright). iOS previews use Apple MapKit.
- Plate typeface: GL-Nummernschild-Eng by Gutenberg Labo; redistribution terms are bundled in [`plate_font_license.txt`](app/src/main/res/raw/plate_font_license.txt).
