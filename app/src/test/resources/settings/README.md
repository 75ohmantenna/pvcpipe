# Settings import fixtures

The ZIP files are synthetic backup archives for settings-import tests.
Filename components identify the entries.

- `db` and `nodb` mark the presence or absence of `newpipe.db`.
- `ser`, `vulnser` and `noser` mark ordinary serialized preferences, an
  injection-test payload or an absent `newpipe.settings`, respectively.
- `json` and `nojson` mark the presence or absence of `preferences.json`.

The standalone `newpipe.db` is a text placeholder.
`BackupRestoreFailureTest` packs it into archives built during the test, and
`ImportExportManagerTest` compares its text after export. These tests use
a no-op database validator to exercise rollback, preparation and archive copying.
`BackupValidationTest` creates SQLite candidates from exported
Room schemas (versions 2–9), plus a version-1 fixture derived from migration
inputs. It uses the production validator, Room and native host SQLite under
Robolectric.
