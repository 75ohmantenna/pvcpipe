# Playback audio

`PlaybackAudio` owns internal-volume restoration bookkeeping, mute effects,
audio-focus eligibility and focus-triggered playback effects. `Player` reports
playback intentions before its normal play/pause operations and retains queue,
replay, history and UI control. A gesture changes internal volume once; it does
not save restoration state separately. Android music-stream volume remains
explicitly distinct from ExoPlayer internal volume.

The caller interface and Android focus callbacks run on the player application
thread. The Android adapter owns focus requests, analytics registration,
audio-effect session broadcasts, volume-command checks, preferences and
1500 ms restoration animations. Tests use the same playback interface with a
controlled adapter for focus and animation delivery. Device tests exercise the
production adapter with real ExoPlayer and Android animation.

This structural change preserves existing focus behavior: loss stores effective
volume and pauses, duck stores effective volume and sets 0.2, and gain fades from
0.2 to the stored level. Gain resumes directly when the existing preference is
enabled. Unsupported internal-volume commands retain existing toast feedback
and getter fallback of 1.0. Animation replacement, mute/focus interactions and
post-disposal effects remain subjects for separately reproduced corrections.
