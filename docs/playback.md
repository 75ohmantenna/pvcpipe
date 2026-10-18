# Playback

The player's decisions are split into small classes with unit tests.
`Player` reads the facts, asks the class that decides, and carries out the
answer. This page says which class decides what. For the rules themselves,
read the class and its test.

## Choosing a source

[`PlaybackSources`](../app/src/main/java/org/schabi/newpipe/player/resolver/PlaybackSources.java)
picks the streams for each player mode and says whether switching video on or
off needs a new source (`requiresReload`).
[`AndroidPlaybackEnvironment`](../app/src/main/java/org/schabi/newpipe/player/resolver/AndroidPlaybackEnvironment.java)
supplies the preferences and builds the ExoPlayer sources.
[`PlaybackResolver`](../app/src/main/java/org/schabi/newpipe/player/resolver/PlaybackResolver.java)
builds sources for each supported delivery method and its manifests. Service
extractors supply stream types and media URLs; the resolver does not discover
service-specific playback endpoints.

What was chosen travels with the source in its
[`StreamInfoTag`](../app/src/main/java/org/schabi/newpipe/player/mediaitem/StreamInfoTag.java).
A later resolution, or one that failed, therefore cannot change the reload
decision for a source that already exists.
A video source whose tag does not say how it was chosen is rebuilt; audio
stream types do not need rebuilding merely to toggle video. Preloading the
queue, expiring loaded items and managing the timeline belong to
`MediaSourceManager`, not here.

## FCast video casting

Start a video, tap the cast button in the player's top row, and select an
FCast receiver on the same local network. **Add by address** accepts a host
or IP with a TCP port (46899 by default), or a pasted `fcast://r/` receiver
link. **Get FCast Receiver** opens the receiver project. This is FCast
casting, not Chromecast, AirPlay, or screen mirroring.

The cast button opens the activity's
[`FCastDialog`](../app/src/main/java/org/schabi/newpipe/player/cast/FCastDialog.java)
**device picker**, not a second playback panel. Selecting a receiver dismisses
the picker after handoff and returns to the regular player controls:

- The normal play/pause button, timeline, left/right double-tap seek gestures,
  keyboard and notification/media-session controls operate the receiver.
  Double-tap seeks use the same configured interval as local playback.
- The normal speed control offers receiver-supported speed changes without
  the local pitch/silence-skipping dialog. Existing volume gestures, mute,
  and media-session volume control the receiver when supported.
- The phone's video stays paused while the receiver owns playback. The
  timeline and play/pause icon reflect remote state, including after seeking
  while paused. Receiver updates do not replace a timeline drag in progress.

Tap the connected cast icon to choose a different receiver, add an address,
or disconnect. The picker keeps discovering devices while open, including
when already connected. During playback, selecting another receiver stops
the old target and preserves the remote position and play/pause intent.
Leaving the activity does not stop an active cast.

While a receiver switch or source replacement is preparing, play/pause, seek
and speed changes update the pending load rather than sending commands to
the old or disconnected target. Quality/audio replacements pause the old
source and retain the latest position and playback intent. Receiver buffering
does not turn a paused load into a playing one, and changing quality after
completion does not automatically replay the video.

- **Disconnect** stops the receiver and leaves the phone paused at the
  receiver's latest position.
- **Play on this device** stops the receiver and resumes locally there.
- Connection or preparation failure leaves local playback available.
  A dropped active connection returns to local playback paused, rather than
  unexpectedly starting the phone's speakers.
- Queue changes, repeat, progress, and watch history remain owned by
  `Player`; receiver progress replaces the paused local preview's clock.
  Local headset disconnection and minimize-on-exit preferences do not pause
  an active receiver.
- Rebuilding a source for the same queue item retains its remote clock.
  Moving to another queue occurrence invalidates the old source even when
  both entries have the same video URL.
- Returning to the phone during a timeline drag clears the remote seek state.

### Components and network lifetime

[`CastPlayback`](../app/src/main/java/org/schabi/newpipe/player/cast/CastPlayback.java)
owns the handoff, asynchronous preparation, remote clock, and per-load
resources for one player service.
[`FCastSession`](../app/src/main/java/org/schabi/newpipe/player/cast/FCastSession.kt)
adapts the official `org.fcast:sender-sdk:0.6.1`: native SDK calls run off the
main thread, callbacks return to it, and connection generations discard stale
events. It supports negotiated v2/v3 and secure v4 connections. v2 has no
explicit end event, so completion is inferred conservatively from a playing
to idle transition near a known duration; a remote Stop exactly there is
indistinguishable from natural completion on that old protocol.

[`FCastDiscovery`](../app/src/main/java/org/schabi/newpipe/player/cast/FCastDiscovery.kt)
browses only `_fcast._tcp` with Android NSD while the picker is open. It keeps
resolved addresses and TXT security records, and releases callbacks and the
multicast lock when the picker closes. Android 17/API 37 requests local
network permission only after the user opens casting. Denial leaves an
explanation and an explicit permission action, without starting discovery.

[`CastMediaResolver`](../app/src/main/java/org/schabi/newpipe/player/cast/CastMediaResolver.java)
uses the selected extracted streams, not the public webpage URL. It serves
HLS/DASH manifests and media through
[`CastMediaServer`](../app/src/main/java/org/schabi/newpipe/player/cast/CastMediaServer.java)
on the connection's local address. This keeps phone-origin-bound media
requests and required service headers on the phone. YouTube uses the
existing extractor DASH creators and `YoutubeHttpDataSource`, including its
POST-based transport. Separate supported audio and video are combined into
one receiver manifest, not silently cast without audio.

The HTTP server has tokenized allowlisted routes, bounded request workers,
manifest sizes and route counts, byte-range/HEAD support, and no arbitrary
receiver-selected upstream URL. Only GET, HEAD and OPTIONS are accepted;
receiver-supplied cookies and authorization are not forwarded upstream.
The server lives for the cast, with a foreground media notification and a CPU
wake lock. Disconnect/error/shutdown cancels preparation and closes media
servers; SDK shutdown stays off the UI thread.

### Security and media limits

Use a trusted local network. v4 control uses the SDK's TLS/public-key
fingerprint verification. Discovery retains the advertised fingerprint;
pasted receiver links can carry it directly from the receiver's screen.
A plain address has no fingerprint and cannot connect to a receiver that
requires secure v4. No unverified TLS or insecure downgrade bypass is added.
v2/v3 control and the tokenized LAN media proxy use unencrypted transport;
v4 control encryption does not encrypt the proxy's HTTP media traffic.

Supported sources include muxed progressive video, HLS/DASH (including inline
extractor manifests), paired HLS or single-period DASH tracks, and YouTube
adaptive progressive/OTF/post-live streams with extractor index metadata.
There is no transcoding/remuxing. Arbitrary split progressive tracks without
index metadata, replacing embedded audio in a muxed stream, and unsupported
DASH alternatives/templates fail explicitly. Receiver codec support still
determines playback. Local subtitle, resize, pitch and silence-skipping
controls are not receiver controls. Subtitle/resize controls are disabled
while casting; the normal speed menu is enabled when the receiver advertises
speed support. Media-session transport speed changes are not enabled by the
connector's default action set; use the player's speed menu instead.

The implementation follows the official
[FCast protocol](https://docs.fcast.org/protocol/) and
[Android sender guidance](https://docs.fcast.org/sdk/tutorials/android/).
[Grayjay's casting integration](https://github.com/futo-org/grayjay-android/tree/master/app/src/main/java/com/futo/platformplayer/casting)
was studied for SDK usage, receiver state, and media-serving/handoff design.
The regular-control interaction follows Grayjay's
[`CastView`](https://github.com/futo-org/grayjay-android/blob/master/app/src/main/java/com/futo/platformplayer/views/casting/CastView.kt)
(play/pause, scrub and seek gestures) and
[`CastButton`](https://github.com/futo-org/grayjay-android/blob/master/app/src/main/java/com/futo/platformplayer/views/casting/CastButton.kt)
(connection-dialog entrypoint). PVCPipe reuses its existing player overlay
rather than introducing a second cast player view.
See the [regular-control smoke record](pvcpipe-device-smoke.md#fcast-regular-player-controls-2026-10-17)
for this interaction cutover, and the earlier
[Pixel/receiver](pvcpipe-device-smoke.md#fcast-pixel-6a-and-amazon-receiver-2026-10-17)
and [local emulator](pvcpipe-device-smoke.md#fcast-local-emulator-smoke-2026-10-17)
records for transport/media checks and remaining hardware limits.
The [0.2.0 release review](pvcpipe-device-smoke.md#pvcpipe-020-release-review-2026-10-18)
records the later adversarial fixes and controlled app/protocol verification.

## A request to play a queue

[`QueuePlaybackChoice`](../app/src/main/java/org/schabi/newpipe/player/QueuePlaybackChoice.java)
decides what the player does with a queue it is asked to play. It checks, in
this order, whether to seek in place, keep the loaded queue, or look up a
resume position first. If none applies, the queue starts from scratch.
`Player.handleQueuePlayback` cancels any pending decision before it asks, and
carries out the answer.

## Changing the queue

[`PlayQueue`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueue.java)
changes the queue and broadcasts a
[`PlayQueueEvent`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueueEvent.kt)
for each change.
[`MediaSourceManager`](../app/src/main/java/org/schabi/newpipe/player/playback/MediaSourceManager.java)
and
[`PlayQueueAdapter`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueueAdapter.java)
receive the events asynchronously. Two rules follow from that:

- An append event carries the index at which the items went in. An observer
  must not work it out from the size of the queue, which may have changed by
  the time the event arrives.
- Replacing a tail the app queued automatically with items the user picked
  sends the removal before the append. The timeline and the shuffle backup
  have to lose that tail as well.

When you change how the queue is modified, keep the queue, the media-source
timeline and the list on screen in step.

## Volume, mute and audio focus

[`PlaybackAudio`](../app/src/main/java/org/schabi/newpipe/player/audio/PlaybackAudio.java)
decides about the player's own volume, mute, audio focus and what is restored
when focus returns.
[`AndroidPlaybackAudioEnvironment`](../app/src/main/java/org/schabi/newpipe/player/audio/AndroidPlaybackAudioEnvironment.java)
does the Android part: focus requests, the audio session and volume fades.
Call `PlaybackAudio` on the player's application thread and dispose it before
releasing ExoPlayer.

The volume the user chose is one thing. The temporary lowering while another
app has focus is another, and Android's music-stream volume is a third. After
the user paused or muted, regaining focus does not start playback again.

## History and resume

[`PlaybackHistory`](../app/src/main/java/org/schabi/newpipe/player/history/PlaybackHistory.java)
turns snapshots of the player into views, saved progress, completions and
resume positions.
[`AndroidPlaybackHistoryEnvironment`](../app/src/main/java/org/schabi/newpipe/player/history/AndroidPlaybackHistoryEnvironment.java)
connects it to the preferences and to the Room-backed
[`HistoryRecordManager`](../app/src/main/java/org/schabi/newpipe/local/history/HistoryRecordManager.java).

- A write that was accepted continues even if the player is destroyed, while
  the application process remains alive. Writes are attempted in the order
  they were accepted, and a resume lookup waits for the writes before it.
  Disposal is not cancellation, but database failures can still lose a write.
- A stream with no history, a finished one, or a failed lookup has no resume
  position.
- The queue's own recovery position is the content position and is kept with
  the watch history switched off. The progress written to the database is the
  playback position and is written only with it switched on.

## Broadcasts

[`PlayerBroadcasts`](../app/src/main/java/org/schabi/newpipe/player/broadcast/PlayerBroadcasts.java)
lists the actions the player listens for and maps each to a player command.
It also remembers whether the screen is on.
[`AndroidPlayerBroadcasts`](../app/src/main/java/org/schabi/newpipe/player/broadcast/AndroidPlayerBroadcasts.java)
registers the receiver. Every broadcast runs its command first and then goes
to the player UIs. An action that only a UI handles still has to be in the
list in `PlayerBroadcasts`, or the receiver never gets it.

## Errors

[`PlaybackErrors`](../app/src/main/java/org/schabi/newpipe/player/error/PlaybackErrors.java)
sorts ExoPlayer's error codes by what the player does about them: restart at
the live edge, skip the stream, reload, or shut down. `Player.onPlayerError`
carries that out and tells the user about every error except the live-edge
restart.

## Listeners

[`PlayerListeners`](../app/src/main/java/org/schabi/newpipe/player/event/PlayerListeners.java)
holds the one fragment and the one activity that follow the player. It sends
them queue, metadata, playback, progress and audio-track updates, the
fragment first. `Player` decides when an update is due and reads the values.

## The detail screen

[`DetailStack`](../app/src/main/java/org/schabi/newpipe/fragments/detail/DetailStack.java)
is the history behind the detail screen's Back key. It adds an entry when a
stream loads, or when the player reports a queue with other streams. When a
stored entry holds the same streams as the player's queue, it gives the entry
the player's live queue. `VideoDetailFragment` decides what to show for the
entry Back returns to.

[`DetailOrientation`](../app/src/main/java/org/schabi/newpipe/fragments/detail/DetailOrientation.java)
decides what the rotation button does, when a rotation enters or leaves
fullscreen, when opening the player rotates the activity, and when turning to
landscape starts a player that is not playing. `VideoDetailFragment` reads
the device and player facts and makes the calls. `MainPlayerUi` handles
vertical videos by itself.

## YouTube proof tokens

PVCPipe provides YouTube web-client proof tokens through the extractor's
`PoTokenProvider` interface.
[`PoTokenProviderImpl`](../app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenProviderImpl.kt)
is what the extractor calls. It answers for the web client only and returns
nothing for the embedded web, Android and iOS clients. The visionOS request
path does not call a proof-token provider.
[`WebPoTokenProvider`](../app/src/main/java/org/schabi/newpipe/util/potoken/WebPoTokenProvider.kt)
keeps the session: who is making one, when it expires and when it is
replaced.
[`PoTokenWebView`](../app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenWebView.kt)
runs YouTube's script in a WebView and hands back the tokens.

On a device with no WebView, or a broken one, there is no token. The tokens
are not a fallback that rescues the other clients.
[services.md](services.md#youtube) has the order in which the clients are
tried.

## Tests

Unit tests run in `make ci`. The ones marked "on a device" are
instrumentation tests (see [building.md](building.md)).

| Area | Tests |
| --- | --- |
| Sources | [`PlaybackSourcesTest`](../app/src/test/java/org/schabi/newpipe/player/resolver/PlaybackSourcesTest.java), [`PlaybackSourcesSafetyTest`](../app/src/test/java/org/schabi/newpipe/player/resolver/PlaybackSourcesSafetyTest.java), on a device [`PlaybackSourcesIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/resolver/PlaybackSourcesIntegrationTest.kt) |
| FCast | [`CastPlaybackTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastPlaybackTest.java), [`CastManifestTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastManifestTest.java), [`CastMediaServerTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastMediaServerTest.java), [`CastMediaTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastMediaTest.java), [`FCastDeviceTest`](../app/src/test/java/org/schabi/newpipe/player/cast/FCastDeviceTest.kt), [`LegacyFCastCompletionTest`](../app/src/test/java/org/schabi/newpipe/player/cast/LegacyFCastCompletionTest.kt); on a device [`CastDashReceiverCompatibilityTest`](../app/src/androidTest/java/org/schabi/newpipe/player/cast/CastDashReceiverCompatibilityTest.java) |
| Queue requests | [`QueuePlaybackChoiceTest`](../app/src/test/java/org/schabi/newpipe/player/QueuePlaybackChoiceTest.java), [`PlayerIntentTest`](../app/src/test/java/org/schabi/newpipe/player/PlayerIntentTest.java) |
| Queue events | [`PlayQueueTest`](../app/src/test/java/org/schabi/newpipe/player/playqueue/PlayQueueTest.java) |
| Audio | [`PlaybackAudioTest`](../app/src/test/java/org/schabi/newpipe/player/audio/PlaybackAudioTest.java), [`PlaybackAudioFocusProtocolTest`](../app/src/test/java/org/schabi/newpipe/player/audio/PlaybackAudioFocusProtocolTest.java), on a device [`PlaybackAudioIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/audio/PlaybackAudioIntegrationTest.kt) |
| History | [`PlaybackHistoryTest`](../app/src/test/java/org/schabi/newpipe/player/history/PlaybackHistoryTest.java), [`PlaybackHistoryPlayerLifecycleTest`](../app/src/test/java/org/schabi/newpipe/player/history/PlaybackHistoryPlayerLifecycleTest.java), on a device [`PlaybackHistoryIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/history/PlaybackHistoryIntegrationTest.kt) |
| Broadcasts | [`PlayerBroadcastsTest`](../app/src/test/java/org/schabi/newpipe/player/broadcast/PlayerBroadcastsTest.java), on a device [`AndroidPlayerBroadcastsTest`](../app/src/androidTest/java/org/schabi/newpipe/player/broadcast/AndroidPlayerBroadcastsTest.kt) |
| Errors | [`PlaybackErrorsTest`](../app/src/test/java/org/schabi/newpipe/player/error/PlaybackErrorsTest.java) |
| Listeners | [`PlayerListenersTest`](../app/src/test/java/org/schabi/newpipe/player/event/PlayerListenersTest.java) |
| Detail screen | [`DetailStackTest`](../app/src/test/java/org/schabi/newpipe/fragments/detail/DetailStackTest.java), [`DetailOrientationTest`](../app/src/test/java/org/schabi/newpipe/fragments/detail/DetailOrientationTest.java) |
| Proof tokens | [`WebPoTokenProviderTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/WebPoTokenProviderTest.kt), [`WebPoTokenProviderReplacementTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/WebPoTokenProviderReplacementTest.kt), [`PoTokenWebViewLifecycleTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/PoTokenWebViewLifecycleTest.kt), on a device [`PoTokenWebViewIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/util/potoken/PoTokenWebViewIntegrationTest.kt) |
