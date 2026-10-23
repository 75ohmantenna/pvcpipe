# Settings import fixtures

The ZIP files are synthetic backup archives for settings-import tests. They
contain no user backup data. Their names describe the entries:

- `db` or `nodb`: whether `newpipe.db` is in it.
- `ser`, `vulnser` or `noser`: whether `newpipe.settings` holds ordinary
  serialized preferences, an injection payload, or is missing.
- `json` or `nojson`: whether `preferences.json` is in it.

The standalone `newpipe.db` is a text placeholder, not a SQLite database.
`BackupRestoreFailureTest` packs it into archives built during the test, and
`ImportExportManagerTest` compares its text after export. Those controlled
rollback/preparation and archive-copy tests do not establish that SQLite can
open that payload; device/Room tests are separate evidence.
