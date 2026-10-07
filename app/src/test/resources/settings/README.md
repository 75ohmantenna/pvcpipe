The `*.zip` files here are database-export fixtures for settings import tests.
Their names describe which archive entries are present:

- `db` / `nodb`: whether `newpipe.db` is included.
- `ser` / `vulnser` / `noser`: whether `newpipe.settings` is included with ordinary
  serialized preferences, an injection payload, or no serialized preferences.
- `json` / `nojson`: whether `preferences.json` is included.
