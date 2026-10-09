# PVCPipe

PVCPipe is an in-development Android app (Android 8.0+, API 26) forked from
[BravePipe](https://github.com/bravepipeproject/BravePipe), which is based on
[NewPipe](https://github.com/TeamNewPipe/NewPipe). Not ready for release.

## Build

The app is in `app/`; the bundled extractor is in `pvcpipe-extractor/`.
Code and tests define current behavior. The consolidated developer guides cover
[services](docs/services.md), [playback](docs/playback.md),
[library data](docs/library-data.md), and [downloads](docs/download-preparation.md).
The [device-test safety record](docs/local-playlist-mutations.md) and
[dated smoke results](docs/pvcpipe-device-smoke.md) describe observed devices,
not a current behavior specification.

Install Android SDK platform 37 and Java 11, 17, and 21 toolchains, then run
`./gradlew :app:assembleDebug` for a debug APK or `make ci` for checks, unit
tests, and APK builds. `make ci` does not run instrumentation tests or sign a
distribution release.
GitHub Actions is disabled to avoid CI credit usage. Run `make ci` locally
before submitting changes; instrumentation tests require a disposable emulator.

The Checkstyle task covers most Java sources; remaining legacy downloader
exclusions are listed in `app/build.gradle.kts`. Remove an exclusion only after
that source passes Checkstyle. Fragment, activity, and dialog state is saved
explicitly in Android `Bundle`s; the original generated-state keys remain
readable after app updates. `org.schabi.newpipe.util.StateSaver` is a separate
disk-backed cache for large list state, not an annotation processor.

Run instrumentation tests only on a disposable emulator:
`connectedDebugAndroidTest` can uninstall the app and erase its data on a device.

GPL-3.0-or-later; see [LICENSE](LICENSE).
