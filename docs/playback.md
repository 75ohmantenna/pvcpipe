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

[`CastPlayback`](../app/src/main/java/org/schabi/newpipe/player/cast/CastPlayback.java)
owns the handoff, asynchronous preparation, remote clock, and per-load
resources for one player service.
[`FCastSession`](../app/src/main/java/org/schabi/newpipe/player/cast/FCastSession.kt)
adapts the official `org.fcast:sender-sdk:0.6.1`. Native SDK calls run off the
main thread, callbacks return to it, and connection generations discard stale
events. The SDK supports negotiated v2/v3 and secure v4 connections.
PVCPipe retains playback state and duration across loads because the SDK can
deduplicate unchanged receiver values; advancing receiver progress can confirm
a load without another Playing event. Matching acknowledgements teach a
connection that it echoes the sender's sources; subsequent loads gate updates
until their own acknowledgement. Without such echoes, readiness relies on
playback evidence, including the observed SDK-v3 connection to Android Receiver
2.1.5 and v4's intentionally unacknowledged own loads. While a load is pending,
these peers cannot distinguish old-source progress from new-source progress.

SDK 0.6.1's v3 `Initial`/`PlayUpdate` source callbacks synthesize Playing without
updating its cached receiver playback state
([release transport source](https://gitlab.futo.org/videostreaming/fcast/-/blob/android-sender-sdk-v0.6.1/sdk/sender/fcast-sender-sdk/src/fcast.rs#L1633-1800)).
If that cache already says Paused, the next receiver Paused snapshot is suppressed.
For a paused startup, the session therefore enforces Pause and exposes Paused
immediately; it does not adopt synthetic Playing as playback intent or wait for a
corrective callback. This freezes the application clock and retains paused intent
for another replacement or receiver switch. Explicit Resume cancels startup pause
enforcement; explicit Pause during preparation re-arms it. The latest explicit
request survives an early Paused snapshot: a first matching source echo re-arms
startup enforcement from that request, rather than losing it before the SDK's
synthetic Playing. Before echo support is learned, that first matching echo is
still a startup boundary even if earlier playback observations were accepted:
remote Play before it cannot be distinguished from load autoplay, so the latest
explicit sender request wins at the echo. Repeated acknowledgements do not re-arm
enforcement. After startup, receiver Playing/Paused transitions remain authoritative;
a later rebuffer does not re-arm startup enforcement. v2 has no such synthetic
source callback, and v4 does not use this state-deduplication path.

The paused-startup regressions in `CastPlaybackTest` exercise the actual session
policy and playback controller with controlled preparation scheduling and clock
time. Android effects, media resolution and SDK/receiver transport are mocked;
v2/v4 cases exercise startup receiver observations, not v3 synthetic callbacks.
These JVM checks and tagged SDK-source evidence do not establish real receiver
picture, audio or transport timing.

v2 has no explicit end event. PVCPipe reports completion once for a
playing-to-idle transition near a known duration, or the Android receiver's
playing-to-paused transition at the known end position. SDK-v3 connections
without an observed own-source echo also permit this compatibility inference;
v4 and source-echoing v3 connections require explicit completion. Without
source/end notifications, a remote Stop near the end or a manual pause/seek
exactly at the duration can be indistinguishable from natural completion.

The sender SDK is MIT-licensed. Its attribution and the Java Native Access
(JNA) attribution are available in the app's **About → Licenses** tab.

[`FCastDiscovery`](../app/src/main/java/org/schabi/newpipe/player/cast/FCastDiscovery.kt)
browses only `_fcast._tcp` with Android NSD while the picker is open. It keeps
resolved addresses and TXT security records. A tracking-level loss withdraws
the receiver from the picker but retains the callback so later updates can
restore it. Browse-level loss or picker closure ends tracking; restarting
discovery clears stale rows. Closing the picker releases callbacks and the
multicast lock. Android 17/API 37 requests local network permission only
after the user opens casting. Denial leaves an explanation and an explicit
permission action, without starting discovery.

[`CastMediaResolver`](../app/src/main/java/org/schabi/newpipe/player/cast/CastMediaResolver.java)
uses the selected extracted streams, not the public webpage URL. It serves
HLS/DASH manifests and media through
[`CastMediaServer`](../app/src/main/java/org/schabi/newpipe/player/cast/CastMediaServer.java)
on the connection's local address. This keeps upstream requests and required
service headers on the phone, including requests for IP-bound media.
YouTube uses the existing extractor DASH creators and
`YoutubeHttpDataSource`, including its POST-based transport. Supported
separate audio and video tracks are combined in one receiver manifest;
PVCPipe does not silently omit the selected audio.

The HTTP server uses tokenized allowlisted routes, bounded request workers,
manifest sizes and route counts, plus byte-range/HEAD support. Initial
preparation fails if all required manifest and media routes exceed the
resource budget; runtime routes use bounded least-recently-used eviction for
long-running live streams. Manifests published during preparation retain their
routes for refresh and replay while old segment routes can expire. Invalid
DASH templates consume no route capacity. DASH XML nesting is limited to 64
levels before recursive transformations run. Request lines and headers have a
15-second absolute deadline measured from connection acceptance, independent
of media-body streaming. Only GET, HEAD and OPTIONS are accepted. Receivers
cannot select arbitrary upstream URLs, and their cookies and authorization
are not forwarded.
The server lives for the cast, with a foreground media notification and a CPU
wake lock. Disconnect, error or shutdown cancels preparation and closes media
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
adaptive progressive, OTF and post-live streams with extractor index metadata.
Single-file DASH `SegmentList` ranges can use an implicit representation base
URL; the proxy preserves that media resource rather than substituting the MPD.
The proxy does not transcode or remux media. Arbitrary split progressive
tracks without index metadata, replacement of embedded audio in a muxed
stream, and unsupported DASH alternatives/templates fail explicitly.
Receiver codec support still determines whether a source plays.

Local subtitle, resize, pitch and silence-skipping controls do not control the
receiver. Subtitle and resize controls are disabled while casting; the normal
speed menu is available when the receiver advertises speed support. The
media-session connector does not enable transport speed changes in its default
action set; use the player's speed menu instead.

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
The [regular-control smoke record](pvcpipe-device-smoke.md#fcast-regular-player-controls-2026-10-17)
covers the interaction cutover. Earlier
[Pixel/receiver](pvcpipe-device-smoke.md#fcast-pixel-6a-and-amazon-receiver-2026-10-17)
and [local emulator](pvcpipe-device-smoke.md#fcast-local-emulator-smoke-2026-10-17)
records preserve their original transport/media results and limitations.
The [0.2.0 release review](pvcpipe-device-smoke.md#pvcpipe-020-release-review-2026-10-18)
records adversarial fixes and controlled app/protocol checks. A later
[receiver recovery record](pvcpipe-device-smoke.md#fcast-receiver-recovery-2026-10-18)
documents real TV picture and audio after a receiver reboot, without an FCast
source change. None of these dated runs verifies every current source,
receiver or protocol version.

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

Rx cancellation does not cancel ExoPlayer's separately retained completion
actions. In the resolved ExoPlayer **2.19.1**,
[`ConcatenatingMediaSource.releaseSourceInternal()`](https://github.com/google/ExoPlayer/blob/r2.19.1/library/core/src/main/java/com/google/android/exoplayer2/source/ConcatenatingMediaSource.java#L525-L539)
dispatches outstanding actions, and
[`HandlerAndRunnable.dispatch()`](https://github.com/google/ExoPlayer/blob/r2.19.1/library/core/src/main/java/com/google/android/exoplayer2/source/ConcatenatingMediaSource.java#L1043-L1055)
posts them to the supplied handler. Mutations
[`without a playback handler`](https://github.com/google/ExoPlayer/blob/r2.19.1/library/core/src/main/java/com/google/android/exoplayer2/source/ConcatenatingMediaSource.java#L601-L619)
(including after release) can also post completions. Removing queued handler
callbacks is cleanup only: the permanent ownership check also rejects actions
already dequeued or posted during/after cleanup and release.

`MediaSourceManagerTest` controls Rx and Android delivery independently. It runs
real queue, manager and ExoPlayer playlist methods for stale synchronization,
reload, release-time dispatch, post-release mutation, same-URL replacement,
repeated disposal and valid current-manager work. Android handlers/loopers,
the playback listener and child media sources are mocked; prepared-source
completion registration and release are invoked directly rather than running
a decoder/playback thread. These are JVM/framework-source checks, not device or
receiver playback evidence. Accepted history writes retain their independent
lifetime described below.

### Recovery during pending navigation — corrected 2026-10-23

Finding **4** from the 2026-10-22 audit was reproduced against unchanged
post-#137 master (`ee76a5e539fe2a0474664543a54e6a6a241eea79`), using a disposable
worktree with separate build outputs and no build-cache reuse. Quality and audio
changes copied A's 45-second engine clock into selected occurrence B; a standalone
controlled JVM scenario also observed the subsequent seek to 45 seconds instead
of B's intended 12 seconds.

`Player.setRecovery()` now captures a position only when ExoPlayer's current
media-item tag carries a `LoadedMediaSource` whose `getStream()` is the selected
`PlayQueueItem` instance. URL equality, matching numerical indices and
`currentItem` are not ownership evidence. Missing or mismatched source ownership
leaves the selected occurrence's existing recovery (including unset) unchanged.
`LoadedMediaSource` carries that tag through both initial and published child
timeline windows, preserving child media configuration, timing and period UIDs.
This is necessary because ExoPlayer reads prepared media items from the timeline,
not the wrapper's `getMediaItem()`. Extras are inspected without an unchecked
loaded-source cast: placeholder and failed-source clocks cannot authorize capture.
Aligned capture retains the existing local/active-receiver clock and clamping.

`PlayerRecoveryTest` holds queue selection delivery on a `TestScheduler`, calls
the actual selection, quality/audio, recovery and synchronization methods, and
checks stored recovery plus the eventual seek/default-position command and
recovery consumption. It covers same-URL distinct occurrences, existing/unset
recovery, equal-index stale sources, missing tags and valid aligned capture with
watch history disabled. The first 11-check harness exposed eight failures on the
unchanged baseline, but its wrapper-only media items did not establish prepared
timeline ownership. Fresh review caught the missing timeline propagation and an
unsafe extras cast; both were reproduced before correction.

The strengthened 18 checks use real initial/published `SinglePeriodTimeline`
windows and caller dispatch, including direct aligned capture without a prior
history write, placeholder/failed extras, and active-receiver clock boundaries.
All pass. A repeated standalone quality/audio scenario using prepared windows
observes 45-second storage/seek on unchanged master and the intended 12 seconds
after the correction.

These are controlled JVM checks, not device playback evidence. ExoPlayer decoding,
Android effects, resolution and manager reload are mocked; the delayed readiness
callback stands in for the manager. Child source preparation is controlled rather
than running a decoder thread; source publication, timeline windows, queue objects,
Player methods and disabled-history policy are real. Cast transport is mocked.
The separate history progress path still used its index-only guard after this
correction; the later equal-index correction below covers that acceptance path.

### Initial cast preparation ownership — corrected 2026-10-23

Finding **5** from the 2026-10-22 playback concurrency audit was revalidated
against unchanged post-#138 master
(`21b1a6c9bd3a7aea2d89ad621d69ab626bef9807`) in a disposable worktree with
separate build outputs. Existing cast checks passed before adding regressions.
The regression/observation run disabled build-cache reuse.

The historical defect was initial A preparation surviving selection of failed B.
`onQueueItemChanged()` invalidated only active casts; `loadCurrent()` returned
before cancellation when B had no `StreamInfo`. A's completion checked generation
and connection, but not its captured occurrence. The original audit baseline was
`29018edf312868bbd2634bc5c00b1e73bc69b3f1`; the finding-4 correction did not fix it.

Revalidation delivered B's metadata, rather than omitting that callback:
`FailedMediaSource` builds an `ExceptionTag` with empty stream info.
`Player.onEvents()` stores that tag and calls `casting.onSourceChanged()` even
without stream info. Known extraction/resolution failures publish a two-second
silent timeline; unrelated failures instead propagate a source-refresh error.
A successful B normally cancels A when its metadata starts replacement
preparation, which is why successful-metadata delivery alone does not reproduce
the failed-selection defect.

Preparation now belongs to a queue-occurrence instance regardless of active
handoff state. Occurrence changes invalidate its generation and close its resolver.
Missing/failed metadata also cancels preparation. Success and failure callbacks
must retain the captured occurrence, generation, resolver lifetime and connected
session before affecting the operation; stale callbacks close only their captured
resolver, never a newer request's resources. Same-occurrence quality/audio
replacement and forced receiver reload retain their existing pending controls.
Queue-manager blocking temporarily clears `Player.currentItem`; that absence is
not navigation and cannot authorize a preparation or handoff. Active failed-source
waiting keeps the remote clock frozen. The existing URL-based source-clock
continuation heuristic is unchanged; URL equality never authorizes preparation
ownership. This correction does not make `currentItem` an engine-source identity.
An accepted handoff records the receiver's established tag separately from the
pending tag. Same-occurrence placeholder/failed metadata cancels only preparation,
restoring that established tag so receiver controls and replacement clock survive.
If replacement paused that established source, cancellation reapplies the latest
seek, speed and play/pause intent to it before exposing an advancing clock.
Changing occurrence, switching receivers or returning to the device clears the
established tag. A new receiver without a load keeps its pending clock frozen and
does not accept playback commands intended for the old receiver.

Sixteen durable `CastPlaybackTest` regressions cover failed B metadata followed by
late A success/failure, distinct same-URL occurrences, same-occurrence failed
metadata, completion without the selection notification, newer-preparation cleanup,
valid initial handoff and successful B metadata replacement. The first nine
regressions yield five failures on unchanged production code. Fresh review then
identified candidate regressions around temporary null occurrences, source-clock
continuation after removal, and active failed-source clock freezing. Four added
checks reproduced those candidate failures before correction, including real
block/events/synchronization with pending seek/pause/speed controls. A second fresh
review found same-occurrence empty metadata losing established receiver control;
that regression was reproduced before correction. Its strengthened check starts
with a real handoff, delivers placeholder and failed metadata, cancels replacement
preparation, verifies receiver controls and retries the same replacement tag at
the retained remote position. A third review identified playing-intent restoration
and receiver-switch tag lifetime defects; both were reproduced before correction.
Their checks verify receiver seek/speed/resume after canceled playing replacement,
retry position after elapsed time, and frozen pending controls for a new empty
receiver. Retrying the identical canceled tag on that new receiver was also
reproduced before correcting duplicate-tag suppression: only pending or established
work can suppress an unchanged tag. All 58 cast checks pass after correction.
The observation harness recorded one receiver load and one local handoff before
the fix, versus zero of each afterward, with A resolving after B's metadata in
both runs. The obsolete resolver closed and preparation became inactive afterward.

The harness uses real queue occurrences, `FailedMediaSource` metadata,
`Player.onPlaybackSynchronize()`, `Player.onEvents()` and `CastPlayback`, with
controlled Rx scheduling. ExoPlayer delivery/decoding, source preparation,
extraction/resolution, Android effects/error logging, metadata history/UI effects
and SDK/receiver transport are mocked; no device or receiver playback is established.

### Equal-index history snapshot ownership — corrected 2026-10-23

After removal/reindexing, a selected occurrence and ExoPlayer's still-loaded
occurrence can share a numerical index. Quality/audio changes call
`saveStreamProgressState()` before `setRecovery()`: the former's index-only
acceptance could replace selected recovery before the latter rejected the old
source. Recovery uses content position; persistence receives playback position
and cached metadata, not the selected queue item's metadata.

History progress capture now reuses `setRecovery()`'s exact loaded-source
occurrence check. The engine tag must also be the cached metadata tag, so a new
engine clock cannot be paired with metadata awaiting aggregate event delivery.
Missing ownership or incoherent metadata invalidates progress before recovery
mutation or write acceptance. URL equality and equal indices cannot authorize
capture. Aligned local and active-cast captures retain their clocks; active-cast
eligibility still uses the local source guard, not a new receiver-ownership check.
View/completion observations retain their metadata-based interpretation.

Ownership is checked synchronously, not when persistence finishes. Accepted
writes remain application-owned, ordered ahead of subsequent resume lookups,
and survive navigation and player destruction. Disabled persistent history still
permits valid queue recovery. Shutdown retains its final progress checkpoint and
the existing subsequent `setRecovery()` capture.

Controlled JVM observation on unchanged post-#139 master
(`fbbd843c695091e35f213862856858da237596ab`) reproduced selected recovery changing
from 12 seconds to the old source's 31-second content position, then an actual
seek to 31 seconds and recovery consumption. The old occurrence's recovery
remained unset. The history adapter received the old metadata and 45-second
playback position: that is an old-stream checkpoint, not evidence that a distinct
selected stream's persistent state was overwritten. Same-URL occurrences share
one persistent stream identity. `saveStreamState` upserts stream metadata and a
valid stream-state position; it does not increment views or write stream-history
records. A separate engine-selected/cached-metadata mismatch regression checks
rejection until actual aggregate metadata delivery restores coherent capture.
The rejected equal-index snapshot can contain a correctly attributed A checkpoint;
dropping it together with selected recovery is intentional, retaining progress's
existing all-or-nothing acceptance model rather than adding a second write path.
The cached-metadata mismatch is different: a synchronous recovery seek can expose
B's clock before aggregate metadata delivery, so the coherence check also prevents
A metadata from accepting B's resume clock. A regression drives the actual
synchronization → seek → discontinuity → aggregate-event sequence.

The desired-behavior regressions call real quality/audio, selection, progress,
metadata delivery, synchronization and destruction methods with controlled
scheduling. They cover existing/unset recovery, enabled/disabled history,
same/distinct URLs, actual database-facing arguments, content/playback position
separation, aligned local/cast clocks, final shutdown, accepted writes completing
after navigation/destruction and resume ordering. Source windows and ownership,
queue objects, `PlaybackHistory` and its Android adapter are real. Android
plumbing/preferences, ExoPlayer decoding/delivery, source-manager reload, cast
transport and the database-facing record manager are mocked. Persistence ordering
also uses a controlled history environment; this is not SQLite or device-playback
evidence. Baseline and corrected observations use separate worktree build outputs
without build-cache reuse. Disposable observation code is not retained.

### Cast synchronization liveness — corrected 2026-10-23

The follow-up raised during PR #139 and left separate through PR #140 was
reproduced on unchanged post-#140 master
(`8117bd5ee6229058d2c1a64cff851ae4ff6b295c`), using separate baseline/fix worktrees
and build outputs without build-cache reuse. Removal or local automatic advancement
selects B; the engine delivers B's preloaded source tag while `currentItem` is
still A. The tag's `LoadedMediaSource.getStream()` identifies B by instance,
including distinct occurrences with identical URLs. Previously, casting attributed
this metadata to A. When synchronization followed, it invalidated that preparation,
but neither it nor an unchanged-tag aggregate event restarted B. The established-cast
variant also stalled. Initial removal/advancement and active removal desired-behavior
regressions failed on the unchanged baseline; the reverse-order control passed.

`Player` now notifies casting when delivered loaded-source ownership matches
selection, invalidating A before its late resolution can hand off. This binds
pending controls to B without pretending that `currentItem` has synchronized.
Preparation requires coherent selection, synchronized occurrence, actual engine
source ownership and cached/engine tag identity. After synchronization's recovery
or default seek, the player retries cached metadata through the existing source
notification. Synchronization-first delivery still waits for B's actual metadata;
same-URL A metadata cannot authorize B. No synthetic metadata event, timer or
manager retry loop is added.

Generation, resolver and connection lifetime checks remain in force at completion,
with source coherence checked again. Repeated notifications for pending or
established work do not create another load. An occurrence notification binds the
new lifetime once, so the later synchronization does not reset its controls.
Explicit pending seek intent takes precedence over the engine's preview position
when deferred preparation starts; pause/resume and speed retain their latest
values. Navigation resets that intent. Failed metadata cancels work; disconnect
and receiver changes retain their existing cancellation/resource rules.
Recovery capture and history acceptance/write lifetime algorithms are unchanged.
Retry also runs when synchronization returns to the already-current occurrence:
selecting an unloaded neighbor can invalidate an in-flight completion without
changing either `currentItem` or engine metadata. Returning must retry the rejected
request, not depend on another metadata event. Conversely, synchronization does
not synthesize failed-source delivery from metadata temporarily cleared by blocking;
the established receiver's buffering clock remains frozen.
Removal now synchronizes through `MediaSourceManager` after ExoPlayer's timeline
completion, including when B is already preloaded and no further source load would
produce a callback. The completion checks manager lifetime and readiness before
synchronizing. A queue-edit notification alone is not engine-completion evidence.
Removing an unloaded selection also uses this completion to retry unchanged A.

Twenty-six added cast regressions plus one manager-retirement regression cover
both callback orders for removal and automatic advancement in initial and established
casting, same-URL occurrences, repeated pending/loaded notifications, deferred controls,
and navigation/disconnect/failure before eligibility. Advancement drives actual
`Player.onPositionDiscontinuity()` and `PlaybackHistory.onDiscontinuity()` queue
policy; metadata and synchronization drive the actual Player methods. An existing
distinct-URL removal clock regression now also waits for valid B synchronization.
An executable disposable callback probe observed zero B loads after synchronization
on baseline versus one on corrected code, without a second metadata callback, for
both initial and established removal and actual automatic-discontinuity advancement.
Fresh review additionally reproduced initial/replacement retry loss after returning
to an unchanged occurrence and a block/synchronization-only clock-unfreeze defect.
Of that total, five first-review regressions cover those corrections and actual
recovery/default-seek position changes, including simulated reentrant metadata
delivery during a mocked engine seek.
A disposable executable probe observed rejected old loads followed by exactly one
retry on returning to A, and a frozen 42000 ms buffering clock after synchronization
without metadata. Source-coherence rejection remains required throughout.
Two second-review regressions cover removal of an unloaded last selection. Their
final fixtures use the real manager completion, not the superseded queue-edit retry.
Four third-review regressions combine the real manager, playlist, loaded-source
tags, Player REMOVE discontinuity, history queue policy, metadata and synchronization
for a complete two-item queue with no neighbor loading to supply synchronization.
All four failed before adding removal completion; both callback orders now load B
once in initial and established casting without directly invoking synchronization.
An executable probe observes B metadata while `currentItem` is A, then the real
manager completion establishes B and loads it once without another metadata event.
The manager-retirement regression rejects late removal completion after disposal.

An inherited active-cast attribution concern remains separate: history eligibility
still uses local-source ownership rather than authoritative receiver ownership.
Source review indicates that pausing between B metadata and B synchronization can
checkpoint B's reset/pending cast position (zero without explicit seek); the
baseline could instead credit B with A's remote clock, then zero after synchronization.
The earlier ownership reset changes the clock visible in this interval, not the
history acceptance algorithm or accepted-write lifetime. This is a reported
remaining concern, not an intentional recovery guarantee or SQLite observation.

These are controlled JVM observations, not proof of real ExoPlayer scheduling,
device playback or physical receiver decoding. Engine callback delivery/decoding,
extraction/cast resolution, Android effects/preferences/autoqueue, database writes,
and SDK/receiver transport are mocked. The managed-removal fixture uses real
successful source tags, wrappers and queue/manager policy. Its unprepared
`ConcatenatingMediaSource` posts removal completion to a mocked Android Handler;
the fixture holds that post to exercise both callback orders. It does not execute
the prepared playback-thread timeline path. Inspected resolved ExoPlayer 2.19.1
source separates these branches in `removePublicMediaSources`: prepared removal
uses `MSG_REMOVE`, timeline refresh and `MSG_ON_COMPLETION` before Handler dispatch;
unprepared removal posts directly. That ordering is source evidence, not a real
engine scheduling observation. Earlier controller-only fixtures use successful
source/metadata doubles. Controlled boundaries live in `CastPlaybackTestFixture`;
behavior assertions remain in `CastPlaybackTest`.
Prepared-window ownership remains covered by its existing real-source regressions;
no live decoder/receiver integration is claimed.

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
| FCast playback | [`CastPlaybackTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastPlaybackTest.java), [`LegacyFCastCompletionTest`](../app/src/test/java/org/schabi/newpipe/player/cast/LegacyFCastCompletionTest.kt), [`CastMediaTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastMediaTest.java), [`FCastDeviceTest`](../app/src/test/java/org/schabi/newpipe/player/cast/FCastDeviceTest.kt) |
| FCast media | [`CastManifestTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastManifestTest.java), [`CastInlineManifestTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastInlineManifestTest.java), [`CastMediaServerTest`](../app/src/test/java/org/schabi/newpipe/player/cast/CastMediaServerTest.java); on a device [`CastDashReceiverCompatibilityTest`](../app/src/androidTest/java/org/schabi/newpipe/player/cast/CastDashReceiverCompatibilityTest.java) |
| FCast discovery | [`FCastDiscoveryTest`](../app/src/test/java/org/schabi/newpipe/player/cast/FCastDiscoveryTest.java) |
| Queue requests | [`QueuePlaybackChoiceTest`](../app/src/test/java/org/schabi/newpipe/player/QueuePlaybackChoiceTest.java), [`PlayerIntentTest`](../app/src/test/java/org/schabi/newpipe/player/PlayerIntentTest.java) |
| Recovery ownership | [`PlayerRecoveryTest`](../app/src/test/java/org/schabi/newpipe/player/PlayerRecoveryTest.java) |
| Queue events and timeline | [`PlayQueueTest`](../app/src/test/java/org/schabi/newpipe/player/playqueue/PlayQueueTest.java), [`MediaSourceManagerTest`](../app/src/test/java/org/schabi/newpipe/player/playback/MediaSourceManagerTest.java) |
| Audio | [`PlaybackAudioTest`](../app/src/test/java/org/schabi/newpipe/player/audio/PlaybackAudioTest.java), [`PlaybackAudioFocusProtocolTest`](../app/src/test/java/org/schabi/newpipe/player/audio/PlaybackAudioFocusProtocolTest.java), on a device [`PlaybackAudioIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/audio/PlaybackAudioIntegrationTest.kt) |
| History | [`PlaybackHistoryTest`](../app/src/test/java/org/schabi/newpipe/player/history/PlaybackHistoryTest.java), [`PlaybackHistoryPlayerLifecycleTest`](../app/src/test/java/org/schabi/newpipe/player/history/PlaybackHistoryPlayerLifecycleTest.java), on a device [`PlaybackHistoryIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/player/history/PlaybackHistoryIntegrationTest.kt) |
| Broadcasts | [`PlayerBroadcastsTest`](../app/src/test/java/org/schabi/newpipe/player/broadcast/PlayerBroadcastsTest.java), on a device [`AndroidPlayerBroadcastsTest`](../app/src/androidTest/java/org/schabi/newpipe/player/broadcast/AndroidPlayerBroadcastsTest.kt) |
| Errors | [`PlaybackErrorsTest`](../app/src/test/java/org/schabi/newpipe/player/error/PlaybackErrorsTest.java) |
| Listeners | [`PlayerListenersTest`](../app/src/test/java/org/schabi/newpipe/player/event/PlayerListenersTest.java) |
| Detail screen | [`DetailStackTest`](../app/src/test/java/org/schabi/newpipe/fragments/detail/DetailStackTest.java), [`DetailOrientationTest`](../app/src/test/java/org/schabi/newpipe/fragments/detail/DetailOrientationTest.java) |
| Proof tokens | [`WebPoTokenProviderTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/WebPoTokenProviderTest.kt), [`WebPoTokenProviderReplacementTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/WebPoTokenProviderReplacementTest.kt), [`PoTokenWebViewLifecycleTest`](../app/src/test/java/org/schabi/newpipe/util/potoken/PoTokenWebViewLifecycleTest.kt), on a device [`PoTokenWebViewIntegrationTest`](../app/src/androidTest/java/org/schabi/newpipe/util/potoken/PoTokenWebViewIntegrationTest.kt) |
