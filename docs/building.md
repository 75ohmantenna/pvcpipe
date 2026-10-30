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

## Debug inspection

Run the debug variant (`org.seventyfiveohmantenna.pvcpipe.debug`) on a
disposable emulator when investigating network traffic or private app data.
In Android Studio, open **View > Tool Windows > App Inspection**, then select
that emulator and the debug app process.

Project import needs a Studio version that supports the AGP version pinned in
the version catalog. Studio Quail 1 (2026.1.1 Patch 2) rejects this project's
AGP 9.4.1 during sync. For an older IDE, build with `./gradlew :app:assembleDebug`
and use [File > Profile or Debug APK](https://developer.android.com/studio/debug/apk-debugger)
to open `app/build/outputs/apk/debug/app-debug.apk` as a separate APK project.
This avoids Gradle project import; verify inspector attachment separately.

- [Network Inspector](https://developer.android.com/studio/debug/network-profiler)
  displays supported HTTP requests, headers, bodies and timing. Trigger a search
  or open a stream to generate extractor traffic. Android Studio documents
  OkHttp and HttpsURLConnection support; verify capture with the installed Studio
  version and this app's OkHttp version. Native sockets, FCast protocol traffic,
  WebView traffic and other network stacks are not covered by that promise.
- [Database Inspector](https://developer.android.com/studio/inspect/database)
  opens the app's Room/SQLite databases on API 26 and newer. Prefer read-only
  queries; use disposable data when testing mutations.
- [Layout Inspector](https://developer.android.com/studio/debug/layout-inspector)
  inspects the running view hierarchy. Device Explorer or
  `adb -s <serial> shell run-as org.seventyfiveohmantenna.pvcpipe.debug ls`
  can inspect the debug app's private files, including shared-preference XML.
  Do not edit preferences behind the running app's caches.

Stetho's Chrome DevTools bridge and `dumpapp` commands have been removed.
These Studio and adb workflows replace individual inspection tasks; there is
no embedded HTTP recorder or on-device inspection UI. Captured headers, bodies
and private files can contain cookies or account data; use disposable accounts
and sanitize evidence before sharing it. LeakCanary and the debug preferences
for heap dumping and disposed Rx exceptions remain available.

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

### Uninstrumented subscriptions release smoke

Use an owned disposable emulator and a fresh `.smoke` installation for this
destructive restore journey. Confirm the selected serial is an emulator and the
package is absent before installation; a separate ID alone does not make an
existing installation disposable. Build through the existing release variant:

```sh
./gradlew --no-daemon -DpackageSuffix=.smoke :app:assembleRelease
python3 tools/subscriptions_release_fixture.py prepare --output /tmp/pvcpipe-release-seed
```

The fixture command refuses an existing output directory. It creates a schema-9
backup containing eight controlled subscriptions, JSON preferences selecting only
the Subscriptions tab, and an expected-record snapshot. It only accesses host
files. Check the APK's package, non-debuggable manifest and signing certificate,
then install it with Android CLI on the explicitly selected emulator. Preserve a
copy of that APK and its R8 outputs before a later build overwrites them.

Copy the seed ZIP to public emulator storage and use Settings → Backup and
restore → Import database → Restore with settings. This replaces the smoke
installation's database/settings and restarts the app. Export a baseline through
the normal Export database action and system document picker, then pull that
public ZIP to the host:

```sh
python3 tools/subscriptions_release_fixture.py check-export \
  --archive /tmp/baseline-export.zip --expected /tmp/pvcpipe-release-seed/expected.json \
  --report /tmp/baseline-check.json
```

Inspect the real list/grid, large-font headings and TalkBack's Headings navigation.
Select the channel layout in Appearance → List view mode; the nearby channel-group
button only changes group presentation. Return normally from Settings and verify
that the channel layout changes without restarting the app. Check that the group
button announces **Show channel groups as grid** when groups use a list and **Show
channel groups as list** when they use a grid. Toggle it both ways and verify that
channel rows keep their selected layout. Unsubscribe from the
fixture's only ID-99 channel through its long-press dialog, force-stop the smoke
app, and use a normal launcher start **before** another export. Check that export
with the same command plus `--removed-service 99`. The checker compares every surviving UID and
stored subscription field, checks other library tables remain empty, and runs
SQLite `quick_check`, foreign-key and schema-metadata checks. It does not inspect
private app files or deserialize the legacy Java settings entry.

This journey does not attach an application test runner. Android CLI layout
inspection uses its own external instrumentation package; stop that inspector
before TalkBack gestures. Visual TalkBack focus observations are separate from
native heading assertions in the debug suite. Restore the emulator's original
display/accessibility/network settings, and never use uninstall or data clearing
on an existing user installation. See the
[dated release result](documentation-alignment.md#uninstrumented-release-subscriptions--2026-10-29)
for tested artifacts, environment failures and limits, and the
[layout-settings follow-up](documentation-alignment.md#subscriptions-layout-settings-return--2026-10-30)
for the return-from-Settings correction.

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

- **Build-logic tests.** `./gradlew :buildSrc:test` checks version component
  rollovers and rejects codes outside Android's supported range.
- **App unit tests.** `./gradlew :app:testDebugUnitTest`, with
  `--tests '<pattern>'` for a subset. They run on the JVM. `BackupValidationTest`
  and `RestoreStartupTest` use Robolectric with API 26 and native host SQLite
  to exercise Room validation and pre-provider recovery without a device.
  Their first run may download the Robolectric Android runtime in addition to
  Gradle's test dependencies. `RestoreTransactionTest` launches child JVMs that
  halt at filesystem checkpoints and recovers their files in a fresh JVM; its
  database validator and disk preferences are controlled substitutes. These
  tests do not simulate device power loss or run Android instrumentation.
  `BookmarkSaveManagerTest` exercises combined bookmark transactions and the
  shared write queue with real Room/native host SQLite. `BookmarkSaveOwnershipTest`
  checks production fragment methods and draft ownership using controlled
  adapters, rendering and persistence; it does not run a real fragment lifecycle.
  `StreamOrphanRetentionTest` uses real Room/native host SQLite to check thumbnail
  retention, reference release, feed clear/pruning and transaction rollback.
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

### Disposable restore validation

[`RestoreEmulatorTest`](../app/src/androidTest/java/org/schabi/newpipe/settings/RestoreEmulatorTest.kt)
and [`restore_emulator_validation.py`](../tools/restore_emulator_validation.py)
replace the debug app's **default database and preferences** on a new disposable
emulator. Do not use an existing user installation. Ordinary test discovery skips
the phase driver unless its explicit host arguments are present.

Use Android CLI to inspect profiles and create/start a new AVD. Its presets select
their own system image. To select API 26 explicitly, the SDK's `avdmanager` can
create a uniquely named AVD from `system-images;android-26;default;x86_64`; start
that AVD with `android emulator start <name> --cold --headless`. Verify the serial,
AVD name and boot completion before installing anything. Every device command
must name that emulator explicitly.

Save both test APKs: the opt-in fixture build has its own test application ID and
runner, but writes to the same Gradle output path as the normal test APK. The
fixture runner substitutes a bare Application for controlled interruption phases;
normal verification uses the debug App's inherited production startup recovery.
Production application IDs and the default instrumentation runner are unchanged.

```sh
# Set these to the newly created disposable AVD and its reported emulator serial.
restore_avd=pvcpipe_restore_api26_demo
restore_serial=emulator-5554
adb -s "$restore_serial" emu avd name
adb -s "$restore_serial" shell getprop sys.boot_completed
restore_artifacts=$(mktemp -d -t pvcpipe-restore-apks.XXXXXX)

./gradlew --no-daemon --no-continue :app:assembleDebug :app:assembleDebugAndroidTest
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$restore_artifacts/normal.apk"
./gradlew --no-daemon --no-continue -Ppvcpipe.restoreFixture=true :app:assembleDebugAndroidTest
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$restore_artifacts/fixture.apk"

android run --device "$restore_serial" --apks app/build/outputs/apk/debug/app-debug.apk \
  --activity org.schabi.newpipe.MainActivity --install-options=-g
android install --device "$restore_serial" --apks "$restore_artifacts/normal.apk"
android install --device "$restore_serial" --apks "$restore_artifacts/fixture.apk"
python3 tools/restore_emulator_validation.py \
  --serial "$restore_serial" --avd-name "$restore_avd" --allow-disposable \
  --test-apk "$restore_artifacts/normal.apk" --fixture-apk "$restore_artifacts/fixture.apk" \
  --output "$restore_artifacts/results"
```

The host checks the exact AVD name, emulator properties and all three installed
APK hashes. A private nonce marker and runner checks guard fixture writes.
Nonterminal phases publish evidence and remain alive for host SIGKILL, or for the
production ProcessPhoenix restart outside target instrumentation, driven through
the real settings dialog and system document picker using Android CLI. Terminal verification runs in a fresh normal
app process. Interrupted activation/rollback use production recovery with a
test-only filesystem checkpoint; they do not pause `App.attachBaseContext` itself.
The driver records PID changes, byte hashes, phase results and a final summary.
It does not use Gradle-managed device tasks, uninstall packages or invoke
`pm clear`. These are emulator process-death checks, not power-loss tests.

### Disposable bookmark validation

[`BookmarkEmulatorTest`](../app/src/androidTest/java/org/schabi/newpipe/local/bookmark/BookmarkEmulatorTest.kt)
uses the real `MainActivity`, bookmark fragment, touch gestures, delete dialogs
and Android SQLite. Test gates hold the production playlist write queue; SQLite
triggers inject failures. The fixture temporarily substitutes an in-memory Room
database, three preference keys and image-quality state, then restores them.
Restoration runs during completed tests' teardown; a process crash can bypass it.
Names and thumbnail updates use synthetic metadata and local PNG files.
The thumbnail-cleanup case pins an image, removes its stream from the contents,
runs feed cleanup, and verifies the image remains. It then verifies collection
after unsetting the cover and after deleting its last playlist through the UI.

Use a disposable emulator, created as described above. Verify its AVD name and
emulator property **before installing** anything. For an update, verify signer
compatibility first, following [the device-safety procedure](local-playlist-mutations.md).
No Gradle-managed device task is needed.

```sh
(
set -e
bookmark_avd=pvcpipe_bookmarks_api26_demo
bookmark_serial=emulator-5554
android emulator start "$bookmark_avd" --cold --headless
test "$(adb -s "$bookmark_serial" emu avd name | head -n 1 | tr -d '\r')" = "$bookmark_avd"
test "$(adb -s "$bookmark_serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
./gradlew --no-daemon --no-continue :app:assembleDebug :app:assembleDebugAndroidTest
android install --device "$bookmark_serial" --apks app/build/outputs/apk/debug/app-debug.apk \
  --install-options=-r --use-delta-install=false
adb -s "$bookmark_serial" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
bookmark_results=$(mktemp -d -t pvcpipe-bookmarks.XXXXXX)
python3 tools/bookmark_emulator_validation.py \
  --serial "$bookmark_serial" --avd-name "$bookmark_avd" --allow-disposable \
  --output "$bookmark_results/results"
)
```

The [host driver](../tools/bookmark_emulator_validation.py) refuses physical
serials, checks the exact AVD, emulator properties and installed APK hashes, then
creates a nonce marker required by the fixture. Ordinary discovery skips these
tests without the explicit host arguments. The driver records all eight cases,
JUnit output and before/after hashes of the default database family. It does not
install, uninstall or clear app data.

With `--visual`, the metadata case pauses for up to 120 seconds. Inspect it with
`android layout --device <serial> --output <json-path>` and
`android screen capture --device <serial> --output <png-path>`;
view the PNG before continuing. Then create the printed `visual-release` marker
with `adb -s <serial> shell run-as org.seventyfiveohmantenna.pvcpipe.debug touch <printed-path>`.
If the CLI layout server cannot respond during instrumentation, use the visually
inspected PNG as the fallback and record the failed layout request separately.
These checks cover actual fragment transitions on the selected emulator, using
controlled write timing and synthetic data. They do not test power loss,
process-death draft recovery or live service metadata updates.

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

Fragments, activities and dialogs save their state by hand in Android
`Bundle`s. The app once used an annotation processor for this. The keys it
generated are still read, so state saved by an older build survives an
update. `DownloadDialogSavedStateTest` and `SearchFragmentSavedStateTest`
exercise restoration through those older keys.

`org.schabi.newpipe.util.StateSaver` is something else. It keeps large list
state out of the `Bundle`, in memory across a configuration change and on
disk otherwise.
