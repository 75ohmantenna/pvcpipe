# Playback development guide

Playback policy is split by responsibility. Read the linked implementations for
selection rules, event handling and defaults; this page is a map, not a second
specification of their control flow.

- **Sources:** [`PlaybackSources`](../app/src/main/java/org/schabi/newpipe/player/resolver/PlaybackSources.java)
  selects streams for player modes and decides whether toggling video requires a
  new source. [`AndroidPlaybackEnvironment`](../app/src/main/java/org/schabi/newpipe/player/resolver/AndroidPlaybackEnvironment.java)
  binds preferences and ExoPlayer construction;
  [`PlaybackResolver`](../app/src/main/java/org/schabi/newpipe/player/resolver/PlaybackResolver.java)
  retains delivery/manifest construction. Selection-path facts travel with the
  source's [`StreamInfoTag`](../app/src/main/java/org/schabi/newpipe/player/mediaitem/StreamInfoTag.java),
  so a later or failed resolution cannot change an existing source's reload
  decision. Unknown source facts require reconstruction. Queue preloading,
  expiration and timeline management are not source-selection policy.
- **Audio:** [`PlaybackAudio`](../app/src/main/java/org/schabi/newpipe/player/audio/PlaybackAudio.java)
  owns internal-volume intent, mute, focus and restoration; the
  [`AndroidPlaybackAudioEnvironment`](../app/src/main/java/org/schabi/newpipe/player/audio/AndroidPlaybackAudioEnvironment.java)
  owns Android focus, session and animation effects. Call it on the player's
  application thread and dispose it before releasing ExoPlayer. The chosen user
  volume is distinct from temporary ducking/restoration and from Android's
  music-stream volume; focus loss need not resume playback after an explicit
  pause or mute.
- **History:** [`PlaybackHistory`](../app/src/main/java/org/schabi/newpipe/player/history/PlaybackHistory.java)
  interprets player snapshots for viewing, progress, completion and resume;
  [`AndroidPlaybackHistoryEnvironment`](../app/src/main/java/org/schabi/newpipe/player/history/AndroidPlaybackHistoryEnvironment.java)
  binds preferences and Room-backed
  [`HistoryRecordManager`](../app/src/main/java/org/schabi/newpipe/local/history/HistoryRecordManager.java).
  Queue recovery uses content position; persisted progress uses playback
  position. Accepted writes outlive player destruction and finish in admission
  order; resume observes prior writes. Absent, completed or failed history has
  no saved resume position. Watch-history preferences govern persistence, not
  queue recovery.
- **Proof tokens:** The extractor's
  [`PoTokenProviderImpl`](../app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenProviderImpl.kt)
  delegates Web-client requests to
  [`WebPoTokenProvider`](../app/src/main/java/org/schabi/newpipe/util/potoken/WebPoTokenProvider.kt),
  which owns coherent session publication, request ownership, expiry and
  replacement. [`PoTokenWebView`](../app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenWebView.kt)
  owns browser initialization and token callbacks. Unsupported or broken WebView
  means no Web-client proof token; the other client methods do not provide
  tokens. These tokens are not a general fallback for all extractor clients.

## Queue mutation contract

[`PlayQueue`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueue.java)
changes the queue and broadcasts
[`PlayQueueEvent`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueueEvent.kt)
for asynchronous observers such as
[`MediaSourceManager`](../app/src/main/java/org/schabi/newpipe/player/playback/MediaSourceManager.java)
and [`PlayQueueAdapter`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueueAdapter.java).
Append events carry the insertion index at mutation time: observers must not
infer it from the queue's later size. Replacing an auto-queued tail with manual
items emits removal before append; the timeline and shuffled backup must lose
that tail as well. Keep the queue, media-source timeline and UI consistent when
changing mutations.

## Test entrypoints

- Source policy: [`PlaybackSourcesTest`](../app/src/test/java/org/schabi/newpipe/player/resolver/PlaybackSourcesTest.java),
  [`PlaybackSourcesSafetyTest`](../app/src/test/java/org/schabi/newpipe/player/resolver/PlaybackSourcesSafetyTest.java),
  [`PlaybackSourcesIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/resolver/PlaybackSourcesIntegrationTest.kt).
- Focus/volume: [`PlaybackAudioTest`](../app/src/test/java/org/schabi/newpipe/player/audio/PlaybackAudioTest.java),
  [`PlaybackAudioFocusProtocolTest`](../app/src/test/java/org/schabi/newpipe/player/audio/PlaybackAudioFocusProtocolTest.java),
  [`PlaybackAudioIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/audio/PlaybackAudioIntegrationTest.kt).
- History: [`PlaybackHistoryTest`](../app/src/test/java/org/schabi/newpipe/player/history/PlaybackHistoryTest.java),
  [`PlaybackHistoryPlayerLifecycleTest`](../app/src/test/java/org/schabi/newpipe/player/history/PlaybackHistoryPlayerLifecycleTest.java),
  [`PlaybackHistoryIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/history/PlaybackHistoryIntegrationTest.kt).
- Tokens: [`WebPoTokenProviderTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/WebPoTokenProviderTest.kt),
  [`WebPoTokenProviderReplacementTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/WebPoTokenProviderReplacementTest.kt),
  [`PoTokenWebViewLifecycleTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/PoTokenWebViewLifecycleTest.kt),
  [`PoTokenWebViewIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/util/potoken/PoTokenWebViewIntegrationTest.kt).
- Queue event ordering: [`PlayQueueTest`](../app/src/test/java/org/schabi/newpipe/player/playqueue/PlayQueueTest.java).
