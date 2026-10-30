# Playback

PVCPipe separates playback decisions from Android effects. `Player` reads
the current state, delegates decisions to focused classes, and applies the
result. This guide identifies those boundaries; each class and its tests
define the detailed rules.

## Choosing a source

[`PlaybackSources`](../app/src/main/java/org/schabi/newpipe/player/resolver/PlaybackSources.java)
picks the streams for each player mode and says whether switching video on or
off needs a new source (`requiresReload`).
[`AndroidPlaybackEnvironment`](../app/src/main/java/org/schabi/newpipe/player/resolver/AndroidPlaybackEnvironment.java)
supplies the preferences and builds the Media3 ExoPlayer sources.
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

## Seek interval settings

Video/audio settings offer 5–30 seconds in 5-second steps, with a 10-second
default. Inexact seek is off by default. When enabled, the settings screen
filters out 5, 15 and 25 seconds and advances a selected filtered value by
5 seconds, saving the replacement and showing the existing toast.
This filter is an inherited workaround, not a measurement of decoder accuracy.

[`VideoAudioSettingsFragment.updateSeekOptions`](../app/src/main/java/org/schabi/newpipe/settings/VideoAudioSettingsFragment.java)
prefers labels from `R.plurals.seconds`. If plural lookup throws
`Resources.NotFoundException`, it uses the corresponding static label from
`seek_duration_description`, indexed before filtering. The maintained static
labels are English and are paired with `seek_duration_value`; normal Android
locale fallback still happens within resource lookup. Preference values,
defaults and player seek routing are unchanged by this label fallback.

## Media3 sessions and service

The app uses AndroidX Media3 ExoPlayer and one `MediaLibrarySession` hosted by
`PlayerService`. The separate downloaded-file player also uses Media3. The
service's `PvcSessionPlayer` exposes PVCPipe's playback policies to controllers;
it does not give system controllers a second independently managed engine
playlist. Next/previous, selection, seeks, repeat, shuffle and speed route through
the application player, including active FCast playback. A floating queue window
contains at most ten items. Receiver-only changes and local engine events both
invalidate session state. While a newly selected occurrence is not synchronized,
the session presents its pending recovery instead of the previous source's clock.

Binding a browser creates an idle session without starting the decoder or a
foreground notification. `SessionLibraryCallback` serves the existing bookmarks,
history and search catalog with Media3 results and pagination. Browser media IDs
retain their previous format. `PackageValidator` retains the existing caller checks;
Media3's trusted system/notification controllers can also connect. Browser
preparation is asynchronous, canceled by replacement or shutdown, and checked
again when its authenticated service-start intent arrives. Arbitrary URI/search
playback and automatic post-reboot playback resumption remain unsupported.

On API 26/27, `LegacyBrowserBridge` serves native and compat browser transport
through the same catalog and `PackageValidator`, using the existing Media3
platform session token. This bypasses the upstream library bridge's loss of
native caller identity. Its compatibility types come from the pinned Media3
session artifact. Modern browsers retain `SessionLibraryCallback`; legacy
subscriptions get their initial children from the compat service without an
extra children-changed notification.

A stale authenticated browser start still satisfies Android's foreground-start
deadline when needed, then restores the prior notification policy. It neither
creates an engine nor cancels a newer browser preparation.

Optional sidecar subtitles use Media3 `SingleSampleMediaSource` with load errors
treated as end-of-stream. Preparation performs no subtitle I/O; only selected
tracks load. `PlaybackRenderersFactory` enables the matching Media3 renderer
subtitle decoding, and `CustomRenderersFactory` inherits that text policy while
retaining the video-surface workaround. This deprecated/experimental decoding
path is pinned to Media3 1.11.1 and must be reassessed on a future engine upgrade.

`NotificationUtil` is the Media3 notification provider. `PlayerService` owns
notification posting and foreground transitions, overriding Media3's default
notification lifecycle to retain the app's minimize, task-removal and active-cast
policies. The existing channel, notification ID, artwork settings and two custom
action preference keys remain. Android 13+ receives metadata and custom actions
from the session; older versions receive explicit notification actions. The
hardware-button ignore preference applies to media-button events independently
of transport/custom commands. Internal start tokens and the UID-checked local
binder remain separate from public controller commands.

The managed-source playlist retains Media3's `ConcatenatingMediaSource` and its
completion callbacks. Migrating to the newer engine playlist API is a separate
change because the current recovery/synchronization policy depends on those
completion boundaries. Cache directory/keys and the cache database name are
unchanged. See the [dated migration record](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/media3-migration.md) for verification,
the correction of the API 26/27 framework-browser defect and outstanding
hardware checks. Historical engine observations retain their original version and evidence limits.

## FCast video casting

Start a video, tap the cast button in the player's top row, and select an
FCast receiver on the same local network. **Add by address** accepts a
hostname or IP address and TCP port (46899 by default), or a pasted
`fcast://r/` receiver link. **Get FCast Receiver** opens the receiver project.
FCast is not Chromecast, AirPlay or screen mirroring.

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

- **Disconnect** stops the receiver and leaves the phone paused at its latest
  attributable position. A selected replacement without an owned clock retains
  its pending recovery instead of applying a reset position.
- **Play on this device** stops the receiver and resumes locally at that
  attributable position or the selected occurrence's pending recovery.
- Connection or preparation failure leaves local playback available.
  A dropped active connection returns to local playback paused, rather than
  unexpectedly starting the phone's speakers.
- Queue changes, repeat, progress, and watch history remain owned by
  `Player`; receiver progress replaces the paused local preview's clock.
  Local headset disconnection and minimize-on-exit preferences do not pause
  an active receiver.
- Rebuilding a source for the same queue item retains its remote clock.
  Synchronization to another queue occurrence invalidates old preparation even
  before the initial handoff, including when both entries have the same URL.
  Failed metadata cancels preparation rather than leaving an obsolete load alive.
- Returning to the phone during a timeline drag clears the remote seek state.
- An initial idle report does not cancel a pending load. Once playback is
  established, an idle report that is not completion releases remote ownership.
- When the receiver identifies a different sender's replacement source,
  PVCPipe releases its cast connection without sending Stop to that source.
  Without receiver source notifications, as on v2, foreign media cannot be
  identified.

### Components and network lifetime

`CastPlayback` owns handoff, preparation, clock and per-load resources.
`FCastSession` adapts the pinned FCast sender SDK off the main thread and returns
callbacks to it; connection generations discard stale events. The SDK supports
v2/v3 and secure v4, with different acknowledgement, startup-pause and end-event
policies. Source echoes or playback evidence are not proof of receiver decoding.
Paused startup enforces the sender's latest explicit intent rather than adopting
the SDK's synthetic Playing callback. Initial source-start ordering remains
[deferred work](README.md#action-plan).

Sources needing stable headers or manifests use a per-cast HTTP proxy. Its
allowlisted token routes, worker/manifest/route budgets and header deadlines
bound resource use. Only GET, HEAD and OPTIONS are accepted; receivers cannot
choose arbitrary upstream URLs or forward their own cookies/authorization.
The service owns the foreground notification and wake lock. Disconnect, failure
and shutdown cancel preparation and close servers; native SDK shutdown stays
off the UI thread. Read [player/cast/](../app/src/main/java/org/schabi/newpipe/player/cast/) before changing these lifetime rules.

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
adaptive progressive, OTF and post-live streams with extractor index metadata.
Single-file DASH `SegmentList` ranges can use an implicit representation base
URL; the proxy preserves that media resource rather than substituting the MPD.
The proxy does not transcode or remux media. Arbitrary split progressive
tracks without index metadata, replacement of embedded audio in a muxed
stream, and unsupported DASH alternatives/templates fail explicitly.
Receiver codec support still determines whether a source plays.

Local subtitle, resize, pitch and silence-skipping controls do not control the
receiver. Subtitle and resize controls are disabled while casting; the normal
speed menu and Media3 session speed command are available when the receiver
advertises speed support. `PvcSessionPlayer.handleSetPlaybackParameters` sends
only speed to an active receiver; pitch remains a local-playback setting.

FCast follows the [protocol](https://docs.fcast.org/protocol/) and
[Android sender guidance](https://docs.fcast.org/sdk/tutorials/android/).
[Grayjay's casting integration](https://github.com/futo-org/grayjay-android/tree/master/app/src/main/java/com/futo/platformplayer/casting)
was studied for SDK use, receiver state, media serving and the regular-control
interaction. PVCPipe retains its own player overlay and device picker.

### Receiver connects but does not play

If the receiver remains on its ready screen with no playback progress after
a connection, disconnect in PVCPipe, restart the receiver device, then
reconnect normally. This recovered the recorded Fire Stick session; it is a
troubleshooting step, not a guaranteed fix. The receiver's internal failure
cause was not established. A successful connection or HTTP media response
alone does not prove that the receiver has decoded video or played audio.

## A request to play a queue

[`QueuePlaybackChoice`](../app/src/main/java/org/schabi/newpipe/player/QueuePlaybackChoice.java)
decides what the player does with a queue it is asked to play. It checks, in
this order, whether to seek in place, keep the loaded queue, or look up a
resume position first. If none applies, the queue starts from scratch.
`Player.handleQueuePlayback` cancels any pending timestamp/resume decision after
it retrieves a valid cached queue, then carries out the answer. Timestamp
requests also replace that pending decision; enqueueing into an existing queue
does not. Cancellation prevents the old lookup from applying playback changes,
not history writes already accepted by `PlaybackHistory`.

## Changing the queue

[`PlayQueue`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueue.java)
changes the queue and publishes mutation events as
[`PlayQueueEvent`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueueEvent.kt)
values.
[`MediaSourceManager`](../app/src/main/java/org/schabi/newpipe/player/playback/MediaSourceManager.java)
and
[`PlayQueueAdapter`](../app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueueAdapter.java)
receive the events asynchronously. Three rules follow from that:

- Each subscription captures its initial queue size before asynchronous delivery.
  Mutations before subscription are already in that baseline; later events apply
  their recorded deltas exactly once. Resetting resolved sources preserves the
  size reached by processed events, not the later live queue size.
- An append event carries its insertion index and count. The source timeline
  inserts exactly those placeholder slots; observers must not infer the delta
  from the live queue size, which may already include later removals or appends.
- Replacing a tail the app queued automatically with items the user picked
  sends the removal before the append. The timeline and the shuffle backup
  have to lose that tail as well.

When you change how the queue is modified, keep the queue, the media-source
timeline and the list on screen in step.

### Playlist completion lifetime

`MediaSourceManager` is owned by the application's main thread. Replacing a
queue manager or destroying the player permanently retires it before cancelling
its subscriptions or stopping/releasing ExoPlayer. Disposal is idempotent.
Retired managers reject playlist synchronization and reload completions before
calling the shared playback listener, loading sources or mutating playback.
Blocking, initialization and source resets do not retire the current manager;
the processed queue/timeline accounting above still applies.

Replacement timeline installation blocks discontinuity-driven navigation until
`setMediaSource` returns, independently of display state: receiver updates can
change that state while the manager is blocked. Its synchronous REMOVE describes
source replacement at the old engine index, not navigation back to that occurrence.
The installation guard is cleared in `finally`; normal engine navigation resumes
afterward. Player then prepares, changing blocked display state to buffering when
applicable. The
[2026-10-23 prepared-engine/SQLite validation](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/pvcpipe-device-smoke.md#combined-playback-integration-validation-2026-10-23)
records the real-engine regression and the API/receiver evidence limits.

Source completion callbacks can outlive Rx cancellation and engine release.
Retired managers therefore reject those callbacks before touching shared player
state. The [baseline investigation](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/playback.md#playlist-completion-lifetime)
keeps the engine versions and observed callback evidence separate.

### Recovery during pending navigation

`Player.setRecovery()` now captures a position only when the engine's current
media-item tag carries a `LoadedMediaSource` whose `getStream()` is the selected
`PlayQueueItem` instance. URL equality, matching numerical indices and
`currentItem` are not ownership evidence. Missing or mismatched source ownership
leaves the selected occurrence's existing recovery (including unset) unchanged.
`LoadedMediaSource` carries that tag through both initial and published child
timeline windows, preserving child media configuration, timing and period UIDs.
This is necessary because the engine reads prepared media items from the timeline,
not the wrapper's `getMediaItem()`. Extras are inspected without an unchecked
loaded-source cast: placeholder and failed-source clocks cannot authorize capture.
Aligned capture retains the existing local/active-receiver clock and clamping.

### Initial cast preparation ownership

Preparation belongs to the exact queue-occurrence instance, resolver lifetime,
connection and generation. Navigation and missing/failed metadata invalidate
obsolete work before a callback can load or hand off. An obsolete callback closes
only its own resolver. A temporarily blocked/null `currentItem` is not navigation.
Same-occurrence replacement failure restores established receiver ownership and
the latest pending seek/speed/play intent; switching receivers clears that tag.

### Equal-index history snapshot ownership

Equal indices and URLs do not establish clock ownership. Progress capture also
requires the engine tag to match cached metadata, rejecting an incoherent snapshot
before accepting recovery or a write. Ownership is checked at acceptance;
accepted history writes remain application-owned and ordered through navigation
and destruction. Disabling persistent history does not disable queue recovery.

### Cast synchronization liveness

Delivered metadata must identify the selected occurrence by instance. Once
synchronization makes source metadata coherent, casting retries preparation,
including when returning to an already-current occurrence. Duplicate suppression
covers only valid pending/established work. A metadata-clear during blocking is
not synthesized failed-source delivery.

### Active-cast checkpoint attribution

A cast clock requires the exact occurrence and usable receiver time, a carried
owned checkpoint or owned sender intent. Reset zeros and load/state/duration
emissions alone do not prove provenance. Explicit seek invalidates pre-seek
samples. Recovery/history checks combine this guard with source/metadata ownership;
disconnect leaves pending recovery untouched when a local override is unowned.
These policies do not resolve the separate initial source-start concern.

The [dated ownership/recovery records](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/playback.md#recovery-during-pending-navigation--corrected-2026-10-23)
distinguish mocked scheduling from prepared-engine, SQLite and hardware evidence.

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
- Progress recording updates the queue's recovery position from the content
  position even with watch history off, but only when the selected occurrence owns
  the engine source, its tag matches the captured metadata, and media indices agree.
  Database progress uses the playback position and requires watch history to be
  on. `Player.setRecovery()` shares the loaded-source occurrence check, retaining
  its separate playback-clock capture for source reloads.
  Active casting additionally requires the selected occurrence's cast clock to
  have the provenance described under active-cast checkpoint attribution above.

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

## Validation

`make ci` includes JVM playback policy and controlled protocol tests.
`app/src/androidTest/java/org/schabi/newpipe/player/` contains separate prepared
engine, session, history and controlled-receiver checks; inspect their guards and
[device safety](building.md#testing-on-an-existing-installation) first. A protocol
acknowledgement, passing mock or assembled APK does not establish receiver picture,
audio, cold startup or automotive UI. See [the action plan](README.md#action-plan)
for deferred and hardware-dependent verification.
