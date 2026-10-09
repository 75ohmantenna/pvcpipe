# Library data workflows

The application owns three related workflows; implementation and tests, not this
guide, define their scheduling, batch sizes, and formats.

- **Feed refresh:** [`FeedRefresh`](../app/src/main/java/org/schabi/newpipe/local/feed/service/FeedRefresh.kt)
  selects subscriptions, extracts updates, stores results, and trims stale data.
  A failed channel extraction is reported alongside successful updates; a query,
  storage, or cleanup failure fails the run. Result observation starts the run
  once and replays its outcome; detaching a UI observer does not undo accepted
  work. Cancellation cooperatively stops new extractions, not requests already
  running; collected results can still be persisted. This is in-process work,
  not a process-death durability guarantee.
- **Subscription transfer:**
  [`SubscriptionTransfer`](../app/src/main/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransfer.kt)
  reads import sources, extracts all selected channels before storage, then
  persists them in order. A later storage failure does not roll back earlier
  committed batches. Export takes a subscription snapshot and writes it to the
  selected document. Unavailable streams and failed writes are failures, not
  successful empty transfers; coroutine cancellation is propagated. The workers
  retain scheduling and foreground presentation.
- **Backup and restore:**
  [`BackupRestore`](../app/src/main/java/org/schabi/newpipe/settings/BackupRestore.java),
  [`BackupOperations`](../app/src/main/java/org/schabi/newpipe/settings/BackupOperations.java),
  and
  [`ImportExportManager`](../app/src/main/java/org/schabi/newpipe/settings/export/ImportExportManager.kt)
  own admission, export, inspection, and accepted restore. Export checkpoints
  the database and writes the app database plus JSON and legacy preferences;
  **downloads are excluded**: the separate finished-download database, pending
  mission metadata, and media files are not backed up. An inspection is advisory,
  not database validation, and the source can change before restore. JSON
  settings take precedence over legacy serialization when both exist. A restore
  prepares a private snapshot before changing live settings, then publishes a
  pending database for
  [`PendingDatabaseRestore`](../app/src/main/java/org/schabi/newpipe/settings/export/PendingDatabaseRestore.java)
  to install at startup, before database connections open. Completion means
  staged with restart requested, **not** installed in the current process. A
  failed restart leaves activation pending; while pending, further restores and
  exports are refused. Preference writes and filesystem publication are not one
  transaction: failures attempt preference recovery, but process-death
  consistency and compatibility with future database migrations are not
  guaranteed. Detached observers do not cancel accepted backup work.

## Verification navigation

Run `make ci` for unit checks and APK builds. Focused unit coverage:
[`FeedRefreshTest`](../app/src/test/java/org/schabi/newpipe/local/feed/service/FeedRefreshTest.kt),
[`SubscriptionTransferTest`](../app/src/test/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransferTest.kt),
[`BackupRestoreTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupRestoreTest.kt),
[`BackupRestoreFailureTest`](../app/src/test/java/org/schabi/newpipe/settings/BackupRestoreFailureTest.kt),
and
[`PendingDatabaseRestoreTest`](../app/src/test/java/org/schabi/newpipe/settings/PendingDatabaseRestoreTest.java).
Device-backed Room/Android coverage:
[`FeedRefreshDatabaseTest`](../app/src/androidTest/java/org/schabi/newpipe/local/feed/service/FeedRefreshDatabaseTest.kt),
[`SubscriptionTransferDatabaseTest`](../app/src/androidTest/java/org/schabi/newpipe/local/subscription/workers/SubscriptionTransferDatabaseTest.kt),
and
[`BackupRestoreIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/settings/BackupRestoreIntegrationTest.java).
