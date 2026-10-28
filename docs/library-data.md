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
`StreamDAO.deleteOrphans` currently checks history, playlist contents and feed
references but not playlist-thumbnail references. Cleanup can therefore remove
an otherwise unreferenced permanent thumbnail's stream row; this is a suspected
retention defect, not a guarantee that the chosen image survives cleanup.

The Room instrumentation tests live in
`app/src/androidTest/java/org/schabi/newpipe/local/playlist/`. Follow
[the device-safety procedure](local-playlist-mutations.md) before running them.

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

**Restore.** A restore never touches the open database:

1. It copies the archive to a private file and takes the database, and the
   settings if the user chose them, from that copy. It verifies each selected
   entry's size and CRC, then opens and migrates the private database through
   the app's Room configuration before changing any preferences.
2. It commits the chosen settings, or a copy of the current preferences for
   database-only restore. Both choices update the remembered backup document URI.
3. It publishes the new database as a pending restore and restarts the app.
4. At the next start,
   [`PendingDatabaseRestore`](../app/src/main/java/org/schabi/newpipe/settings/export/PendingDatabaseRestore.java)
   moves the pending database into place before any database connection is
   opened.

A completed restore has staged the database and requested a restart; it has
not replaced the database in the running process. If restart fails, the
restore stays pending. While a restore is pending, further restores and
exports are refused. Accepted work survives screen closure, not application
process termination.

Replacing the preferences and publishing the database are two steps, not one
transaction. If the second fails, the app puts the old preferences back, and
tries again at the next backup operation if that failed too. A process that
dies between the steps can leave new settings with the old database. Nothing
promises that a backup will open after future database migrations.

[`BackupDatabaseValidator`](../app/src/main/java/org/schabi/newpipe/settings/export/BackupDatabaseValidator.kt)
checks the SQLite header, a positive supported schema version, SQLite integrity,
Room migrations/schema and foreign-key consistency. Future or otherwise
unsupported migration paths fail before publication. The private copy's Room
identity metadata is rebuilt so a matching hash cannot bypass schema validation.
The candidate is closed with migrations already applied. Snapshot and migration
sidecar cleanup finishes before preferences can commit; the resulting validated
database file is then either published or removed when the attempt closes.
The source archive, live database and its sidecars are not migration targets.

These checks establish structural compatibility with the current build, not the
authenticity or meaning of every stored value. Ignored archive entries and
settings omitted by database-only restore are not decoded or CRC-checked. The
existing process-death gap between preference commit and database publication
still applies.

## Tests

Unit tests, run by `make ci`:
[`FeedRefreshTest`](../app/src/test/java/org/schabi/newpipe/local/feed/service/FeedRefreshTest.kt),
[`FeedErrorContextTest`](../app/src/test/java/org/schabi/newpipe/local/feed/service/FeedErrorContextTest.kt),
[`SubscriptionTransferTest`](../app/src/test/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransferTest.kt),
[`BackupRestoreTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupRestoreTest.kt),
[`BackupRestoreFailureTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupRestoreFailureTest.kt),
[`BackupValidationTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupValidationTest.kt) and
[`PendingDatabaseRestoreTest`](../app/src/test/java/org/schabi/newpipe/settings/PendingDatabaseRestoreTest.java).

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

These tests exercise controlled workflow failures and local persistence.
The feed and subscription Room tests supply extraction results rather than
contacting services; they do not establish live extractor/network behavior
or survival after Android kills the process.
