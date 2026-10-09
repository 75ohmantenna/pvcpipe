# Playback history

`PlaybackHistory` owns view registration, progress checkpoints, completion
markers, discontinuity interpretation, and resume outcomes. `Player` supplies
playback snapshots and retains ExoPlayer control and intent-request
cancellation. Queue recovery uses content position; saved progress uses playback
position. Normal checkpoints require matching queue and media-item indexes.
Completion records the outgoing stream before a transition moves the queue
index.

Room transactions and stored-state validity remain in `HistoryRecordManager`.
Search history, list presentation, queue loading and playback source selection
remain independent. Production uses the Android preferences and Room adapter;
module tests use a scripted adapter and device tests use real in-memory Room.

Accepted recordings run in admission order on one application-owned queue shared
by production history instances. Player destruction cannot cancel them. Failed
progress saves are logged in debug builds; failed recordings do not prevent
later ones. Accepted recording operations retain stream metadata and
database/preferences adapters, never a Player or its UI. This ownership lasts
while the application process is running.

Resume waits for recordings accepted before its subscription, then performs its
lookup outside the recording queue. A slow extraction cannot block later writes.
Disposal before the write barrier finishes prevents lookup. During lookup it
detaches delivery without canceling earlier recordings. Absent, finished or
failed history yields `RECOVERY_UNSET`.

With no stored preference, `HistoryRecordManager` defaults view registration to
false while `AndroidPlaybackHistoryEnvironment` defaults progress saving to true.
The history settings screen declares a true default for watch history.

Module tests control write completion and extraction; real in-memory Room tests
verify preferences, persistence and ordering across production history
instances. An integration test calls actual `Player.destroy()` before the final
write runs.
