# Sunshine Navigator

Send a place from your map app to your scooter through [Sunshine](https://sunshine.rescoot.org).

Navigator is an Android app with OAuth sign-in, scooter selection, saved places and destination previews. Share a location to **Send to scooter**, check the destination, then tap a connected scooter to send it. Selecting a scooter, signing in or opening a shared place does not send a command.

## Features

- Google Maps links, `geo:` links, coordinates and address search
- Exact-coordinate destination previews with density-aware OpenStreetMap tiles
- Personal favourites, recent destinations and scooter-saved places
- Scooter artwork, connection and vehicle state, and licence plates
- OAuth with PKCE, refresh-token rotation and Android Keystore storage
- Persistent pending-request recovery and server receipt checks
- Light and dark themes

A successful response means Sunshine accepted the request. Check the vehicle dashboard to confirm receipt.

## Android builds

Open [Actions → Android](https://github.com/rescoot/sunshine-navigator/actions/workflows/android.yml), select a successful run and download its `android-builds-<commit>` artifact. GitHub requires sign-in to download workflow artifacts.

The archive contains:

- `sunshine-navigator-debug.apk` — installable test build
- `sunshine-navigator-release-unsigned.apk` — unsigned, optimized APK
- `sunshine-navigator-release-unsigned.aab` — unsigned Android App Bundle
- `SHA256SUMS` — checksums for the build files

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

The Android workflow runs build, test and lint checks plus an API 34 emulator UI check on pull requests and pushes to `main`. It also supports manual runs. Test reports and offline preview fixtures are uploaded as separate artifacts; preview fixtures are not live-device acceptance tests.

## Connection

Use **Continue with Sunshine** for the default server, or configure an HTTPS server and public OAuth client in connection settings. The callback is `dev.ligustah.lsnav:/oauth2redirect`; the app requests `read`, `write` and `scooter_control` scopes.

Manual API tokens are also supported. Never commit access tokens, signing keys or account data.

## Credits

- Based on [LSNav by ligustah](https://github.com/ligustah/lsnav).
- Sunshine branding and scooter artwork come from the Sunshine project.
- Map data and tiles: © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).
- Plate typeface: GL-Nummernschild-Eng by Gutenberg Labo; redistribution terms are bundled in [`plate_font_license.txt`](app/src/main/res/raw/plate_font_license.txt).
