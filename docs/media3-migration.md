# Media3 migration — 2026-10-26

This record concerns the migration branch based on
`0dd43646c` (Stetho replacement). It does not update the meaning of earlier
ExoPlayer 2.19.1 or physical-receiver observations.

## Implementation

Production playback uses AndroidX Media3 1.11.1. Standalone ExoPlayer modules and
the app's direct legacy Media dependency and compat imports have been removed.
Both runtime dependency reports resolve Media3 modules to 1.11.1 and contain no
`com.google.android.exoplayer` modules. Media3 session retains its transitive
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

Sidecar subtitles use `SubtitleExtractor` and Media3's parsers, retaining language
and role metadata. The old single-sample source requires legacy decoding, which
Media3 disables by default. SubRip's extractor MIME type (`text/srt`) is normalized
to Media3's `application/x-subrip`; unsupported parser formats are omitted.
The controlled renderer checks use generated SubRip, WebVTT and TTML captions.
These standalone progressive subtitle sources prepare their tracks eagerly;
subtitle request timing and large multilingual catalogs need observation on real
streams. Media3's lazy sidecar helper is package-private.

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
Support for native framework browser clients on API 26/27 needs resolution before
claiming full legacy-client parity.

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
Android Auto/Desktop Head Unit is unavailable. Keep the PR draft until the
required receiver and automotive checks are resolved. Controlled FCast tests and
emulators do not establish physical receiver picture, audio, timing or automotive
UI behavior.

The receiver follow-up should cover paused handoff, play/pause and paused seeks
from both normal and system controls, supported speed/volume, queue navigation,
quality/audio replacement, disconnect/play-here, and dropped-connection recovery.
Exercise receiver switching when a second receiver is available. Android Auto UI
and automotive-specific behavior remain unverified even when the framework
legacy-browser protocol tests pass.

No merge, version bump, tag or release publication is part of this change.
