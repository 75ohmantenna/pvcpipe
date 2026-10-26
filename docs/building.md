# Building and testing

## What you need

- Android SDK platform 37. PVCPipe compiles against and targets API 37; its
  minimum is API 26 (Android 8.0).
- JDK 21 for app and extractor compilation, and JDK 25 for Gradle and
  Checkstyle. Install both so Gradle can resolve the declared toolchains.
  The daemon JVM criteria in both builds select Java 25 even when the shell's
  `JAVA_HOME` points to another version. No automatic JDK download is configured.
- Python 3 for the offline recorded-fixture privacy checks in `make ci`.
  These checks use only the Python standard library.
- Both Gradle wrappers download Gradle 9.8.2 and verify its SHA-256 checksum.
  A globally installed Gradle does not change the wrapper version. Initial builds
  also need access to the dependency repositories declared in the settings files.

The current build uses AGP 9.4.1, Kotlin 2.4.21 and KSP 2.3.12. Kotlin's
[published fully supported matrix](https://kotlinlang.org/docs/gradle-configure-project.html)
for 2.4.21 ends at Gradle 9.7.0 and AGP 9.3.1, while permitting newer versions
with qualifications. This repository exercises its newer combination through
`make ci`; that is project-specific evidence, not certification by every vendor.

Java 21 language and bytecode targets do not make the complete desktop JDK API
available on Android. Android SDK APIs and core-library desugaring still determine
which library calls can run on a device.

The 2026-10-22 toolchain cutover passed `make ci` on Linux x64: build-logic,
app and offline extractor tests, static checks, Android lint, and both APK
variants. That run did not include instrumentation or receiver/hardware
validation.

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

## OMP code intelligence

Start OMP from the repository root. `.omp/config.yml` enables structural
search for Java/Kotlin code patterns and language-server access for delegated
agents. Automatic formatting and post-edit diagnostics are not enabled by this
project configuration; Checkstyle and ktlint remain the formatting authorities.

Language servers are installed per user, not bundled with the app. Put
machine-specific executable paths, JDK paths and workspace import options in
`.omp/lsp.json` (gitignored), then use OMP's LSP `reload` action with `file: "*"`
after changing that file or installing a server. Import the root Gradle build,
not just `app/`, so the bundled extractor is included.

For Java, JDT LS needs a server JVM supporting its release. Gradle build/import
uses the pinned JDK 25, while the app and extractor compile with JDK 21.
AGP 9 is not compatible with JDT LS's legacy Android importer. Configure
`java.import.gradle.arguments` with `--init-script` followed by the absolute
path to `.omp/android.init.gradle`, and disable
`java.jdt.ls.androidSupport.enabled` so the two importers do not compete.
Keep Gradle import enabled.

The adapter imports the debug variant's real SDK, dependency classpath, source
roots and composite project edges. It extracts AAR class JARs and removes only
JPMS descriptors from Android-specific analysis-cache copies: Android's boot
classpath is non-modular, while desktop module detection otherwise makes JDT
require a missing `java.base`. Original dependency archives are unchanged.
The adapter follows the Android variant's Java language level and does not
compile app code. Java analysis of Kotlin classes, generated sources and resource
classes (`R.jar`) reuses existing build outputs; refresh them with a normal debug
build after relevant edits. AAR analysis caches are keyed by archive contents so
replacing an archive cannot retain stale classes. This is not a replacement for
`make ci`.
JDT LS is not a Kotlin source-navigation provider; Java calls into compiled
Kotlin classes can lack a go-to-definition target even when Java-to-Java and
app-to-extractor navigation work. Use the Kotlin server for Kotlin source.

JetBrains Kotlin language-server preview builds expire. If startup reports an
expired build, update from the [official releases](https://github.com/Kotlin/kotlin-lsp/releases)
and verify the download checksum. A successful `--help` invocation does not
prove that the server can initialize. Verify an actual cross-file definition
lookup after Gradle import and indexing finish; a same-file lookup alone does
not establish that dependencies or other modules were imported.

Use the standalone Kotlin distribution for this integration; do not assume that
the separately licensed commercial Java/Kotlin editor extension has the same
terms. Do not activate a trial or bypass a license check to make an agent's
language-server requests work.

## Commands

```sh
./gradlew :app:assembleDebug   # debug APK
make ci                        # every check below, then both APKs
```

`make ci` first runs the offline fixture-sanitizer tests and checks recorded
fixtures for known captured identifiers. It then runs five serial Gradle
invocations and stops at the first failure:
static/build-logic checks, JVM tests, Android lint, the debug APK, then the release
APK. Each invocation uses `--no-daemon` so compiler and lint memory is released
between stages.

The app build defaults to one Gradle worker, no parallel project execution,
in-process Kotlin compilation, a 2 GiB daemon heap and a 1 GiB metaspace limit.
Standalone extractor builds use one worker, a 1 GiB heap and 768 MiB of metaspace.
On a small laptop, run only one build at a time and use `--no-daemon` for individual
commands too. These limits do not include the OS, IDE or forked test/check workers.

| Task | What it does |
| --- | --- |
| `:buildSrc:test` | version-code monotonicity and Android upper-bound regressions |
| `:app:runCheckstyle` | Checkstyle over every Java file under `app/src/` |
| `:app:runKtlint` | ktlint over the app's Kotlin, with the rules in `.editorconfig` |
| `:app:checkDependenciesOrder` | fails if a section of `gradle/libs.versions.toml` is not in alphabetical order |
| `:app:lintDebug` | Android lint |
| `:app:testDebugUnitTest` | the app's unit tests |
| `:pvcpipe-extractor:extractor:checkstyleMain` | the extractor's main-source Checkstyle |
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
  app name **PVCPipe**. Code and resources are shrunk. With local release signing
  configured, the output is `app-release.apk`; otherwise it is
  `app-release-unsigned.apk`.

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

## Release signing

Release signing is optional and never falls back to the Android debug key.
The build reads `~/.config/pvcpipe/signing.properties`, or the file selected by
`PVCPIPE_SIGNING_PROPERTIES`. The properties must contain `storeFile` (an absolute
path to a PKCS12 keystore), `storePassword`, `keyAlias` and `keyPassword`.
An absent default file leaves releases unsigned; an invalid explicit path or
incomplete properties file fails the build instead of silently skipping signing.

Keep the keystore and credentials outside the checkout, in directories accessible
only to their owner (`0700`), with files readable only by that owner (`0600`).
Do not commit them, copy them into build artifacts, publish Gradle caches, or put
passwords on command lines. Run `./gradlew --no-daemon :app:assembleRelease` to
build with the configured key, and verify the APK certificate before distributing
it. Signing an APK does not publish it or make a Git tag a release.

Keep a private inventory of the keystore, alias, public certificate and encrypted
backup location outside the public checkout. Store the backup's decryption
passphrase separately from the encrypted bundle; do not publish machine-specific
recovery paths or credential locations in project documentation.

Copy the encrypted bundle to offline storage and save the decryption passphrase
separately in a password manager. Both are needed for recovery; copies on the
same machine alone do not protect against losing it. A local recovery round-trip
was verified on 2026-10-22. After restoring elsewhere, update
`storeFile` to the restored absolute path and preserve the alias and passwords.
Do not generate a new signing identity for existing installations: updates must
retain their original signing key.

Use a separate application ID such as `-DpackageSuffix=.smoke` for disposable
release-mode testing. This keeps smoke-test data separate from both the debug
and production apps. Verify the package ID as well as the certificate before
installing; never uninstall a populated app to bypass a signing mismatch.

The 2026-10-22 signed, minified smoke build was exercised on a Pixel 6a running
Android 17/API 37. It initially exposed a release-only startup failure: R8 kept
PrettyTime's locale bundle classes but removed their public no-argument
constructors, which `ResourceBundle` invokes reflectively. The targeted rule in
`app/proguard-rules.pro` retains those constructors. After that fix, cold startup,
live search, video playback, pause/seek/resume, background audio and playlist
persistence across a cold process restart passed in the separate smoke package.
The populated debug app was not replaced or cleared. Repeat a release-mode cold
start after shrinker/dependency changes; assembling an APK and running JVM tests
alone did not catch this failure.

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

`assembleRelease` signs with the external release configuration when it is
available, and otherwise produces an unsigned APK. Creating a Git tag does not
sign or publish an APK. Never label a debug-key APK as a production-signed release.

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
  `checkstyle/suppressions.xml`. Checkstyle does not ask for Javadoc, but existing
  Javadoc needs a summary sentence ending with a period, and a method's Javadoc
  has to carry a tag for every parameter. Both builds use `SummaryJavadoc`;
  Checkstyle removed the old `JavadocStyle` check. License-only and author-only
  metadata belongs in ordinary block comments, not Javadoc.
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
