# Local playlist testing and device safety

`LocalPlaylistManager` owns playlist content and automatic-thumbnail mutations;
`LocalPlaylistFragment` owns the editable UI draft. The source and
`app/src/androidTest/java/org/schabi/newpipe/local/playlist/` are the reference
for current mutation and transaction behavior. The device-test restrictions
below are based on an observed Gradle run, not on the manager's implementation.

## Device instrumentation without replacing user data

The five `org.schabi.newpipe.local.playlist` instrumentation suites use
in-memory Room databases (`LocalPlaylistCorrectionTest`,
`LocalPlaylistPendingEditsTest`, `LocalPlaylistContentsTest`, and
`LocalPlaylistMutationFailureTest` build them directly;
`LocalPlaylistManagerTest` installs an in-memory database through
`TestDatabase`). Their `@After` methods close the databases. Check this
isolation and any file, preference, or external-storage effects again before
adding or running other suites; test isolation alone does not make the **test
task's installation and cleanup** safe.

The app configures `androidx.test.runner.AndroidJUnitRunner` in
`app/build.gradle.kts`. On the Pixel 6a in October 2026, this repository's
`:app:connectedDebugAndroidTest` installed the debug app and instrumentation
APK, ran the selected tests, then issued `cmd package uninstall` for **both**
`org.seventyfiveohmantenna.pvcpipe.debug` and
`org.seventyfiveohmantenna.pvcpipe.debug.test`. The captured log for
`LocalPlaylistPendingEditsTest.pendingReorderIsFrozenBeforeDuplicateRemovalAndThumbnailSelection`
under `app/build/outputs/androidTest-results/connected/debug/Pixel 6a - 17/`
records `cmd package 'uninstall' 'org.seventyfiveohmantenna.pvcpipe.debug'`
at 10:04:38.526 and the corresponding `.debug.test` uninstall at
10:04:38.773. Android then logged that
`org.seventyfiveohmantenna.pvcpipe.debug` (UID 10324) was fully removed
for user 0 at 10:04:38.802. This is an observation
of this configured task/run, not a claim about every Android Gradle
instrumentation task. An `adb install -r` had preserved the installed debug
app in the earlier [`device smoke`](pvcpipe-device-smoke.md); it did not make
this Gradle task safe.

After that run, `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` were
reinstalled using `adb -s <serial> install -r`. Package and instrumentation
listings then showed both installed. **Reinstallation cannot restore private
data deleted by uninstall.** No pre-run copy of the debug app's private data
was captured, so its pre-run contents and any loss cannot be verified or
recovered from these APKs. The repository's ZIP files under
`app/src/test/resources/settings/` are test fixtures, not a user backup.
An offline search of the repository and the workstation's conventional
Downloads location found no plausible user archive; a read-only survey of
the device's `Download` and `Documents` folders found no PVCPipe-named export
or plausible PVCPipe ZIP. A backup selected through another storage provider
or kept elsewhere remains possible, not established. If the user identifies
one, inspect a copy **offline** for `newpipe.db`, `preferences.json`, and/or
`newpipe.settings`, archive integrity, and database/schema compatibility
before considering any separately authorized import; an APK is not a backup.

For future testing on an existing installation: identify the actual ADB
serial, installed package and signing certificate; inspect the selected
Gradle task's install/cleanup behavior and all selected tests' data effects.
Do **not** use this observed uninstalling task on a user installation, or
run uninstall/clear-data commands. If an update is needed, verify same-signer
compatibility first and use `adb -s <serial> install -r` (not an uninstall).
With a safely installed app and test APK, run only the isolated playlist
package directly:

```sh
adb -s '<serial>' shell am instrument -w \
  -e package org.schabi.newpipe.local.playlist \
  org.seventyfiveohmantenna.pvcpipe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Check installed packages and test results afterward. Direct instrumentation
passed all 26 playlist tests on this Pixel without the Gradle task's cleanup.
If package signing, test isolation, or cleanup safety is uncertain, use a
separate disposable device/profile instead of the user's installation.
