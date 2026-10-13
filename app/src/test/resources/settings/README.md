# Settings import fixtures

The ZIP files here are made-up backup archives for the settings import tests.
They are not anyone's backup. A file's name says which entries it has:

- `db` or `nodb`: whether `newpipe.db` is in it.
- `ser`, `vulnser` or `noser`: whether `newpipe.settings` holds ordinary
  serialized preferences, an injection payload, or is missing.
- `json` or `nojson`: whether `preferences.json` is in it.

`newpipe.db` is a database by itself. `BackupRestoreFailureTest` packs it
into archives it builds while it runs.
