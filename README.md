# PVCPipe

Work in progress. Not ready for release.

Requires Android 8.0 (API 26) or newer.

PVCPipe is a fork of [BravePipe](https://github.com/bravepipeproject/BravePipe),
created by [evermind-zz](https://github.com/evermind-zz). BravePipe is based on
[NewPipe](https://github.com/TeamNewPipe/NewPipe).

## Repository layout

- `app/` contains the Android application.
- `pvcpipe-extractor/` contains the bundled extractor and its tests.
- [`docs/extractor-services.md`](docs/extractor-services.md) explains how URLs flow from the app
  through service link handlers and extractors.

Gradle substitutes the extractor dependency with the bundled composite build, so
the application and extractor are built together without publishing an extractor
artifact.

## Build and verification

The Android app targets SDK 37 and requires Android SDK platform 37 to build
(`app/build.gradle.kts`). Gradle uses Java 17 for app compilation, Java 11 for
the extractor, and Java 21 for Checkstyle. The included Gradle wrapper uses
Gradle 9.6.1; configure an Android SDK and the required Java toolchains before
running the builds.

From the repository root, run `./gradlew :app:assembleDebug` to build a debug
APK. `make ci` runs app Checkstyle, ktlint, dependency ordering, lint, unit tests,
and the extractor's deterministic offline tests and Checkstyle, then builds debug
and release APKs (`Makefile`). It does not run Android instrumentation tests or
sign a distribution release.

GPL-3.0-or-later; see [LICENSE](LICENSE).
