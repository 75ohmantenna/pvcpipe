# Media3 migration — 2026-10-26

This record concerns the migration branch based on
`0dd43646c` (Stetho replacement). It does not update the meaning of earlier
ExoPlayer 2.19.1 or physical-receiver observations.

## Implementation

Production playback uses AndroidX Media3 1.11.1. Standalone ExoPlayer modules and
the app's direct legacy Media dependency and old support-media imports have been
removed.
Both runtime dependency reports resolve Media3 modules to 1.11.1 and contain no
`com.google.android.exoplayer` modules. The October 27 correction adds an API
26/27 browser delegate using Media3's bundled legacy transport types. Media3
session retains its transitive
`androidx.media:media:1.7.0` interoperability dependency; it is not force-excluded.
The extractor, FCast SDK,
application IDs, SDK levels, preferences, Room schema and app version are unchanged.

The service exposes one application-player adapter and library session. It
retains authenticated internal starts, the private local binder, trusted browser
validation, asynchronous queue resolution, source-occurrence recovery ownership,
and explicit notification/foreground policy. The managed-source playlist keeps
completion callbacks. Load control keeps the previous 50-second min/max buffer
and 2.5/5-second playback/rebuffer thresholds, including local files.

Media3 unstable APIs are opted into at the classes that use them. The custom
video surface workaround and stream transport implementations remain. The
independent local-file player's construction, UI and silence-skipping API have
also migrated.

Optional sidecar subtitles now use Media3 `SingleSampleMediaSource`, with failed
loads treated as end-of-stream. Both standard and custom-video renderer factories
opt into Media3's corresponding renderer subtitle decoder. This preserves lazy
selection and prevents a missing optional subtitle from blocking primary media.
The path is deprecated/experimental in Media3 1.11.1 and needs reassessment on
future engine upgrades. SubRip's extractor MIME type (`text/srt`) is normalized
to Media3's `application/x-subrip`; unsupported parser formats are omitted.
Language, roles and labels remain attached. The initial migration used eager
`SubtitleExtractor` sources; the October 27 review below records why that changed.

## Baseline and JVM verification

- The original `make ci` passed before source changes. The initial checkout and
  APK outputs were recorded before migration.
- The migrated app's 1,039 JVM tests pass. The first migrated run's 41 failures
  all came from one controlled source-publication fixture using a retired
  `prepareSource` overload. It now calls Media3's active overload with a controlled
  bandwidth meter; caller registration and timeline publication remain real.
- Existing queue/recovery/history/cast assertions were retained. Package changes
  and adapting a test fixture do not themselves establish real-engine or receiver
  scheduling.

## Device and upgrade verification

Validation uses newly created disposable API 26 and API 37 emulators. The
connected phone's installations and data are not used. APK installation and direct
instrumentation are targeted by serial; `connectedDebugAndroidTest` is not used.
The normal AndroidJUnitRunner is retained. Prepared-engine/history fixtures use
unique preferences/databases/cache directories and restore their injected globals.
The real service-lifecycle suite additionally requires
`-e media3DisposableEnvironment true` because it starts/stops the app service.

Before upgrade, the API 26 installation ran the original source-construction
suite (11 tests), then played the public Big Buck Bunny YouTube test video with
the legacy engine. It contained one stream/history/resume entry and a legacy cache
with two keys, 18 committed spans, and 7,852,894 cached bytes. The cache UID,
SQLite index and file metadata were captured after stopping the app; raw media
URLs are not included in this record.

The cache-upgrade test accepts SHA-256 digests of the original sorted keys and
`filename:length` entries through instrumentation arguments. It reopens the cache
through production `PlayerDataSource`, requires positive committed media bytes,
and compares both digests. It skips when no prepared legacy fixture is supplied.
It does not introduce a legacy ExoPlayer test dependency.

The in-place cache check passed: Media3 retained both key and committed-span
hashes after `adb install -r`, including all 7,852,894 bytes. A separate semantic
SQLite comparison found every original `streams`, `stream_history` and
`stream_state` row unchanged after upgrade and controlled service playback. The
new controlled stream added its own rows rather than replacing the original.

The API 26 migrated app also rendered the same YouTube video. Native system
media commands paused, sought while paused and resumed it; session metadata
reported Big Buck Bunny/Blender and the two configured custom actions. This is
an emulator video/rendering observation, not an audible-output or receiver test.

Combined instrumentation exposed two fixture assumptions: history tests left the
singleton pointing at a closed in-memory database, and an audio-effect test
assumed that no engine-generated session callback preceded its controlled one.
The history fixture now restores its previous singleton, and the audio test
checks the exact controlled open/close suffix relative to prior callbacks.

Adapter checks additionally cover external paused-engine seek events, selected
recovery during pending seeks, idle modern/framework browser connections, rejected
URI playback, and Close canceling held browser preparation before a late start.
Clock values are captured per session state; Media3 extrapolates only while
playing. Reading a mutable clock from both the old and new snapshots would hide
position discontinuities.

The first minified mode-switch smoke check exposed a missing lifecycle case:
a cached session timeline could outlive the selected queue item and crash while
reading its recovery. The adapter now retires the timeline when the queue's
message bus or selected item is absent, and guards the position lookup itself.
The added real-engine regression covers emptying, disposing, reinitializing the
same queue and replacing it, without relying on a metadata refresh. The earlier
passing suites did not establish this case.

After the correction, the exact final signed/minified APK rendered Big Buck Bunny
in the main player and a visible popup on API 26. System pause held through a
forward seek (96,755 to 107,488 ms), then playback resumed. Background playback
continued on Home; switching to popup retained nonzero progress and coherent
Big Buck Bunny/Blender session metadata. These were normal UI/router actions and
system media commands, not injected service fixtures. The headless emulator
does not establish audible output or physical receiver behavior.

The final `make ci` passed after the subtitle and queue-retirement corrections, including the host
sanitizer tests, 685 recorded-fixture checks, build-source tests, style/dependency
order checks, all 1,039 app JVM tests, the extractor CI tests, lint, debug APK and
signed/R8-minified release APK. The separate instrumentation APK build passed.
The selected API 26 run scheduled 78 checks: 77 passed and the platform-browser
check was skipped by its API assumption; no checks failed. The positive legacy
cache-upgrade check passed separately.

All 78 selected API 37 checks passed after the lifecycle correction, including the native framework and compat
messenger browsers. Both emulator suites exercise real engine preparation,
history/recovery ownership, audio policies, service lifecycle, local-file UI,
generated HLS audio and decoded SubRip/WebVTT/TTML cues. The HLS fixture is bounded;
it does not establish live-edge scheduling on a real stream.

The initial API 37 run exited with a bind-application startup ANR before any
tests ran, while preinstalled Google services were also repeatedly timing out.
The successful run used the same APKs after disabling Google Play services,
Google search, Android System Intelligence and Photos only on that disposable
emulator. Google-service integration is not verified by that run. Later repeats also hit
the startup timeout with those services disabled, including an idle-host attempt.
The ANR trace stopped in ART decompressing DEX files before test execution, with
substantial scheduler wait. After the emulator settled, the same installed APKs
passed all 78 checks; no startup timeout or runner policy was relaxed.

Before the queue-retirement correction, the signed release baseline and initial
Media3 candidate were installed successively with
`install -r` on the same disposable API 37 environment. Each had one warmup and
three force-stopped launches. `am start -W` sometimes omitted `TotalTime` on the
baseline, and app-side memory dumps timed out, so the completed comparison uses
launch `WaitTime` and main-PID OS PSS (`dumpsys meminfo --local`) five seconds
after launch. All sampled activities reached `MainActivity`.

| Observation | Baseline runs | Media3 runs | Baseline / Media3 median |
| --- | --- | --- | --- |
| Launch wait, ms | 3,595; 3,252; 3,512 | 3,281; 4,151; 3,626 | 3,512 / 3,626 |
| Main-process PSS, KiB | 124,589; 117,000; 121,357 | 125,368; 125,662; 117,169 | 121,357 / 125,368 |

These are small emulator observations from the initial candidate, with background
Google services disabled. They are not a statistically meaningful benchmark, a
hardware performance guarantee or a measurement of the final lifecycle correction.

The baseline and initial signed Media3 candidate resolved Big Buck Bunny on API 37,
but video playback failed
through the emulator's `c2.goldfish.h264.decoder`: the codec reported buffer-pool
transport errors and `queueInputBuffer() failed as MediaCodec already in error`.
The original ExoPlayer 2.19.1 baseline reproduced the same failure after an
in-place reinstall. This comparison points to the emulator codec path; it does
not establish successful API 37 video rendering. The API 37 controlled audio,
subtitle and session checks remain separate positive results.

| APK | Baseline bytes | Media3 bytes | Change |
| --- | ---: | ---: | ---: |
| Debug | 27,780,626 | 28,754,484 | +973,858 (+3.51%) |
| Signed release | 13,909,109 | 14,414,299 | +505,190 (+3.63%) |

Both release APK signatures verify with certificate SHA-256
`14a4e83570c8d32855ad271c8eec1f5822ba225673c11111c4f653dc0bf996bb`.
The final release APK SHA-256 is
`cbde99c1600389b4aa954a2e1f819df7cd7bf3d943d1e793c68a4213dbfdf112`.
These compare the original `0dd43646c` baseline with the migration working tree;
they do not assert a runtime performance improvement.

The platform `android.media.browse.MediaBrowser` connected on API 26 but failed
to load children. Source inspection points to Media3's legacy bridge using an
unknown framework caller identity before API 28. This is an observed compatibility
defect, not an intended app contract. The platform-browser test runs from API 28;
a separate compat messenger client exercises the minimum-SDK legacy path. The
test client uses Media3's bundled restricted compat classes only in instrumentation;
the app does not add a direct legacy Media dependency. See the
[Media3 bridge implementation](https://github.com/androidx/media/blob/1.11.1/libraries/session/src/main/java/androidx/media3/session/legacy/MediaBrowserServiceCompat.java).
This was an unresolved defect in the October 26 candidate. The October 27
correction below restores native browsing through a compatibility delegate;
the earlier observations do not verify that later implementation.

## Repeating the controlled checks

Run `make ci` for the repository gate. Build instrumentation with
`./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest`.
Use a dedicated disposable emulator installation, install/update the debug and
test APKs there, then run the new migration checks directly:

```sh
adb -s <disposable-serial> shell am instrument -w -r \
  -e media3DisposableEnvironment true \
  -e class org.schabi.newpipe.player.history.PreparedPlayerSqliteHistoryTest,org.schabi.newpipe.player.mediasession.Media3ServiceIntegrationTest,org.schabi.newpipe.player.resolver.Media3SourceEngineIntegrationTest \
  org.seventyfiveohmantenna.pvcpipe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The larger selected suite also includes `PlayerRecoveryEngineIntegrationTest`,
`PlaybackSourcesIntegrationTest`, `PlaybackAudioIntegrationTest`,
`PlaybackHistoryIntegrationTest`, `PreparedMediaSourceManagerIntegrationTest` and
`CastDashReceiverCompatibilityTest`. The legacy-cache check additionally requires
a prepared positive pre-upgrade cache and its expected hash arguments. Do not
substitute `connectedDebugAndroidTest` or an existing user installation for this
disposable environment; see [the installation-preservation record](local-playlist-mutations.md).

## Outstanding release checks

The user confirmed that physical FCast testing must wait until later and that
Android Auto/Desktop Head Unit is unavailable. On October 27 the user explicitly
authorized independent Opus review, corrections and merging into master while
phone/FCast testing remains deferred. That authorization does not supply the
missing hardware evidence. Controlled FCast tests and emulators do not establish
physical receiver picture, audio, timing or automotive UI behavior.

The receiver follow-up should cover paused handoff, play/pause and paused seeks
from both normal and system controls, supported speed/volume, queue navigation,
quality/audio replacement, disconnect/play-here, and dropped-connection recovery.
Exercise receiver switching when a second receiver is available. Android Auto UI
and automotive-specific behavior remain unverified even when the framework
legacy-browser protocol tests pass.

The October 26 verification did not merge the change. The October 27 follow-up
below records the review and corrections for the newly authorized merge. App
version remains 0.2.4; tags and release publication are outside that follow-up.

## Independent Opus CLI review and corrections — 2026-10-27

Separate fresh read-only Opus CLI sessions (`claude-opus-5-5`) reviewed PR #158
and the independent feed fix in PR #159 against their common master baseline.
The parent agent checked the findings against implementation, the exact local
Media3 1.11.1 sources and controlled regression runs. A further fresh Opus
session reviewed a frozen snapshot of the playback corrections, including the
actual Media3 sources rather than relying on recollection of library internals.

- **Optional subtitle failure, confirmed:** the original candidate failed both
  an unselected HTTP-404 subtitle and a selected missing-file subtitle. The
  primary media could not reach ready playback. The lazy single-sample source
  and matching renderer decoding correct this. Malformed WebVTT bodies in the
  controlled cases already allowed playback; that part of the review's broader
  parsing-error claim was not reproduced.
- **Stale foreground start, confirmed:** delivering a stale authenticated
  browser intent through Android's actual `startForegroundService` caused an
  API 26 ANR: `did not then call Service.startForeground()`. The new handler
  satisfies the deadline and restores notification policy without creating an
  engine or cancelling a newer preparation. The regression waits through the
  real platform watchdog while a newer request remains pending.
- **Native API 26/27 browsing, confirmed on API 26:** the original candidate
  connected but failed to load children. The API-specific delegate uses the
  existing catalog, caller/UID validation and one Media3 platform session token.
  Native and compat clients now load the two root folders on API 26. API 27
  uses the same implementation branch but has no separate runtime result.
- **Duplicate legacy subscription work, confirmed by source/protocol:** the
  compat service already sends initial children. Its Media3 callback now
  acknowledges the subscription without loading and notifying again. Modern
  browsers retain their initial notification. The claim of an unavoidable
  second network request was overstated: `ExtractorHelper` also caches playlist
  results. Controlled tests check one initial compat callback and the retained
  modern initial notification.
- **Background foreground-promotion exception, unsubstantiated:** the review
  supplied no concrete non-exempt resume path. Tracing the current pause/cast
  policies did not establish one. This remains a hypothesis, rather than a
  reproduced regression or a reason to add a blanket exception handler.

The focused follow-up found no blocking regressions. Its service-lifetime
corner was valid: a stale start must not stop an otherwise unbound service
while a newer preparation is pending. The handler now preserves that started
service, and terminal preparation cleanup releases it when no player exists.
The strengthened regression checks the platform's `RunningServiceInfo.started`
state as well as future ownership. The extra compat-token binder round trip is
standard recovery through `COMMAND_GET_EXTRA_BINDER`, now noted in the bridge;
its public token still identifies the same Media3 session.

The first corrected candidate passed all 21 focused API 26 checks, including
native/compat browsing, caller validation, the real foreground-start deadline,
standard/custom renderer captions, selected/unselected missing and malformed
optional tracks, and native transport commands. These are emulator results;
physical FCast and automotive behavior remain unverified. The final combined-tree
verification follows below.

The review also mentioned exported player broadcasts and ignored foreground
starts already present on master. Those are separate baseline observations;
they were not established or changed by this correction's regression checks.

### Final combined-tree verification

After integrating master with merged PR #159 and applying all corrections,
`make ci` passed: the host checks, recorded fixtures, build-source and
style/dependency-order checks, all 1,044 app JVM tests, 610 extractor offline
tests (three additional cases skipped), lint, debug assembly and signed/R8
release assembly. Lint reported zero errors and 59 warnings.

The exact final debug and instrumentation APKs passed all 21 focused checks on
API 26. All 20 applicable checks passed on API 37; the API 26/27-only delegate
caller test is excluded there by its SDK annotation. Both runs include the
newer-preparation started-service ownership assertion and a stale start with an
existing paused player and hidden notification. The former catches the final
follow-up finding; the latter verifies that the player and notification policy
survive the deadline-handling path.

The first API 37 installation stalled while sharing host resources with lint.
An idle-host restart with 4 GiB configured memory installed both APKs but then
hit a process-startup ANR before any test ran. A Google app also failed startup,
and CPU pressure was near 100%. The guest exposed only CPU 0 despite configuring
four emulator cores. The final attempt used the same installed APKs after the
emulator settled and its display was put to sleep to reduce rendering load;
all 20 checks passed. App/startup watchdogs and test deadlines were unchanged.
These results do not establish foreground UI, audible output, physical receiver
behavior or successful API 37 H.264 video rendering.

The October 27 signed release APK is 14,417,979 bytes, version 0.2.4/code 1000204,
with SHA-256 `0f3d79862d062de4664cf6e2e05883b7f278794c4e5478802496d2a516bddc5e`.
Its verified release certificate SHA-256 remains
`14a4e83570c8d32855ad271c8eec1f5822ba225673c11111c4f653dc0bf996bb`.
The earlier October 26 fingerprints and observations remain historical. The
phone retains that previously installed FCast candidate; this review did not
update it or run phone/FCast tests. Physical FCast and Android Auto/Desktop Head
Unit checks remain deferred under the user's explicit merge instruction.

## October 27 physical FCast follow-up

The user subsequently authorized testing on their Pixel 6a (Android 17/API 37)
and an Amazon Fire TV running FCast Receiver 2.1.5-playstore, negotiating FCast
protocol v3. These observations supplement the earlier emulator and controlled
peer results; they do not verify v4 or automotive behavior.

The signed release exposed a defect that the debug instrumentation did not
exercise. R8 merged `MediaSessionPlayerUi` into `BackgroundPlayerUi`, while
`PlayerUiList` selects and removes components through `Class.isInstance` and
`Class.cast`. Entering video mode consequently removed the persistent session
publisher. Local video rendered and advanced, but Android's session initially
reported no playback metadata or progress and later retained stale cast state.
The earlier packaged code confirmed the merged identities.

The release rules now preserve the identities of `PlayerUi` subclasses. A fresh
minified build keeps the session publisher separate; on the phone, local playback
published its title, position and playing state. Pause, seek and volume commands
addressed to the receiver updated Android's session. `make ci` passed with this
rule. This is release-build and physical-phone evidence, not a new debug JVM
regression assertion. The shared class selection also serves popup and background
mode; their behavior was not separately verified on the phone in this run.

The corrected APK is 14,417,919 bytes, version 0.2.4/code 1000204, with SHA-256
`b48dea108f970bd598b810f77fb4c0dc31643c8161d0d0a2b059a185c6f498aa`.
Its DEX files embed R8 map ID
`cb91e17846704114a3f0a8be3aa99d2a4d301cfc2113f3b23c3a00e744332edd`,
matching the corrected mapping used to verify the separate UI classes.
Its signing certificate matches the preceding release. It was installed using
an update that preserved the installed package's credential- and device-encrypted
data directory identities, and the installed APK hash was verified. No package
was uninstalled, no app data was cleared, and no instrumentation ran on the
user's installation.

Observed results so far:

| Check | Physical evidence |
| --- | --- |
| Receiver discovery and connection | Android NSD discovered the receiver; the app connected through the official sender SDK. |
| Independent receiver baseline | A quiet, locally generated H.264/AAC MP4 was fetched and advanced from zero to ten seconds; the user confirmed seeing it play. |
| YouTube media delivery | Big Buck Bunny at 720p60 used the phone's generated DASH manifest and separate audio/video proxies; the receiver reported the full duration and advancing positions. The user confirmed both picture and sound on the TV. |
| Pause and paused seeks | The main-player seekbar moved the paused receiver to 92.422 seconds. Android's media transport then advanced it exactly ten seconds to 102.422 seconds, with matching paused receiver and system-session snapshots. |
| Speed | Selecting 1.25x reached the receiver. That sequence also changed orientation, so preservation of pause intent during a speed change needs a separate check. |
| Volume | The normal fullscreen volume gesture changed the receiver from 100 to approximately 39 percent, with matching receiver callbacks and Android remote-volume state. |
| Background playback | Receiver progress continued after Home, with the Android session still reporting remote playback at 1.25x. |

The first receiver loads remained on the receiver home screen despite an accepted
connection. The user rebooted the Fire TV and reopened FCast Receiver. The
independent baseline also required a temporary workstation firewall allowance
restricted to that receiver and diagnostic server port; the rule was removed
afterward. Those initial attempts are not evidence of successful media playback.

The phone subsequently became unreachable over Wi-Fi, interrupting further UI
checks. The receiver remained paused at approximately 264.156 seconds. After the
phone reconnected, its system session reported local playback paused at exactly
264.156 seconds. This verifies recovery from connection loss while already
paused; recovery from a loss during active playback still needs a separate check.
Paused initial handoff, quality/audio replacement, queue navigation, disconnect
and play-on-device position recovery also remain incomplete. A second physical
receiver and Android Auto/Desktop Head Unit remain unavailable. Do not infer
those results from the checks above.

## October 28 resumed verification

The same corrected APK hash was verified on the phone when testing resumed.
The receiver accepted another media load, but an independent protocol observer
received its source acknowledgement without fresh playback clock or duration
updates. The phone's paused session snapshot alone does not establish successful
paused handoff. That attempt remains incomplete; restarting and reopening the TV
receiver was requested before continuing the remaining receiver checks. After
the user reported it ready, an explicit Play command still produced no fresh
receiver playback samples. The independent H.264/AAC diagnostic also received
no media fetch request, with its narrow workstation firewall allowance active
and subsequently removed. A complete Fire TV reboot was requested; no additional
hardware check is counted as passed from these attempts.

A fresh, read-only Opus CLI review (`claude-opus-5-5`) found no concrete defect in
the release rule. It independently traced the earlier class merging through
`PlayerUiList` selection/removal and verified that the corrected mapping keeps
the relevant UI classes separate. The reviewer did not run builds or devices;
its documentation/evidence findings were checked against the actual snapshots
and APK metadata before updating this record.
