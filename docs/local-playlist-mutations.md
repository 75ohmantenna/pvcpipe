# Local playlist contents

`LocalPlaylistManager` owns content mutations and their automatic-thumbnail
policy. Callers append streams, replace ordered stream IDs, or remove duplicates,
watched streams, or watched and partially watched streams. The fragment retains
confirmation dialogs, loading state, presentation, and debounce handling for
manual edits. The append dialog submits one manager operation.

Appends retain duplicates and assign indexes after the current maximum. Replacing
contents preserves the supplied order and duplicates. Duplicate removal keeps the
first occurrence. Watched removal requires both history and saved playback state;
full-only removal additionally uses the existing finished-playback calculation.
Automatic thumbnails remain when their stream survives, otherwise they use the
first surviving stream or the default for empty contents. Permanent thumbnails
remain unchanged by content removal.

Each cached writer mutation starts on first subscription and runs once. Its
transaction runs on the shared application writer and caches the outcome, so
disposing presentation does not stop queued content or thumbnail work and later
observers receive its stored result.
Append stream fields and content ID lists are copied when the operation is
created. Unlike these cached writer operations, playlist creation retains its
existing IO scheduling and does not use the shared writer. Renames, explicit
thumbnails, bookmark ordering, and deletions use the writer and read current
metadata in their transactions; bookmark ordering only changes the display
index, preserving newer names and thumbnails.

Content selection, history/state classification, index replacement, thumbnail
selection, and the returned ordered result use synchronous Room queries inside
one transaction. No blocking Rx query is subscribed from that transaction.
Automatic thumbnails, including the empty default, change atomically with joins;
permanent thumbnails remain unchanged, including permanent defaults. Missing
playlists fail. Every replacement validates its joined stream count before
commit, so unknown IDs fail inside the transaction body and roll back reliably.
This also prevents cleanup filtering from hiding dangling pending joins.

Bulk cleanup accepts an optional copied pending list of stream IDs. Null selects
stored contents; a non-null empty list clears them before applying cleanup. The
fragment submits dirty pending edits together with cleanup, blocks drag, delete,
and separate saves during rewriting, and acknowledges only the captured saver
revision. Success, failure, and view destruction clear presentation rewriting
state. Mutation failures use a snackbar and preserve the displayed draft and
database observation, so a later lifecycle save cannot replace rolled-back
contents with an error-cleared empty list. Failed work leaves dirty edits
available for retry. View destruction only detaches observers from already
accepted mutations. Read-error reset marks contents unloaded, clears rewrite
ownership, and detaches old callbacks before retry observers are attached. The
cleared adapter cannot be saved as an empty loaded playlist.

The database is local-substitutable. Tests exercise the manager interface with
in-memory Room, actual history and playback state, joins, and playlist metadata.
No additional adapter is needed. Real Room tests use controlled scheduling to
cover disposed accepted appends,
consecutive index allocation, and the execution order of two disposed accepted
replacements. These replace the overlapping DAO-mock mutation tests. Fragment
regressions cover failed draft saves and read-error reset/reload races. Run
`make ci` for ordinary checks and APK builds; run
playlist database tests on an Android device for transaction and persistence
coverage.

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
