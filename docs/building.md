# Building and testing

## What you need

- Android SDK platform 37. PVCPipe compiles against and targets API 37; its
  minimum is API 26 (Android 8.0).
- JDK 21 for app and extractor compilation, and JDK 25 for Gradle and
  Checkstyle. Install both so Gradle can resolve the declared toolchains.
  The daemon JVM criteria in both builds select Java 25 even when the shell's
  `JAVA_HOME` points to another version. No automatic JDK download is configured.
- Python 3 for the offline recorded-fixture privacy and deletion-driver checks
  in `make ci`.
  These checks use only the Python standard library.
- Both Gradle wrappers download Gradle 9.9.1 and verify its SHA-256 checksum.
  A globally installed Gradle does not change the wrapper version. Initial builds
  also need access to the dependency repositories declared in the settings files.

The build scripts declare the compiler toolchains. The version catalogs,
wrapper properties and daemon JVM criteria declare the other tool versions.
Android API levels and core-library desugaring determine which Java APIs the
app can use at runtime.

## Dependency integrity boundary

The version catalogs and build scripts pin direct dependencies. SHA-256
checks cover the Gradle distribution and the vendored HLS converter. Resolved
transitive dependencies lack a complete lock and Gradle verification allowlist.
Version locks fix selection, and verification checksums authenticate the bytes
against reviewed values.

For dependency-verification work, review where initial checksums come from.
Validate the app, build logic, composite and standalone extractor builds,
instrumentation compilation and host-specific tools on the supported platforms.
Use an isolated cache to verify that the policy rejects a modified dependency.

## Layout

| Path | What is in it |
| --- | --- |
| `app/` | The Android app. Room schemas are in `app/schemas/`. |
| `pvcpipe-extractor/` | The `extractor` and `timeago-generator` Gradle projects; `timeago-parser/` supplies additional Java sources to `extractor`. |
| `buildSrc/` | Shared version parsing and validation, regression tests, and `checkDependenciesOrder`. |
| `checkstyle/` | The Checkstyle rules for the app. The extractor has its own in `pvcpipe-extractor/checkstyle/`. |
| `docs/` | The guides and verification records listed in [README.md](README.md). |

`settings.gradle.kts` includes `pvcpipe-extractor` as a composite build and
substitutes it for the app's `com.github.75ohmantenna:pvcpipe-extractor`
dependency. Extractor changes are included in the next app build without a
publishing step. This repository declares no publication workflow.

## Commands

```sh
./gradlew :app:assembleDebug   # debug APK
make ci                        # every check below, then both APKs
```

`make ci` verifies the converter and checks recorded fixtures for known
captured identifiers. It runs the two named Python suites. The deletion-driver
suite substitutes validation, native execution and shell calls, so it runs
without ADB.

The remaining stages run in order and stop on failure. They cover static
and build-logic checks, JVM tests, Android lint, debug assembly and release
assembly. Each invocation uses `--no-daemon` to release compiler and lint memory
between stages.

`make ci` sets `--max-workers=1` on each Gradle invocation. Ordinary builds
use the worker and memory defaults in `gradle.properties`, with user-home
properties taking precedence. The gate also uses the configured heap and
metaspace settings.

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
`FfmpegPackagingTest` checks the packaging requirement on a device. Its test-only
runner redirects output to a unique temporary file, enforces a 30-second wait,
and terminates/reaps the child and removes the file on failure or interruption.
Controlled JVM child fixtures cover normal/nonzero exits, verbose output, timeout
and interruption; they do not prove packaged Android executable compatibility.
The checked-in converter AAR supplies MPEG-TS, fragmented MP4 and raw ADTS
input, plus the H.264 framing filter needed by concat. `make ci` checks its
pinned checksum. Ordinary builds use the prebuilt artifact. See the
[native build recipe](../app/libs/README.md) for rebuilding with the pinned NDK.

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

## Disposable physical-device verification

`-Ppvcpipe.debugVerificationId=<token>` builds a separately named **PVCPipe
Verification** debug app at
`org.seventyfiveohmantenna.pvcpipe.verification.<token>.debug`, with a matching
`.test` instrumentation package and isolated download-test provider authority.
The token must match `[a-z][a-z0-9]{1,31}`. Without the property, normal debug
identity, label and provider authority are unchanged. Combining this property
with `pvcpipe.restoreFixture` is rejected.

Choose a fresh random token. Before installation, inspect both APK manifests,
test target and signing certificates; verify the exact packages are absent.
Use an initial install scoped to the intended user **without replacement or
permission-grant flags**. Never install over an existing package, and never use
`connectedDebugAndroidTest` on a populated physical device. If another profile
cannot be inspected, do not probe it or assume it is empty; a fresh token and
fail-if-existing installation must still prevent replacement of a global package.

`DownloadTimeoutDeviceTest` runs only with both instrumentation arguments
`disposableVerification=true` and `expectedPackage=<exact verification target>`.
It validates the package before creating its own fixture directory or preferences.
Without both arguments it is not device verification: absent arguments skip the
test, while partial/invalid opt-in fails. Run only this selected class through
direct instrumentation; do not run the whole destructive integration suite.
Successful evidence includes `timeoutCycles=2`, `exactBytesVerified=true` and the
verified byte count, in addition to a passing test result.

After the safe installation checks, the selected invocation is:

```sh
adb -s <serial> shell am instrument --user <user> -w \
  -e class us.shandian.giga.service.DownloadTimeoutDeviceTest \
  -e disposableVerification true -e expectedPackage <verification-target> \
  <verification-target>.test/androidx.test.runner.AndroidJUnitRunner
```

The test uses a real foreground About activity, a loopback HTTP peer, private
synthetic bytes and the real bound download service. It invokes `onTimeout`
directly, waits for a readable checkpoint, explicitly resumes, removes the last
test binding and checks the native started/foreground lifetime twice. This is
not evidence that Android delivers its six-hour timeout callback. Do not change
global timeout budgets, permissions or device settings to run it.

After collecting results, remove only the newly installed verification packages
for the intended user and verify their absence. Compare the original app's
package/version/update metadata before and after; this does not inspect or prove
the contents of its private data.

[Recorded Pixel verification](https://github.com/75ohmantenna/pvcpipe/blob/e7cb3f6b25b55cc47b2b409e9b2f621fe9e311bb/docs/building.md#isolated-pixel-verification-2026-11-13)
covers the isolated debug configuration and a separately signed release startup.

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

Preserve the release signing identity and maintain an encrypted offline backup
with its decryption secret stored separately. Machine-specific recovery paths
and credentials stay outside the checkout. For disposable minified testing, use
a separate release application ID such as `-DpackageSuffix=.smoke`; verify its
package and certificate before installation.

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
- The app validates the version's shape and code during configuration through
  `PvcVersion`. A standalone extractor build only requires the `version` property
  and copies its text; it does not run that Android version-code validation.
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

`make ci` runs the two offline Python suites above, build-logic and app JVM tests,
offline extractor tests, style and
dependency checks, lint and both APK builds. It does not run instrumentation or
live-service tests. App tests can be filtered with
`./gradlew --no-daemon :app:testDebugUnitTest --tests '<pattern>'`.

`./gradlew --no-daemon :pvcpipe-extractor:extractor:test` includes live-network
extractor suites; narrow it with `--tests` and avoid request bursts.
`-Ddownloader=MOCK` selects recordings, `-Ddownloader=REC` records responses, and
the default downloader uses the network. Recorded answers do not establish
current website availability. Inspect test assertions and doubles before
expanding their evidence into a runtime guarantee.

Build native tests without installing them:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
```

Use a disposable emulator for Gradle-managed instrumentation. In this checkout,
`connectedDebugAndroidTest` was observed uninstalling the debug app and its instrumentation package after
testing. Reinstalling an APK cannot recover private data deleted by uninstall.
The [dated incident](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/local-playlist-mutations.md) is retained in Git history.

## Testing on an existing installation

1. Verify the actual serial, installed package and signing certificate.
2. Inspect the selected task's installation/cleanup, runner and every selected
   test's database, file, preference and external-storage isolation.
3. Never use a path that uninstalls packages or clears app data on a user's
   installation. Isolated test databases do not make an installing task safe.
4. If an update is necessary, verify signing compatibility before
   `adb -s <serial> install -r`. Do not uninstall to bypass a mismatch.
5. Select only verified isolated tests through direct instrumentation. For the
   playlist package, after inspecting its current sources and installing matching
   APKs safely, the invocation is:

```sh
adb -s '<verified-serial>' shell am instrument -w \
  -e package org.schabi.newpipe.local.playlist \
  org.seventyfiveohmantenna.pvcpipe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Verify the result, installed packages and preserved data afterwards. An ADB exit
code alone is not a JUnit result. If signing, isolation or cleanup is uncertain,
use a separate disposable environment. Do not assume older successful runs make
an expanded suite safe.

### Isolated native HLS/download scenarios

The debug variant permits cleartext **only for `127.0.0.1`** through
`debug_network_security_config.xml`, enabling the isolated loopback peers on
older Android versions. Non-loopback cleartext remains denied; release does not
include this debug-only policy. This is not permission to install or clear data.

After inspecting the current harness lifecycles and verifying a disposable,
compatible debug installation, run the selected classes directly:

```sh
adb -s '<verified-emulator-serial>' shell am instrument -w \
  -e class org.schabi.newpipe.player.resolver.PlaybackSourcesIntegrationTest,org.schabi.newpipe.player.resolver.Media3SourceEngineIntegrationTest,us.shandian.giga.postprocessing.HlsDownloadIntegrationTest \
  org.seventyfiveohmantenna.pvcpipe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

These harnesses use temporary media/cache/storage and no application database.
They still require the installation/signing checks above.
`PlaybackSourcesIntegrationTest` constructs sources without transfers;
`Media3SourceEngineIntegrationTest` observes real engine loading;
`HlsDownloadIntegrationTest` completes controlled downloads and native processing.
None establishes current public Rumble reachability, decoded receiver playback,
or a general Cloudflare bypass. Build APKs separately with
`./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest`, then inspect
the selected emulator/package/signatures before installation. Do not set the
restore-fixture property for these classes.

For offline Rumble verification, run
`./gradlew :pvcpipe-extractor:extractor:forkCiTest --tests '*services.rumble*'`.
Report Android decoding, completed downloads, live HTTP and physical-receiver
results with the build and environment used for each check.

## Controlled validation tools

Host drivers under `tools/` document their required fixtures, APKs and arguments;
read their source and `--help` before use. The drivers are separate from `make ci`;
only the offline deletion-driver orchestration unit tests run in the gate.

| Driver | Scope |
| --- | --- |
| `restore_emulator_validation.py` | Restore choices, interrupted activation and rollback; normal and fixture test APKs |
| `bookmark_emulator_validation.py` | Bookmark queue, draft ownership and failures |
| `subscriptions_screen_validation.py`, `subscriptions_release_fixture.py` | Controlled subscription presentation and release fixtures |
| `pr151_cold_restart.py`, `download_warm_retry.py` | Controlled HTTP download restart/retry fixtures |
| `download_deletion_crashes.py` | Guarded deletion SIGKILL phases and retained-data checks |

Restore validation also needs a separate AndroidTest APK built with
`./gradlew --no-daemon -Ppvcpipe.restoreFixture=true :app:assembleDebugAndroidTest`.
Copy the normal test APK before building it: both variants use the same output
path. The fixture uses the guarded `RestoreFixtureRunner` and a separate
`org.seventyfiveohmantenna.pvcpipe.debug.restorefixture` installed test package.

Some suites require host nonce files and explicit disposable arguments. Running
all instrumentation without their preparation can skip or fail them. Preserve
logs and distinguish JVM, controlled peers, emulator and actual hardware results.

## Debug and editor tools

Use Android Studio App Inspection or `adb run-as` on a disposable debug app for
network, database and layout inspection. Network inspection does not cover every
native socket or WebView stack. Do not edit preferences behind running caches or
share captured cookies or private data.

OMP configuration is in `.omp/config.yml`; machine-specific LSP settings belong
in the ignored `.omp/lsp.json`. JDT LS imports Android through
`.omp/android.init.gradle` with its legacy Android importer disabled. Build the
debug variant to refresh generated/compiled Java-to-Kotlin analysis inputs.
Use a compatible standalone Kotlin language server; do not activate a commercial
trial or bypass a license check for agent integration. Neither LSP diagnostics
nor a successful server launch replace `make ci` or cross-file lookup checks.

## Conventions the checks enforce

- Java follows `checkstyle/checkstyle.xml`, with exceptions in
  `checkstyle/suppressions.xml`. The extractor uses its own files under
  `pvcpipe-extractor/checkstyle/`. Neither enables `MissingJavadocMethod`.
  Both use `SummaryJavadoc` for existing summaries. The app's `JavadocMethod`
  uses its default parameter/return-tag checks; the extractor explicitly allows
  missing parameter and return tags. License-only and author-only metadata
  belongs in ordinary block comments, not Javadoc.
- The extractor's Checkstyle rejects `toLowerCase()` and `toUpperCase()`
  without a `Locale`. With the language of the device set to Turkish they
  turn `i` into `İ` and `I` into `ı`, and host names stop matching.
- Kotlin follows ktlint. `./gradlew :app:formatKtlint` fixes what it can.
- Each section of `gradle/libs.versions.toml` stays in alphabetical order.

## Saved state

Fragments, activities and dialogs explicitly save state in Android bundles.
`DownloadDialogSavedStateTest` and `SearchFragmentSavedStateTest` verify
that their older generated keys are still read. Channel tabs save a compact
fallback in the bundle and use `StateSaver` for a full handler when available.
`StateSaver` keeps larger state in memory across a configuration change or
serializes it to private cache.
