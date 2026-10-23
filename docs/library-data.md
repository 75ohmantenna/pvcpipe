# Library data

PVCPipe stores subscriptions, feeds, playlists, history and settings on the
device. Feed refreshes update the local library; subscription transfers and
backup/restore move data to or from user-selected sources. This guide covers
their behavior and failure boundaries. The implementation defines batch
sizes, delays and file formats.

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
  are still stored.
- All of this runs inside the app's process. If Android kills the process,
  the refresh is gone.

`FeedLoadManager` builds the refresh and does its loading and storing. It
leaves out subscriptions of services the app does not have, and it uses a
service's feed extractor in place of loading the channel when the dedicated
feed setting is on and the service has one
(see [services.md](services.md#the-services)).

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

**Inspection.** Before a restore the app looks at the chosen file and reports
whether it is a readable ZIP and which form of settings it holds. JSON
settings are preferred when both forms are present. Inspection is advisory:
it does not validate the database or snapshot the archive. When settings
are included in the restore, a change in the preferred settings format
before preparation stops the restore and requires a new inspection.
Database-only restores do not recheck the settings format.

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
   settings if the user chose them, from that copy.
2. It replaces the live preferences.
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

## Tests

Unit tests, run by `make ci`:
[`FeedRefreshTest`](../app/src/test/java/org/schabi/newpipe/local/feed/service/FeedRefreshTest.kt),
[`SubscriptionTransferTest`](../app/src/test/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransferTest.kt),
[`BackupRestoreTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupRestoreTest.kt),
[`BackupRestoreFailureTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupRestoreFailureTest.kt)
and
[`PendingDatabaseRestoreTest`](../app/src/test/java/org/schabi/newpipe/settings/PendingDatabaseRestoreTest.java).

Instrumentation tests against Room on a device, not in `make ci`:
[`FeedRefreshDatabaseTest`](../app/src/androidTest/java/org/schabi/newpipe/local/feed/service/FeedRefreshDatabaseTest.kt),
[`SubscriptionTransferDatabaseTest`](../app/src/androidTest/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransferDatabaseTest.kt)
and
[`BackupRestoreIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/settings/BackupRestoreIntegrationTest.java).
[building.md](building.md) says where it is safe to run them.

These tests exercise controlled workflow failures and local persistence.
The feed and subscription Room tests supply extraction results rather than
contacting services; they do not establish live extractor/network behavior
or survival after Android kills the process.
