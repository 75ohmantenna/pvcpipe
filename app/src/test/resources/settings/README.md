# Settings import fixtures

The ZIP files are synthetic backup archives for settings-import tests. They
contain no user backup data. Their names describe the entries:

- `db` or `nodb`: whether `newpipe.db` is in it.
- `ser`, `vulnser` or `noser`: whether `newpipe.settings` holds ordinary
  serialized preferences, an injection payload, or is missing.
- `json` or `nojson`: whether `preferences.json` is in it.

`newpipe.db` is a database by itself. `BackupRestoreFailureTest` packs it
into archives it builds while it runs.
