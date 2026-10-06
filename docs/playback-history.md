# Playback history

`PlaybackHistory` owns view registration, progress checkpoints, completion
markers, discontinuity interpretation and resume outcomes. `Player` supplies
playback snapshots and retains ExoPlayer control and intent-request cancellation.
Queue recovery uses content position; saved progress uses playback position.
Normal checkpoints require matching queue and media-item indexes. Completion
records the outgoing stream before a transition moves the queue index.

Room transactions and stored-state validity remain in `HistoryRecordManager`.
Search history, list presentation, queue loading and playback source selection
remain independent. Production uses the Android preferences and Room adapter;
module tests use a scripted adapter and device tests use real in-memory Room.

This structural refactor preserves independent IO writes, cancellation on reset,
and the existing preference defaults (view registration: false, progress: true).
Shutdown persistence and write ordering require separate reproduced corrections.
Resume emits `RECOVERY_UNSET` for absent, finished or failed history. Disposing
the caller's resume subscription prevents late playback decisions.
