# Backup and restore ownership

`BackupRestore` owns export, inspection, and accepted restore. The settings
fragment selects documents and presents confirmation and result dialogs.

Operations begin on first subscription. A request caches its result; observing
it again does not repeat work. Create a new request to retry. All production
instances share application-owned admission through `BackupOperations`, which
rejects overlapping work. Inspection releases admission before delivering its
result, so a confirmation dialog holds no reservation.

Detaching observers does not interrupt accepted work. Restore restart and error
notification belong to the module and run once on the main scheduler, even when
the settings view has disappeared. Completion means staged and restart requested.

Inspection is advisory: the current stream probe is not database validation and
a document can change after inspection. JSON preferences take precedence over
legacy serialized preferences; the fragment retains the legacy warning.

The structural refactor preserves preference import and device cleanup before
database staging. A staging failure can therefore leave changed settings. Its
correction is a separate change.

Staging leaves the live database and WAL untouched. `PendingDatabaseRestore`
activates the pending database in `App.attachBaseContext`, before content
providers can open a database connection.

Workflow tests use real ZIP fixtures and temporary files with controlled
preferences, scheduling, and platform effects. `BackupRestoreTest` covers
admission, detached observers, replay, format selection, export, and staging.
`PendingDatabaseRestoreTest` covers the startup lifecycle. Run `make ci` for the
application and deterministic extractor checks and both APK builds.
