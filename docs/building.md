# Building and testing

## What you need

- Android SDK platform 37. The app compiles and targets API 37 and runs on
  API 26, Android 8.0, and later.
- JDK 17 for the app, JDK 11 for the extractor and JDK 21 for Checkstyle in
  both. Gradle finds them as toolchains, so all three have to be installed.
- The Gradle wrapper downloads Gradle 9.6.1. Initial builds also need access
  to the dependency repositories declared in the Gradle settings files.

## Layout

| Path | What is in it |
| --- | --- |
| `app/` | The Android app. Room schemas are in `app/schemas/`. |
| `pvcpipe-extractor/` | The `extractor` and `timeago-generator` Gradle projects; `timeago-parser/` supplies additional Java sources to `extractor`. |
| `buildSrc/` | Shared version parsing/code validation, its regressions, and `checkDependenciesOrder`. |
| `checkstyle/` | The Checkstyle rules for the app. The extractor has its own in `pvcpipe-extractor/checkstyle/`. |
| `docs/` | The guides and records listed in [README.md](README.md). |

`settings.gradle.kts` includes `pvcpipe-extractor` as a composite build and
substitutes it for the app's `com.github.75ohmantenna:pvcpipe-extractor`
dependency. A change in the extractor is in the next app build without a
publishing step. The library is not published anywhere.

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

Native libraries are packaged the legacy way (`useLegacyPackaging` in
`app/build.gradle.kts`), so the installer extracts them. The HLS remuxer runs
`libffmpeg.so` as a program, and Android lets an app execute a file only from
its native library directory. With the default packaging the file stays
inside the APK and every HLS download fails at its last step.
`FfmpegPackagingTest` checks this on a device.

The APKs land in `app/build/outputs/apk/`. The debug build has the
application id `org.seventyfiveohmantenna.pvcpipe.debug`, so it installs next
to a release build. The release build is minified and comes out as
`app-release-unsigned.apk`. No release signing configuration is declared,
so signing a release is a separate step. Android tooling signs the debug APK
with the debug key.

The repository has no GitHub Actions workflows. `make ci` on your own machine
is the only gate, so run it before you push.

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
2. Run `make ci` and exercise the changed behavior. Record which paths used
   controlled peers/emulators and which used real devices. Use the device
   safety procedure below; do not erase an installation to verify a release.
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
- **Offline extractor tests.** `forkCiTest` runs the JUnit tests tagged
  `offline`. They read recorded or made-up answers and never touch the
  network.
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

A test that contacts a website shows what the site answered that day. Go easy
on the sites while testing. [odysee-observations.md](odysee-observations.md)
has an example of a media host that stopped answering after a burst of test
requests.

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
