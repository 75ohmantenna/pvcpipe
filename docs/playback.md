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
decision for a source that already exists. A source whose tag does not say
how it was chosen is rebuilt. Preloading the queue, expiring loaded items and
managing the timeline belong to `MediaSourceManager`, not here.

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

- A write that was accepted is carried out even if the player is destroyed.
  Writes finish in the order they were accepted, and a resume lookup waits
  for the writes before it.
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
| Queue requests | [`QueuePlaybackChoiceTest`](../app/src/test/java/org/schabi/newpipe/player/QueuePlaybackChoiceTest.java), [`PlayerIntentTest`](../app/src/test/java/org/schabi/newpipe/player/PlayerIntentTest.java) |
| Queue events | [`PlayQueueTest`](../app/src/test/java/org/schabi/newpipe/player/playqueue/PlayQueueTest.java) |
| Audio | [`PlaybackAudioTest`](../app/src/test/java/org/schabi/newpipe/player/audio/PlaybackAudioTest.java), [`PlaybackAudioFocusProtocolTest`](../app/src/test/java/org/schabi/newpipe/player/audio/PlaybackAudioFocusProtocolTest.java), on a device [`PlaybackAudioIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/audio/PlaybackAudioIntegrationTest.kt) |
| History | [`PlaybackHistoryTest`](../app/src/test/java/org/schabi/newpipe/player/history/PlaybackHistoryTest.java), [`PlaybackHistoryPlayerLifecycleTest`](../app/src/test/java/org/schabi/newpipe/player/history/PlaybackHistoryPlayerLifecycleTest.java), on a device [`PlaybackHistoryIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/history/PlaybackHistoryIntegrationTest.kt) |
| Broadcasts | [`PlayerBroadcastsTest`](../app/src/test/java/org/schabi/newpipe/player/broadcast/PlayerBroadcastsTest.java), on a device [`AndroidPlayerBroadcastsTest`](../app/src/androidTest/java/org/schabi/newpipe/player/broadcast/AndroidPlayerBroadcastsTest.kt) |
| Errors | [`PlaybackErrorsTest`](../app/src/test/java/org/schabi/newpipe/player/error/PlaybackErrorsTest.java) |
| Listeners | [`PlayerListenersTest`](../app/src/test/java/org/schabi/newpipe/player/event/PlayerListenersTest.java) |
| Detail screen | [`DetailStackTest`](../app/src/test/java/org/schabi/newpipe/fragments/detail/DetailStackTest.java), [`DetailOrientationTest`](../app/src/test/java/org/schabi/newpipe/fragments/detail/DetailOrientationTest.java) |
| Proof tokens | [`WebPoTokenProviderTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/WebPoTokenProviderTest.kt), [`WebPoTokenProviderReplacementTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/WebPoTokenProviderReplacementTest.kt), [`PoTokenWebViewLifecycleTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/PoTokenWebViewLifecycleTest.kt), on a device [`PoTokenWebViewIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/util/potoken/PoTokenWebViewIntegrationTest.kt) |
