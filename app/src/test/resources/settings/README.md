The `*.zip` files in this directory are database-export fixtures for settings
import tests. Their names indicate which archive entries are present:

- `db` / `nodb`: whether `newpipe.db` is present.
- `ser` / `vulnser` / `noser`: whether `newpipe.settings` contains ordinary
  serialized preferences, an injection payload, or no serialized preferences.
- `json` / `nojson`: whether `preferences.json` is present.
