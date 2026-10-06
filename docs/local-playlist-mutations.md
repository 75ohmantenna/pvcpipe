# Local playlist contents

`LocalPlaylistManager` owns content mutations and their automatic-thumbnail
policy. Callers append streams, replace ordered stream IDs, or remove duplicates,
watched streams, or watched and partially watched streams. The fragment retains
confirmation dialogs, loading state, presentation, and debounce handling for
manual edits. The append dialog submits one manager operation.

Appends retain duplicates and assign indexes after the current maximum. Replacing
contents preserves the supplied order and duplicates. Duplicate removal keeps the
first occurrence. Watched removal requires both history and saved playback state;
full-only removal additionally uses the existing finished-playback calculation.
Automatic thumbnails remain when their stream survives, otherwise they use the
first surviving stream or the default for empty contents. Permanent thumbnails
remain unchanged by content removal.

The existing application-owned scheduler and cached content writes retain their
ordering and accepted completion behavior. This refactor leaves content and
thumbnail writes separate, with reactive selection outside transactions. Bulk
selection also precedes the queued content rewrite. Atomicity, observer disposal
during follow-up work, and stale snapshots are reserved for separate corrections.
Never block on a Room Rx query from inside a database transaction: a scheduled
query can wait for the transaction that is waiting for that query.

The database is local-substitutable. Tests exercise the manager interface with
in-memory Room, actual history and playback state, joins, and playlist metadata.
No additional adapter is needed. Mutation tests retain distinct scheduler and
observer-lifetime coverage. Run `make ci` for ordinary checks and APK builds; run
playlist database tests on an Android device for transaction and persistence
coverage.
