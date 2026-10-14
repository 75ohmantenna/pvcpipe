# Local playlist tests and device safety

`LocalPlaylistManager` changes playlist contents and automatic thumbnails.
`LocalPlaylistFragment` holds the draft the user is editing. The code and the
tests in `app/src/androidTest/java/org/schabi/newpipe/local/playlist/` say
how those changes and their transactions work.

Current playlist behavior is described in
[library-data.md](library-data.md#local-playlists).
The incident below records what one Gradle test run did to an installed app.
The final section gives the procedure for testing an existing installation.

## What the playlist tests touch

The six instrumentation suites in `org.schabi.newpipe.local.playlist` use
in-memory Room databases:

- `LocalPlaylistContentsTest`, `LocalPlaylistCorrectionTest`,
  `LocalPlaylistMutationFailureTest`, `LocalPlaylistPendingEditsTest` and
  `LocalPlaylistThumbnailQueriesTest` build one directly.
- `LocalPlaylistManagerTest` installs one in place of the app's database
  through `TestDatabase`.

Each suite closes its database in its `@After` method. Before adding or
running any other suite, check its isolation again, and what it does to
files, preferences and external storage. Isolated tests do not make the test
task safe. The task also installs and cleans up.

## What happened on the Pixel 6a, October 2026

The app's instrumentation runner is `androidx.test.runner.AndroidJUnitRunner`,
set in `app/build.gradle.kts`. `:app:connectedDebugAndroidTest` installed the
debug app and the instrumentation APK, ran the selected tests, and then ran
`cmd package uninstall` for both
`org.seventyfiveohmantenna.pvcpipe.debug` and
`org.seventyfiveohmantenna.pvcpipe.debug.test`.

The log captured for
`LocalPlaylistPendingEditsTest.pendingReorderIsFrozenBeforeDuplicateRemovalAndThumbnailSelection`
under `app/build/outputs/androidTest-results/connected/debug/Pixel 6a - 17/`
shows it:

- 10:04:38.526, `cmd package 'uninstall' 'org.seventyfiveohmantenna.pvcpipe.debug'`
- 10:04:38.773, the same for `.debug.test`
- 10:04:38.802, Android logs that
  `org.seventyfiveohmantenna.pvcpipe.debug` (UID 10324) was fully removed for
  user 0

This is what this task did in this run. It says nothing certain about other
Android Gradle test tasks. An `adb install -r` had kept the installed debug
app in the earlier [device smoke](pvcpipe-device-smoke.md), but that did not
make the Gradle task safe.

## What could and could not be put back

After the run, `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` were
installed again with `adb -s <serial> install -r`, and the package and
instrumentation listings showed both.

Reinstalling an APK does not bring back private data that an uninstall
deleted. Nobody had copied the debug app's data before the run, so what was
in it, and whether anything was lost, cannot be checked or recovered.

- The ZIP files under `app/src/test/resources/settings/` are test fixtures.
  They are not a backup of anyone's data.
- A search of the repository and the workstation's Downloads folder found no
  archive that could be a user backup.
- A read-only look at the phone's `Download` and `Documents` folders found no
  export named after PVCPipe and no ZIP that could be one.
- A backup saved through another storage provider, or kept somewhere else,
  is still possible. None was found.

If the user points to a backup, work on a copy and stay offline. Look for
`newpipe.db`, `preferences.json` and `newpipe.settings`, check that the
archive is intact, and check that the database's schema version is one the
app can open. Import it only if the user then asks for that.

## Testing on an existing installation

1. Find the actual ADB serial, the installed package and its signing
   certificate.
2. Read what the chosen Gradle task installs and cleans up, and what every
   selected test does to data.
3. Do not run the uninstalling task above on a user's installation, and do
   not run any command that uninstalls a package or clears its data.
4. If the app needs updating, confirm that the new APK has the same signer,
   then use `adb -s <serial> install -r`.
5. With the app and the test APK installed that way, run only the isolated
   playlist package, directly:

```sh
adb -s '<serial>' shell am instrument -w \
  -e package org.schabi.newpipe.local.playlist \
  org.seventyfiveohmantenna.pvcpipe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Check the installed packages and the test results afterwards. Run this way
in October 2026, all 26 playlist tests of that time passed on the Pixel and
nothing was uninstalled. The test package has since changed; inspect its
current sources before selecting it for another run.

If the signing, the isolation of the tests or the cleanup is in doubt, use a
disposable device or profile and leave the user's installation alone.
