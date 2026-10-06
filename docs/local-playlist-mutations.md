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

The first subscription accepts each mutation once. The complete transaction is
cached on the shared application writer, so disposing presentation does not stop
queued content or thumbnail work and later observers receive its stored result.
Append stream fields and content ID lists are copied when the operation is
created. Playlist creation retains its existing behavior. Renames,
explicit thumbnails, bookmark ordering, and deletions use the same writer and
read current metadata in their transactions; bookmark ordering only changes the
display index, preserving newer names and thumbnails.

Content selection, history/state classification, index replacement, thumbnail
selection, and the returned ordered result use synchronous Room queries inside
one transaction. No blocking Rx query is subscribed from that transaction.
Automatic thumbnails, including the empty default, change atomically with joins;
permanent thumbnails remain unchanged, including permanent defaults. Missing
playlists fail. Every replacement validates its joined stream count before
commit, so unknown IDs fail inside the transaction body and roll back reliably.
This also prevents cleanup filtering from hiding dangling pending joins.

Bulk cleanup accepts an optional copied pending list of stream IDs. Null selects
stored contents; a non-null empty list clears them before applying cleanup. The
fragment submits dirty pending edits together with cleanup, blocks drag, delete,
and separate saves during rewriting, and acknowledges only the captured saver
revision. Success, failure, and view destruction clear presentation rewriting
state. Mutation failures use a snackbar and preserve the displayed draft and
database observation, so a later lifecycle save cannot replace rolled-back
contents with an error-cleared empty list. Failed work leaves dirty edits
available for retry. View destruction only detaches observers from already
accepted mutations. Read-error reset marks contents unloaded, clears rewrite
ownership, and detaches old callbacks before retry observers are attached. The
cleared adapter cannot be saved as an empty loaded playlist.

The database is local-substitutable. Tests exercise the manager interface with
in-memory Room, actual history and playback state, joins, and playlist metadata.
No additional adapter is needed. Real Room tests use controlled scheduling to
cover disposed accepted appends,
consecutive index allocation, and the execution order of two disposed accepted
replacements. These replace the overlapping DAO-mock mutation tests. Fragment
regressions cover failed draft saves and read-error reset/reload races. Run
`make ci` for ordinary checks and APK builds; run
playlist database tests on an Android device for transaction and persistence
coverage.
