# Playback audio

`PlaybackAudio` owns internal-volume restoration bookkeeping, mute effects,
audio-focus eligibility, and focus-triggered playback effects. `Player` reports
playback intentions before its normal play/pause operations and retains queue,
replay, history, and UI control. A gesture changes internal volume once; it does
not save restoration state separately. Android music-stream volume remains
distinct from ExoPlayer internal volume.

The caller interface and Android focus callbacks run on the player's application
thread. The Android adapter owns focus requests, analytics registration,
audio-effect session broadcasts, volume-command checks, preferences, and
1500 ms restoration animations. Tests use the same playback interface with a
controlled adapter for focus and animation delivery. Device tests exercise the
production adapter with real ExoPlayer and Android animation.

The user level and mute intent are independent of temporary effective volume.
Duck caps output at the smaller of 0.2 and the chosen level, without changing
that level. Repeated losses cannot overwrite it. Gain restores from current
output, and resumes only previously playing playback interrupted by focus loss
when the existing preference permits. Explicit pause or mute clears that resume
intention. Positive volume gestures unmute and request focus only for active
playback; zero gestures mute and release focus. Unmute restores the last audible
level (1.0 when initialized at zero).

Accepted user changes and newer focus events supersede restoration. Ownership
is invalidated before canceling an animator, because Android emits synchronous
cancel/end callbacks. Disposed playback audio ignores subsequent commands and
focus/session/animation callbacks. Player disposes audio before releasing Exo.
Immediate successful focus requests also reconcile ducking or interrupted
restoration; a newer focus event or disposal during the request wins over its
return value. Denied requests do not clear attenuation. Android distinguishes
these immediate results from later focus callbacks in its
[audio-focus guidance](https://developer.android.com/media/optimize/audio-focus).
Denied internal-volume changes preserve prior intent and transition ownership;
UI mute notifications report the resulting state. Unsupported getter feedback
and fallback of 1.0 are preserved. Internal-volume reads otherwise report the
chosen user level, not transient ducking or a restoration frame.
