# PVCPipe

PVCPipe is an in-development Android app (Android 8.0+, API 26) forked from
[BravePipe](https://github.com/bravepipeproject/BravePipe), which is based on
[NewPipe](https://github.com/TeamNewPipe/NewPipe). Not ready for release.

## Build

The app is in `app/`; the bundled extractor is in `pvcpipe-extractor/`.
See [extractor services](docs/extractor-services.md) for URL handling.

Install Android SDK platform 37 and Java 11, 17, and 21 toolchains, then run
`./gradlew :app:assembleDebug` for a debug APK or `make ci` for checks, unit
tests, and APK builds. `make ci` does not run instrumentation tests or sign a
distribution release.

Run instrumentation tests only on a disposable emulator:
`connectedDebugAndroidTest` can uninstall the app and erase its data on a device.

GPL-3.0-or-later; see [LICENSE](LICENSE).
