# Library data

PVCPipe stores subscriptions, feeds, playlists, history and settings on the
device. Feed refreshes update the local library; subscription transfers and
backup/restore move data to or from user-selected sources. This guide covers
their behavior and failure boundaries. The implementation defines batch
sizes, delays and file formats.

Local storage is not an exclusive-device privacy guarantee. `AndroidManifest.xml`
sets `android:allowBackup="true"` and declares no app-specific backup exclusions.
Android's backup/transfer behavior depends on the platform, user settings and
transport. The ZIP workflow below is the app's own export/restore mechanism.

## Local playlists

`LocalPlaylistManager` stores ordered stream references, including repeated
entries. `LocalPlaylistFragment` keeps the draft currently being edited.
Appending, replacing and filtering contents each run in a Room transaction,
with automatic-thumbnail updates in the same transaction.

- Append operations copy the input list and its stream entities; replacement
  and removal operations snapshot their stream ids when requested. Later UI
  edits cannot change those pending operations.
- These mutations use a shared serial write scheduler and cache their result,
  so multiple observers do not repeat the write. Playlist creation instead
  checks for an empty input immediately; for a nonempty input it returns a
  deferred, uncached I/O operation that reads the live list on subscription.
- Duplicate removal keeps the first occurrence. Watched-item removal uses
  both history and saved progress; finished-only removal also checks the
  saved completion state. A pending draft is applied in the same transaction
  before filtering.
- An automatic thumbnail keeps its stream while that stream remains in the
  playlist. Otherwise it uses the first remaining stream, or the default
  image when empty. Permanent thumbnails are not adjusted by content edits.
- The fragment tracks draft revisions so a completed save does not mark newer
  edits as saved or replace them with an older result.

A permanent thumbnail stores a stream ID, not an independent image copy.
`StreamDAO.deleteOrphans` preserves streams referenced by history, playlist
contents, feeds or a stored playlist thumbnail ID. The thumbnail's permanent flag
does not affect that reference check. Removing a stream from playlist contents
therefore does not make its permanent cover collectible.

An old cover becomes eligible for cleanup only after its last reference is gone,
for example after unsetting it to an automatic/default thumbnail or deleting its
last playlist. History orphan cleanup, feed clearing and age-based pruning use
this query; changing the thumbnail does not itself delete stream rows. This
prevents future cleanup loss but does not reconstruct metadata deleted before
the correction.

The Room instrumentation tests live in
`app/src/androidTest/java/org/schabi/newpipe/local/playlist/`. Follow
[the device-safety procedure](local-playlist-mutations.md) before running them.

## Bookmarks

`BookmarkFragment` edits the combined order of local playlists and remote
playlist bookmarks. [`BookmarkSaveManager`](../app/src/main/java/org/schabi/newpipe/local/bookmark/BookmarkSaveManager.kt)
copies that order's IDs and item types, together with pending deletions, when a
save is requested. Local and remote IDs belong to separate tables; equal numeric
IDs do not identify the same bookmark.

- The first subscription admits a save onto the shared local-playlist write
  queue. Its cached operation runs once and continues if observers detach.
  Later accepted saves run after earlier ones, including across manager instances.
- Each save applies both tables' deletions and order updates in one Room
  transaction. Either table's write failure rolls back the transaction. Only
  display indices are updated: stale adapter metadata cannot overwrite a newer
  name or thumbnail, and a missing row is not recreated.
- Completion acknowledges only the captured draft revision and deletion batch,
  for the same saver instance. An older completion cannot mark a newer edit saved.
  Adapter display indices are not changed to claim persistence before success.
- Write failures leave the visible draft and pending deletions available for
  retry. Each later save request submits the current draft as a new operation.
  The loaded fragment's reload path retries saving instead of discarding the
  draft. Database emissions do not replace a dirty draft. Errors use the existing
  snackbar or detached error-reporting path.
- Accepted writes and their completion callbacks outlive view subscriptions.
  A dirty draft is retained when the same fragment's view is destroyed and
  recreated. This is process-local state: a failed draft is not stored for
  recovery after full fragment destruction or process death. Other remote
  playlist operations retain their own scheduling and metadata-update behavior.

The focused JVM tests under
`app/src/test/java/org/schabi/newpipe/local/bookmark/` distinguish actual Room
transactions with native host SQLite from fragment-method tests with controlled
adapters, rendering and persistence. They do not establish device lifecycle or
storage durability guarantees.

The separate [bookmark emulator suite](building.md#disposable-bookmark-validation)
uses real views, fragment transitions and Android SQLite, with controlled queue
gates and SQL failures. Its synthetic metadata uses local images. Dated results
in [the alignment record](documentation-alignment.md) identify the tested revision
and emulator; they do not establish physical-device or process-death durability.

## Feed refresh

[`FeedRefresh`](../app/src/main/java/org/schabi/newpipe/local/feed/service/FeedRefresh.kt)
is one refresh. It selects the subscriptions that are due, loads each one,
stores the results in batches and then trims old entries from the feed.

- A channel that fails to load is reported next to the ones that worked. A
  failure of the first query, of a database write or of the trimming fails
  the whole run.
- The first observer of `result` starts the run. Later observers get the same
  outcome replayed. An observer that detaches does not stop the work.
- `cancel` cooperatively stops admitting channel loads at the pipeline's
  cancellation checks. Loads already under way finish, and collected results
  are still stored. Cancellation does not interrupt these admitted loads;
  an Odysee resolve already under way can also perform its one timeout retry,
  so completion and the foreground refresh notification may wait through
  that additional request timeout.
- All of this runs inside the app's process. If Android kills the process,
  the refresh is gone.

`FeedLoadManager` builds the refresh and does its loading and storing. It
leaves out subscriptions of services the app does not have, and it uses a
service's feed extractor in place of loading the channel when the dedicated
feed setting is on and the service has one
(see [services.md](services.md#the-services)).
That setting defaults to off. The channel path loads each tab enabled by the
feed tab preferences, independently of channel-screen tab visibility. It uses
the initial page, or one continuation page when the initial page is empty;
it does not exhaust every tab's pagination (`FeedLoadManager.AndroidOperations.fetch`).

Feed error reports retain each failed subscription's service ID and URL.
The report names a service only when every error belongs to that same
service; mixed or unattributed failures have no shared service. The request
description includes up to ten distinct failed requests, separated by
semicolons on one display line and capped at 4,000 characters. The underlying
exception causes remain available in the traces.

## Subscriptions tab

Subscribed channels are grouped by their stored service ID in ascending ID
order. For supported services, this matches the service picker: YouTube,
SoundCloud, BitChute, Rumble, Odysee, then Kick. Within each section, channels
retain the database's case-insensitive alphabetical name order.

Only services with subscriptions get a heading. Headings span the full width
in both list and grid layouts; unsubscribing from a section's last channel
removes its heading.

These are presentation sections, not new database records or custom feed
groups. Custom groups can still contain channels from multiple services.
Subscriptions from retired services remain grouped by their original IDs,
with `<unknown>` headings; different retired IDs remain separate even though
their labels match. The empty list retains its import/export hint.

## Subscription import and export

[`SubscriptionTransfer`](../app/src/main/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransfer.kt)
does both. `SubscriptionImportWorker` and `SubscriptionExportWorker` run it
in the background and show its progress in a notification.

- An import reads its source: a channel address, a file exported from a
  service, or an earlier export of this app. It counts and skips channels
  from unsupported services. For every remaining entry it loads the channel
  and its first tab before storing any, then stores the results in source
  order, in batches.
- Each batch is a database transaction. A batch that fails rolls back that
  batch, not the batches stored before it. Import updates existing
  subscriptions as well as inserting new ones; a successful count is the
  number of supported source entries processed, not necessarily new rows.
- An export takes a snapshot of the subscriptions and writes it to the
  document the user chose.
- A source that cannot be opened, a channel that cannot be loaded and a write
  that fails all come back as `Outcome.Failure`. None of them is reported as
  an import or export of nothing. A cancelled coroutine stays cancelled and
  is not turned into a failure. An empty source, or one containing only
  unsupported services, can succeed with a count of zero.

## Backup and restore

[`BackupRestore`](../app/src/main/java/org/schabi/newpipe/settings/BackupRestore.java)
is the workflow.
[`BackupOperations`](../app/src/main/java/org/schabi/newpipe/settings/BackupOperations.java)
runs it outside the settings screen, so work that was accepted goes on when
the screen closes.
[`ImportExportManager`](../app/src/main/java/org/schabi/newpipe/settings/export/ImportExportManager.kt)
reads and writes the archive.

**Export.** PVCPipe checkpoints the local library database and writes a ZIP
with three entries: `newpipe.db`, `preferences.json` and `newpipe.settings`
(the legacy serialized form of the same preferences). It excludes downloaded
media, the separate finished-downloads database and unfinished-download state.

**Inspection.** Before a restore the app copies the document to a private temporary
directory, opens its ZIP central directory and discovers which form of settings
it holds. Duplicate critical entries and directories in place of the database
or settings entries are rejected. JSON settings are preferred when both formats
are present. This copy is deleted after inspection: the result is advisory, does
not validate the database or entry payloads, and is not a retained snapshot.
When settings are included in the restore, a change in the preferred settings
format before preparation stops the restore and requires a new inspection.
Database-only restores do not recheck the settings format.

**Restore choices.** The overwrite confirmation still comes first. If the
backup contains settings, **Restore backup** then explains the two actions:

- **Restore without settings** restores the database while keeping current
  settings.
- **Restore with settings** restores the database and replaces current settings
  with the backup's settings.
- **Cancel**, Back and outside dismissal close the dialog without starting a
  restore. Closing the settings view before choosing also does not start one.

Legacy serialized settings retain their trust/security warning in this dialog.
A backup without settings proceeds with database-only restore after the
overwrite confirmation; it does not show a settings-choice dialog.

**Imported settings are not trusted.** Before they replace the live ones,
`ImportedPreferenceTypes.conformImport` checks their types. A preference the
app declares as numeric must hold that kind of number, or one that converts
to it without changing its value. Other keys with a live value must match
that value's type. A value that fails is replaced by the live value, or
dropped so that the default applies. A key with neither a numeric declaration
nor a live value is left unchanged: this is type normalization, not complete
validation of every imported setting. Two tables in `BackupRestore` feed
this check:

- The numeric preferences. Add a key there when adding a `getInt`,
  `getLong` or `getFloat` read.
- The preferences whose type a settings migration changed, with the version
  of that change. A file from before the change keeps the old type, because
  the migration runs at the next start and has to read it. Add a key there
  when a migration changes a type.

**Restore.** Preparation leaves the running database and preferences unchanged:

1. It copies the archive to a private file, extracts the selected entries, checks
   their sizes/CRCs, and opens/migrates the private database with the app's Room
   configuration. Settings are decoded and normalized only when selected.
2. It atomically publishes a private `newpipe.db.restore-transaction` directory
   containing the database and a typed settings intent, then requests restart.
   Both choices stage the remembered backup document URI. Database-only restore
   stages a patch; it keeps the preferences present at activation, including edits
   made after staging. Restore with settings stages a complete replacement.
3. In the main process's `App.attachBaseContext`, before content providers or
   `Application.onCreate` can open the database,
   [`PendingDatabaseRestore`](../app/src/main/java/org/schabi/newpipe/settings/export/PendingDatabaseRestore.kt)
   verifies and revalidates a fresh copy of the queued database. Phoenix and ACRA
   helper processes skip activation.
4. [`RestoreTransaction`](../app/src/main/java/org/schabi/newpipe/settings/export/RestoreTransaction.java)
   saves the previous preferences and database family (main file, journal, WAL
   and shared-memory sidecar) and publishes an activation record before changing
   live data. It atomically replaces the main database before removing old
   sidecars, commits preferences, then writes a completion record.

The records and snapshots use checksums, forced file writes and directory syncs.
An interrupted activation without a completion record restores the saved pair
before startup returns to consumers. Recovery can itself be retried after an
interruption. If a required undo snapshot is damaged or recovery cannot persist
its changes, startup fails rather than exposing an unresolved pair. A valid
completion record prevents replay over later database or preference edits,
including when transaction cleanup failed. Handled rejection/rollback/cleanup
errors are reported through the application's error-notification path after
normal initialization, unless error reporting is disabled. Notification visibility
also depends on Android notification permission/settings.

Rejected and rolled-back transactions are quarantined in private
`newpipe.db.restore-rejected-<UUID>` paths. They are retained for diagnosis rather
than automatically deleted. Successfully completed transactions are retired
before best-effort deletion; cleanup failure cannot authorize replay. Interrupted
pre-publication and installation temporaries may also remain on disk. These
files can require additional private storage; there is no cleanup UI. Validation
and snapshot copying run synchronously during attach, so launch time and storage
cost grow with the database size.

A successful restore request has staged work and requested restart; it has not
activated the replacement in the running process. Before publication, accepted
work survives screen closure only while its process remains alive. After
publication, the intent survives process termination. If restart fails, it stays
pending; further restores and exports are refused until startup handles it.
Legacy `newpipe.db.restore` files are converted to the same recovery protocol and
validated before replacement. They preserve the preferences present at startup;
old versions did not record enough information to undo an earlier settings
change made before publishing a legacy file.

[`BackupDatabaseValidator`](../app/src/main/java/org/schabi/newpipe/settings/export/BackupDatabaseValidator.kt)
checks the SQLite header, a positive supported schema version, SQLite integrity,
Room migrations/schema and foreign-key consistency. Future or otherwise
unsupported migration paths fail before new ZIP restores are published. The
private copy's Room identity metadata is rebuilt so a matching hash cannot bypass
schema validation. The candidate closes with migrations applied; the original
archive and live database are never migration targets. Startup uses a unique
validation directory so a killed migration's sidecars cannot contaminate a retry.

These checks establish structural compatibility with the current build, not the
authenticity or meaning of every stored value. Ignored archive entries and
settings omitted by database-only restore are not decoded or CRC-checked.
Process-interruption tests and host filesystem syncs do not establish survival
of arbitrary device power loss or storage failure. Future app migrations may
also change compatibility.

## Tests

Unit tests, run by `make ci`:
[`FeedRefreshTest`](../app/src/test/java/org/schabi/newpipe/local/feed/service/FeedRefreshTest.kt),
[`FeedErrorContextTest`](../app/src/test/java/org/schabi/newpipe/local/feed/service/FeedErrorContextTest.kt),
[`SubscriptionTransferTest`](../app/src/test/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransferTest.kt),
[`BackupRestoreTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupRestoreTest.kt),
[`BackupRestoreFailureTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupRestoreFailureTest.kt),
[`BackupValidationTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupValidationTest.kt),
[`PendingDatabaseRestoreTest`](../app/src/test/java/org/schabi/newpipe/settings/PendingDatabaseRestoreTest.java),
[`RestoreTransactionTest`](../app/src/test/java/org/schabi/newpipe/settings/export/RestoreTransactionTest.java)
and [`RestoreStartupTest`](../app/src/test/java/org/schabi/newpipe/settings/RestoreStartupTest.kt).

Instrumentation tests on a device, not in `make ci`:
[`FeedRefreshDatabaseTest`](../app/src/androidTest/java/org/schabi/newpipe/local/feed/service/FeedRefreshDatabaseTest.kt),
[`SubscriptionTransferDatabaseTest`](../app/src/androidTest/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransferDatabaseTest.kt),
[`BackupRestoreIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/settings/BackupRestoreIntegrationTest.java)
and
[`BackupRestoreDialogTest`](../app/src/androidTest/java/org/schabi/newpipe/settings/BackupRestoreDialogTest.java).
The dialog tests use the real settings fragment, isolated archive/database files
and preferences, and a counting restart callback rather than restarting the app.
The archive/dialog workflow fixtures inject a no-op database validator for their
text placeholders. `BackupValidationTest` separately exercises the production
validator, Room migrations and native host SQLite under Robolectric API 26,
with real files/preferences and controlled restart/report callbacks. Its version
2–9 fixtures use exported Room schemas; the version-1 fixture is constructed from
the inputs consumed by migration 1 → 2, since schema 1 is not exported.
[building.md](building.md) says where it is safe to run them.

`RestoreTransactionTest` uses actual host files, a controlled text-database
validator and typed disk preferences. Child JVMs halt without cleanup at selected
publication, activation, rollback and retirement checkpoints, then another JVM
recovers the same files. `RestoreStartupTest` separately exercises production
`App.attachBaseContext`, SQLite validation and Android preferences under
Robolectric API 26; it uses controlled process-role/ACRA hooks and suppresses main-process
`onCreate` initialization; its helper test calls the production early-return path. Its interrupted-commit case injects an `Error` in the
same JVM. Neither suite establishes Android process scheduling, physical storage
durability or hardware behavior.

[`RestoreEmulatorTest`](../app/src/androidTest/java/org/schabi/newpipe/settings/RestoreEmulatorTest.kt)
adds host-driven Android phases with real SQLite databases, preferences and
committed WAL-only data. The two restore choices use the production backup
workflow, real settings dialog/system document picker and ProcessPhoenix restart,
driven using Android CLI outside target instrumentation. Controlled filesystem checkpoints hold
activation and rollback until the host sends SIGKILL; a fresh normal debug App
then runs the inherited production startup recovery. A separate guarded fixture
runner prevents startup from consuming those journals before the selected fault
point. These tests replace the default database/preferences and require a new
disposable emulator. They are separate from `make ci`; see
[the build guide](building.md#disposable-restore-validation) for commands and
the [dated record](documentation-alignment.md#restore-emulator-validation--2026-10-28)
for observed results and limits.

These tests exercise controlled workflow failures and local persistence.
The feed and subscription Room tests supply extraction results rather than
contacting services; they do not establish live extractor/network behavior
or survival after Android kills the process.
