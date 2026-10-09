# PVCPipe

Under development; not ready for release.

Requires Android 8.0 (API 26) or later.

PVCPipe is a fork of [BravePipe](https://github.com/bravepipeproject/BravePipe)
by [evermind-zz](https://github.com/evermind-zz), which is based on
[NewPipe](https://github.com/TeamNewPipe/NewPipe).

## Repository layout

- `app/` contains the Android application.
- `pvcpipe-extractor/` contains the bundled extractor and its tests.
- [`docs/extractor-services.md`](docs/extractor-services.md) explains how URLs flow from the app
  through service link handlers and extractors.

Gradle replaces the extractor dependency with the bundled composite build, so
the app and extractor build together without publishing an extractor artifact.

## Build and verification

The app targets SDK 37 and requires Android SDK platform 37 to build
(`app/build.gradle.kts`). Gradle uses Java 17 for app compilation, Java 11 for
the extractor, and Java 21 for Checkstyle. The included wrapper uses Gradle
9.6.1. Configure an Android SDK and these Java toolchains before building.

Run `./gradlew :app:assembleDebug` from the repository root to build a debug
APK. `make ci` runs app Checkstyle, ktlint, dependency ordering, lint, unit tests,
and the extractor's deterministic offline tests and Checkstyle; it then builds
debug and release APKs (`Makefile`). It does not run Android instrumentation tests
or sign a distribution release.

## Search history

Accepted searches persist independently of result loading and search-screen lifecycle.
Search-history writes and deletions execute in request order, so clearing history cannot
restore an earlier queued search. When search history is disabled in settings,
searches are not recorded.

`HistoryRecordManagerTest` covers queued writes and clearing against in-memory Room.
`SearchHistoryUiTest` covers actual search-fragment destruction and attached error
presentation with an isolated database. These are Android instrumentation tests,
not part of `make ci`; run them directly on a disposable emulator. Do not run
`connectedDebugAndroidTest` on a device with an existing installation: its
cleanup has been observed uninstalling the app and deleting its private data.

## List-view preferences

List fragments ignore preference callbacks delivered after detachment. A new view
reads the current display mode when created.

GPL-3.0-or-later; see [LICENSE](LICENSE).
