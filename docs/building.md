# Building and testing

## What you need

- Android SDK platform 37. PVCPipe compiles against and targets API 37; its
  minimum is API 26 (Android 8.0).
- JDK 17 for the app, JDK 11 for the extractor and JDK 21 for Checkstyle in
  both builds. Install all three so Gradle can resolve the declared toolchains.
- The Gradle wrapper downloads Gradle 9.6.1. Initial builds also need access
  to the dependency repositories declared in the Gradle settings files.

## Layout

| Path | What is in it |
| --- | --- |
| `app/` | The Android app. Room schemas are in `app/schemas/`. |
| `pvcpipe-extractor/` | The `extractor` and `timeago-generator` Gradle projects; `timeago-parser/` supplies additional Java sources to `extractor`. |
| `buildSrc/` | Shared version parsing and validation, regression tests, and `checkDependenciesOrder`. |
| `checkstyle/` | The Checkstyle rules for the app. The extractor has its own in `pvcpipe-extractor/checkstyle/`. |
| `docs/` | The guides and records listed in [README.md](README.md). |

`settings.gradle.kts` includes `pvcpipe-extractor` as a composite build and
substitutes it for the app's `com.github.75ohmantenna:pvcpipe-extractor`
dependency. Extractor changes are included in the next app build without a
publishing step. This repository declares no publication workflow.

## Commands

```sh
./gradlew :app:assembleDebug   # debug APK
make ci                        # every check below, then both APKs
```

`make ci` runs two Gradle invocations and stops at the first failure:

| Task | What it does |
| --- | --- |
| `:buildSrc:test` | version-code monotonicity and Android upper-bound regressions |
| `:app:runCheckstyle` | Checkstyle over every Java file under `app/src/` |
| `:app:runKtlint` | ktlint over the app's Kotlin, with the rules in `.editorconfig` |
| `:app:checkDependenciesOrder` | fails if a section of `gradle/libs.versions.toml` is not in alphabetical order |
| `:app:lintDebug` | Android lint |
| `:app:testDebugUnitTest` | the app's unit tests |
| `:pvcpipe-extractor:extractor:forkCiTest` | the extractor's Checkstyle and its tests tagged `offline` |
| `:app:assembleDebug`, `:app:assembleRelease` | both APKs |

Native libraries use legacy packaging (`useLegacyPackaging` in
`app/build.gradle.kts`) so the installer extracts them. The HLS remuxer
executes `libffmpeg.so` from the app's native library directory. Leaving that
binary inside the APK prevents this processing step.
`FfmpegPackagingTest` checks the packaging requirement on a device.

APKs are written under `app/build/outputs/apk/`:

- **Debug:** application ID `org.seventyfiveohmantenna.pvcpipe.debug`, app name
  **PVCPipe Debug**. Android tooling signs it with the local debug key.
- **Release:** default application ID `org.seventyfiveohmantenna.pvcpipe`,
  app name **PVCPipe**. Code and resources are shrunk; without a signing
  configuration, the output is `app-release-unsigned.apk`.

The source namespace remains `org.schabi.newpipe`; it is not an installation
ID. Debug and default release builds can coexist. The optional
`-DpackageSuffix=...` changes the release application ID and display name;
`-DversionNameSuffix=...` changes the displayed version name, not its code.

For an in-place update, keep the installed application ID and signing key,
and do not lower the version code. A debug APK signed on a different machine
may use a different key. Do not uninstall a populated installation to bypass
an identity or signature mismatch; follow the device-safety procedure below.

`make ci` is the repository's local gate. Run it before pushing; it does not
include instrumentation tests or live-network extractor tests.

## Versions

PVCPipe has its own version, in `version.properties` at the top of the
repository. It started at 0.1.0 in October 2026. Before that the version was
`0.28.8-2.8.2`, the NewPipe version followed by the BravePipe version the
fork came from.

- The version is `major.minor.patch`, with minor and patch below 100.
- The app's version code is computed from it (`PvcVersion` in `buildSrc/`):
  1,000,000 + major x 10,000 + minor x 100 + patch. So 0.2.0 is 1000200 and
  1.0.0 is 1010000. Codes above Android's maximum of 2,100,000,000 are rejected.
  The base is above 581013, the last code of the old
  numbering, because Android refuses to install a lower code over a higher
  one.
- The bundled extractor has the same version. Its build reads the same file.
- Change the version only in `version.properties`, not in either build script.

### Release procedure

1. Choose a new version and an unused `v<version>` Git tag. PVCPipe remains
   in development; a version tag is not a claim that every site or receiver
   has been tested.
2. Update `version.properties`, run `make ci`, and exercise the changed
   behavior. Record which paths used controlled peers or emulators and which
   used real devices. Follow the device-safety procedure below; do not erase
   an installation to verify a release.
3. Commit the reviewed changes and create an annotated `v<version>` tag on
   that commit. `version.properties`, app metadata and extractor version
   must agree.
4. Check the submission destination, then push the commit and tag to
   `github.com/75ohmantenna/pvcpipe` only. Keep the local pre-push guard.
   Do not publish to upstream repositories or force-update an existing tag.

The APK built by `assembleRelease` remains unsigned. Creating a Git tag does
not sign it or upload an installable release asset; release signing requires
a separate, securely managed key. Never label a debug-key APK as a signed
production release.

## Tests

- **Build-logic tests.** `./gradlew :buildSrc:test` checks version component
  rollovers and rejects codes outside Android's supported range.
- **App unit tests.** `./gradlew :app:testDebugUnitTest`, with
  `--tests '<pattern>'` for a subset. They run on the JVM.
- **Offline extractor tests.** `forkCiTest` runs JUnit tests tagged `offline`
  and the extractor's main-source Checkstyle task. The tagged tests use
  recorded or synthetic responses rather than live websites.
- **All extractor tests.** `./gradlew :pvcpipe-extractor:extractor:test` runs
  everything, including the tests that contact the websites. Narrow it with
  `--tests`.
  `-Ddownloader=MOCK` replays recorded answers, `-Ddownloader=REC` records
  new ones, and the default is the real network.
- **Instrumentation tests** under `app/src/androidTest/` need a device and
  are not part of `make ci`.

Use a disposable emulator for Gradle-managed instrumentation.
`connectedDebugAndroidTest` was observed uninstalling the debug app and its
test package from a phone after testing. Uninstalling deletes app data.
[local-playlist-mutations.md](local-playlist-mutations.md) has the record and
the way to run a test package on an existing installation without that.

A live-network test establishes only what a site returned during that run.
Avoid bursts of requests. [odysee-observations.md](odysee-observations.md)
records a media host becoming unavailable during repeated test requests.

## Conventions the checks enforce

- Java follows `checkstyle/checkstyle.xml`, with exceptions in
  `checkstyle/suppressions.xml`. Checkstyle does not ask for Javadoc, but a
  method's Javadoc has to carry a tag for every parameter.
- The extractor's Checkstyle rejects `toLowerCase()` and `toUpperCase()`
  without a `Locale`. With the language of the device set to Turkish they
  turn `i` into `İ` and `I` into `ı`, and host names stop matching.
- Kotlin follows ktlint. `./gradlew :app:formatKtlint` fixes what it can.
- Each section of `gradle/libs.versions.toml` stays in alphabetical order.

## Saved state

Fragments, activities and dialogs save their state by hand in Android
`Bundle`s. The app once used an annotation processor for this. The keys it
generated are still read, so state saved by an older build survives an
update. `DownloadDialogSavedStateTest` and `SearchFragmentSavedStateTest`
exercise restoration through those older keys.

`org.schabi.newpipe.util.StateSaver` is something else. It keeps large list
state out of the `Bundle`, in memory across a configuration change and on
disk otherwise.
