# Backup and restore ownership

`BackupRestore` owns export, inspection, and accepted restore. The settings
fragment selects documents and presents confirmation and result dialogs.

Operations begin on first subscription. Each request caches its result; observing
it again does not repeat the work. Create a new request to retry. All production
instances share application-owned admission through `BackupOperations`, which
rejects overlapping work. Inspection releases admission before delivering its
result, so a confirmation dialog does not hold a reservation.

Detaching observers does not interrupt accepted work. The module handles restore
restart and error notification once on the main scheduler, even if the settings
view has disappeared. Completion means the restore is staged and a restart has been requested.

Inspection is advisory: the current stream probe is not database validation and
a document can change after inspection. JSON preferences take precedence over
legacy serialized preferences; the fragment retains the legacy warning.

Exports checkpoint the database, truncate the selected document, and include
the database plus both JSON and legacy serialized preferences. The legacy
format remains in exported archives for compatibility, but inspection offers
JSON when both settings entries are present.

An accepted restore first makes a private archive snapshot, decodes selected
settings without writing them, and extracts a nonempty database to a unique
unpublished file. A changed settings format requires another inspection rather
than silently switching to legacy deserialization. Preparation errors leave the
live database and preferences untouched.

Commit captures a copy of existing preferences, including string sets. It writes
normalized settings and the remembered URI together, then publishes the database
without replacing an existing pending restore. Preference or publication failure
attempts rollback. Failed rollback reports recovery-required state, retained by
the application-owned module; subsequent operations retry recovery before doing
new work. Restart failure after publication reports that activation still needs
a restart and preserves the pending database. While activation is pending, new
restores and exports are rejected; exporting would pair the old live database
with the newly restored settings.

The preference and filesystem writes are not a cross-store transaction. This
change handles reported errors and retains the existing startup retry behavior;
it adds no process-death consistency guarantee. Archive/database extraction does
not prove compatibility with a future Room schema migration.

Staging leaves the live database and WAL untouched. `PendingDatabaseRestore`
activates the pending database in `App.attachBaseContext`, before content
providers can open a database connection.

Workflow tests use real ZIP fixtures and temporary files with controlled
preferences, scheduling, and platform effects. `BackupRestoreTest` covers
admission, detached observers, replay, format selection, export, and staging.
`BackupRestoreFailureTest` covers preparation, failed commits and rollback,
source changes, cleanup, and failed restart. `BackupRestoreIntegrationTest` uses
isolated files and real Android preferences. `PendingDatabaseRestoreTest` covers
the startup lifecycle. Run `make ci` for application and deterministic extractor
checks and both APK builds.
