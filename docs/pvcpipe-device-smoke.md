# Device test records, October 2026

Observations from a Pixel 6a and a disposable emulator, in chronological
order. Results apply to the stated builds and test conditions. Later
sections record follow-ups without changing the earlier observations. Pull
request numbers refer to `75ohmantenna/pvcpipe`.
[odysee-observations.md](odysee-observations.md) has the runs for the Odysee
service.

## Pixel 6a smoke, 2026-10-07

The smoke test used commit `169113e7c4605297fa7184c7e307b21a08cd7cdb` on clean
`master`, a Pixel 6a (`bluejay`) running Android 17/API 37, and Wi-Fi ADB serial
`adb-25311JEGR13654-vFFNKQ._adb-tls-connect._tcp`. The APK was built with
`./gradlew :app:assembleDebug --console=plain` (58 tasks up to date), then
installed `app/build/outputs/apk/debug/app-debug.apk` with
`adb -s <serial> install -r`. The installed signer matched this APK; app data
was preserved. Package: `org.seventyfiveohmantenna.pvcpipe.debug` (debug
suffix on `org.seventyfiveohmantenna.pvcpipe`). These are **device observations**,
not inferences from offline tests. All URLs are public; no account was used.

### Startup and playback

- **PASS, startup/navigation.** YouTube Live loaded. The service picker offered
  YouTube and Rumble. Searching `Me at the zoo` returned jawed's public video;
  its 00:19 details and download controls opened. After an app process restart,
  the main feed loaded again without a crash.
- **PASS, YouTube playback.**
  `https://www.youtube.com/watch?v=YE7VzlLtp-4` (`Big Buck Bunny`, 09:57)
  showed moving video; detail time advanced 00:21→00:44→00:49. Pause held at
  01:19; seeking moved to 04:49; resume advanced 05:03→05:07. After Home,
  `media_session` was PLAYING; notification Pause produced PAUSED at 404115 ms,
  and its Play action returned to PLAYING.
  [Video frame and details](device-smoke/youtube-playback.webp).
- **BLOCKED, client-specific fallback.** Neither the client supplying playback
  nor an Android-failure/visionOS-fallback branch was observable in the player
  UI or bounded logs. No controlled client failure was injected on device.
- **PASS, Rumble watch.**
  `https://rumble.com/v5a7yh0-spacex-starship-test-flight-5-update.html`
  opened `SpaceX Starship Test Flight 5 Update`, duration 02:53; moving video
  and progress logs 19168→20166 and 54154→55158 ms; media pause at 98333 ms.
  [Watch video frame and details](device-smoke/rumble-watch.webp).
- **PASS, genuine Rumble Short.** The public feed item
  `https://rumble.com/shorts/v7g6wz4` opened
  `hitman 2 Short No Cometary :Golden Handshake Cut scene` (02:58). Playback
  detail time advanced 00:20→00:30; media session paused at 31437 ms.
- **PASS, query/fragment routing.** The watch URL above with
  `?next=/shorts/v7g6wz4`, with `#/shorts/v7g6wz4`, and with both still
  resolved to the **02:53 SpaceX watch**, not the 02:58 Short. The watch
  position was retained. Malformed links were not tested on device.

### Downloads and resume

- **PASS, small normal download.** The Rumble watch's 360p MP4 used three
  threads; Downloads displayed **Finished / 100%**, 12.81 MB. The saved file
  measured **13,435,079 bytes**. Local `ffprobe` identified H.264, 640×360,
  AAC, and duration 173.035 seconds; `ffmpeg -v error -i <file> -t 2 -f null -`
  decoded successfully. Opening it through the app into VLC displayed a
  moving frame and 02:53 duration.
  [Decoded saved media](device-smoke/download-decoded.webp).
- **PASS, parallel requests observed.** Bounded `DownloadMission` debug logs
  for the 360p mission showed workers 0, 1, and 2 issuing byte ranges and
  receiving compatible `Content-Range` responses with a total of 13,435,079 bytes.
  Final length and decode agreed. Later one-thread 1080p logs showed
  `Range=bytes=0-`, `Content-Range=bytes 0-81542670/81542671`, then
  `Range=bytes=81542661-81542671` answered with end 81542670. Those
  initial logs did **not** expose `If-Range`; the controlled transfer below
  observed conditional requests.
- **PASS, reopen finished missions.** Leaving and reopening Downloads showed
  the completed public-media entries at **Finished / 100%**.
- **Earlier limitation, resolved below:** The 20.43 MB and 77.77 MB public
  missions finished before they could be paused. Rapid taps on a finished card
  also preceded a persistent `Loading Metadata…` overlay. Those missions did
  not test partial resume; a separate controlled transfer did.

#### Controlled local-range follow-up

**Hybrid harness/UI, not a normal UI-initiated media download:** A temporary,
opt-in Android instrumentation method submitted each 4,194,304-byte mission
through production `DownloadManagerService.startMission` into an app-owned
test file. The normal Downloads **UI** supplied Pause and Start for each
mission. It did not run a site extractor, SAF picker, playback, or recovery.
The fixture URLs were `http://127.0.0.1:8765/smoke/a` and
`http://127.0.0.1:8765/smoke/b`; neither mission fetched public media.
A loopback host server reached through `adb reverse tcp:8765 tcp:8765` served
deterministic bytes at 4 KiB per 160 ms, HTTP ranges, and the strong ETag
`"pvcpipe-smoke-controlled-20261007"`. Source SHA-256:
`0309a3025f9e7f38ad555e11bee5a0cbafea0a1c02e39c7df51cc2f462ad4c61`.
No phone-wide network or VPN setting was changed.

- **PASS, active UI pause/resume (mission A).** Downloads showed Pending at
  45.80% while writing. Its own More options → Pause stopped the active worker
  at **2,629,632 / 4,194,304 bytes** (62.70%). A device harness read the
  pending mission and twice hashed the partial file, 350 ms apart: both hashes
  were `55a61ed8028851560f7c7e97232c0f95cdc43e85e99e71206e1f0a3f2bef8fb4`,
  and progress did not move. The UI showed **paused**;
  [paused mission](device-smoke/controlled-paused.webp). UI Start resumed it;
  Downloads then showed **Finished / 100%**, and an independent device check
  found the expected 4,194,304-byte length and exact source SHA-256.
- **PASS, partial persistence across app restart (mission B).** A distinct
  active mission was paused through the UI at **2,220,032 / 4,194,304 bytes**
  (52.93%). Before and after `am force-stop` of only the debug app, the
  pending mission reported the same progress and unchanged partial SHA-256
  `3138131d5966629ce1b923d60eb4752c9639ebfc0099b0292fee3aa7cfc70fb6`.
  Reopened Downloads showed the same **paused / 52.93%** state;
  [recovered mission](device-smoke/controlled-persisted.webp). UI Start
  resumed to **Finished / 100%**; the resulting 4,194,304-byte file matched
  the source SHA-256 above.
- **PASS, observed conditional HTTP ranges.** The fixture logged ranged
  `HEAD` probes and initial `GET Range: bytes=0-` with
  `If-Range: "pvcpipe-smoke-controlled-20261007"`. After pause, mission A
  requested `GET Range: bytes=2629632-` with the **same If-Range**;
  the server returned 206 for `2629632-4194303/4194304`. After the app
  restart, mission B requested `GET Range: bytes=2220032-` with that
  **same If-Range**, receiving 206 for `2220032-4194303/4194304`. Server-side
  socket bytes sent on cancellation slightly exceeded the mission's committed
  progress; the paused file hash and final file hashes provide the write
  checks. No different validator or full-response conditional fallback was
  injected.
- **BLOCKED, representation replacement/recovery.** No controlled version
  change, validator mismatch, refreshed URL, HTTP 200 replacement, or 416 was
  exercised. Completed downloads do not validate these paths.

A bounded app-PID error-log sample had no playback crash or extractor error
associated with the successful checks. A null-thumbnail `RealImageLoader`
`NullRequestDataException` and a `LegacyGraphicsTracker` buffer warning also
occurred; neither established a media failure. The root cause of the later
metadata overlay is **unconfirmed**: rapid taps hit a finished card and no
corresponding extraction failure was isolated. Raw device-wide logs, cookies,
tokens and notification-shade screenshots are deliberately not attached.

### Cleanup and limits

The five uniquely named public-media smoke entries/files were removed through
**Delete file** menus. The test directory
`/sdcard/Download/PVCPipe-smoke-20261007/` was empty afterward. YouTube was
restored as the selected service; the persistent download thread setting was
restored from 1 to the original **3**, confirmed by reopening the dialog. The
smoke player was closed (`media_session` STOPPED). No existing downloads were
deleted, app data cleared, or other app variant replaced. In the controlled
follow-up, the harness deleted **only** its two named finished missions/files
and their empty app-owned test directory; its checks confirmed absence, and
Downloads showed **Nothing here but crickets**. The loopback server and
`adb reverse` mapping were stopped. The temporary test source was removed;
the previously installed instrumentation APK was restored with a same-signer
`install -r`. A pulled copy matched the pretest APK SHA-256
`7bdace0cdbb94062496fe17fc129601774dca7276c14d2f1156f3adc26caa0d5`.
The tested production debug APK and saved app settings were not replaced
during that controlled follow-up.

**Destination restored after publication:** The original video destination
was not captured before choosing the test SAF directory. The user subsequently
identified it as `Download/ytdlp`. On this Pixel, the existing `ytdlp` folder
was selected through the system folder picker and access was allowed.
Settings → Download → Video download folder then displayed
`content://com.android.externalstorage.documents/tree/primary%3ADownload%2Fytdlp`.
The now-unreferenced test directory
`/sdcard/Download/PVCPipe-smoke-20261007/` was confirmed empty and removed.
Files already in `Download/ytdlp` were left untouched. No new download was
started after restoring the folder, so writing to it was not retested.

Verification is limited to this phone, the stated public URLs and local
fixture, and the network at the stated time. Rumble content availability can
change. The controlled resume checks exercised the real downloader and its
mission UI, **not** a site-extracted, UI-created slow mission. Validator
replacement, recovery after changed content, HTTP 200/416 on resume, and
YouTube client-fallback device scenarios remain untested; separate
deterministic regressions cannot substitute for these device observations.

## Emulator checks for the player and detail screen splits, 2026-10-11

These checks belong to the pull requests that moved code out of `Player.java`
and `VideoDetailFragment.java` (#101 to #104). They ran on a disposable
emulator, not on the Pixel: AVD `pvcpipe_ci_api37`, Android 17/API 37,
x86_64, headless with software rendering. The emulator's hardware video
decoder fails on this image, so it was started with
`-feature -HardwareDecoder` and ExoPlayer used the software H.264 decoder.
Each debug APK was installed with `adb -s emulator-5554 install -r`.

The checks were scripted over adb. Playback state and position came from
`dumpsys media_session`, rotation from `dumpsys window displays`, and what was
on screen from `uiautomator dump`. The video was
`https://www.youtube.com/watch?v=dQw4w9WgXcQ`. Every script also ran on a
build of `master` from before #101, with the same results.

- **PASS, player broadcasts (9 of 9).** Opening the link from outside the
  app, starting the main player, and then each action as a real broadcast to
  the player's receiver: play/pause paused and resumed, fast forward and
  rewind moved the position, repeat changed the repeat mode, and close stopped
  the player.
- **PASS, error recovery (11 of 11).** With the debug build's "crash the
  player" control, a live-window source error restarted playback, an
  unspecified error reloaded it and playback went on, and a renderer error
  shut the player down.
- **PASS, Back history (6 of 6).** Three videos opened one after another in
  the same detail screen. Back returned to the second, then to the first, and
  a third Back left the detail screen.
- **PASS with one known failure, player modes (23 of 24).** Main player
  position advanced; the fullscreen button turned the screen and Back left
  fullscreen; rotating the device through the accelerometer went to landscape
  and back while playing; a related item was enqueued and a media-next key
  skipped to it; the popup player showed its window and played; the
  background player played without a popup window and survived the activity
  being destroyed and recreated twice by a font-scale change; close stopped
  each player.
- **FAIL on every build, `master` included.** A player that was paused before
  entering fullscreen stayed paused after a play/pause broadcast. The cause is
  not known. Playback that was running when fullscreen was entered kept
  running in an earlier form of the same check.
- **PASS, receiver wiring.** The instrumented `AndroidPlayerBroadcastsTest`
  passed 4 of 4.

Limits: no physical device, no audio output, and one network at one time.
During these runs YouTube refused some other videos with a sign-in request,
and `https://rumble.com/v5a7yh0-spacex-starship-test-flight-5-update.html`
from the smoke test above failed with "Could not parse the website"; neither
was looked into. Errors that skip a stream and history entries created by
the player reporting a new queue were not exercised. The emulator setting
"resume playback" was switched off for these runs so every flow started its
video from the beginning.

## Correction and Pixel 6a checks, 2026-10-11

Added later the same day. The section above stays as it was written.

**Correction.** The entry "FAIL on every build, `master` included" above does
not describe a fault in the app. A log trace on the emulator showed that the
paused player starts playing by itself when fullscreen is entered with the
on-screen button: `VideoDetailFragment.checkLandscape()` calls `play()` when
the system orientation is locked and the player is not playing. The check
then sent a play/pause broadcast, which paused it again, and read that as
"stayed paused". With that request removed, the step passes on a build of
`master`: the player is playing in fullscreen without being asked.

**Pixel 6a.** The same scripted checks ran on the Pixel 6a (`bluejay`,
Android 17/API 37) over Wi-Fi ADB with a debug build of `master` at #104,
installed with `adb -s <serial> install -r` after confirming that the
installed package had the same signer. App data was kept. Input was only sent
while PVCPipe was in the foreground. The app's preference file was saved
before the run and written back afterwards; a byte comparison confirmed the
restore. No system setting and no other app's data was changed.

- **PASS, player broadcasts (9 of 9).** As on the emulator.
- **PASS, playback (11 of 11).** Main player position advanced, a related item
  was enqueued and a media-next key skipped to it, the background player
  played while the detail screen kept showing the video, and close stopped
  each player.
- **PASS, Back history (6 of 6).** Three videos in the same detail screen,
  Back to the second, Back to the first, Back leaves the screen.
- **PASS, error recovery (11 of 11).** Live-window error restarted playback,
  an unspecified error reloaded it, a renderer error shut the player down.
- **PASS, scroll fix from #100.** With "scroll only below player" off, the
  comments were scrolled and swiped back so that the header stayed partly
  collapsed. After the setting was turned on with the video still open, three
  swipes each brought a different comment to the top.

Not run on the Pixel: fullscreen, rotation, the popup player and activity
recreation. They need a system setting or a permission changed on the phone.
The unfixed build from before #100 was not installed on the Pixel, so the
scroll result shows the fixed behavior only; the emulator showed both.

Two things the Pixel runs brought out about the checks themselves. A UI dump
fails while the screen keeps animating, and the helper had been reading the
previous dump in that case; it now deletes the old dump first. And on a phone
the link chooser has no option selected, so the helper picks "Show info"
before "Just once".

**Rumble link.** The emulator section above lists
`https://rumble.com/v5a7yh0-spacex-starship-test-flight-5-update.html` as
failing with "Could not parse the website". It did not fail again. From the
same network the watch page answered HTTP 200 with the title and an embed id,
a live run of the extractor on the JVM parsed that URL (title, uploader, 173
seconds, four video streams), and a later attempt in the app on the emulator
loaded and played it. The failure was not reproduced and its cause was not
found.

## Follow-up emulator checks, 2026-10-11 and 2026-10-12

Scripted checks on the same emulator for things the earlier sections left
open. Each one names the build it ran on.

- **PASS, history entries made by the player.** On a build with two temporary
  log lines around the detail screen's history, video B was sent from outside
  to the background player while video A was on the detail screen. The log
  shows `onQueueUpdate` taking the history from one entry to two, with no
  stream load involved, and Back then returned to video A.
- **PASS with one gap, play queue activity.** Opened from the mini player, it
  showed the playing item, its position text followed playback, and its
  forward button moved the player to the second item. In an earlier run
  playback went on after it was closed. Not obtained: a read of the activity
  showing the second item after the skip.
- **No difference, D-pad navigation around #100.** Key-by-key recordings on
  the current build and on a build from before #100 are the same with "scroll
  only below player" on and off. In both, on a freshly opened detail screen
  the focus moves through the main screen behind it and the detail lists do
  not move. After a list had been touched, the focus moved between comments
  and their reply buttons. The focus path itself was not looked into.
- **Finding, skip-stream errors.** With the debug control's "Bad HTTP status"
  entry (#108) and a two-item queue, the skip branch ran and moved the queue
  index, the player reloaded both sources, and the position discontinuity
  that followed set the index back to the item ExoPlayer was still on. The
  first item kept playing. The control cannot break ExoPlayer itself, so this
  does not show what a real bad-status error does; whether the queue is pulled
  back to the failing item in that case is not known.
- **PASS, title row and arrow (#106), and the orientation decisions (#109).**
  Title and arrow sequence 14 of 14. Player modes 24 of 24, including the
  fullscreen button, Back out of fullscreen and rotation both ways.

## Pixel 6a checks that change phone settings, 2026-10-12

The four checks left out of the earlier Pixel run, on the same phone with a
debug build of `master` at #111. Each needs something changed outside the
app, so every value was read first and written back at the end: auto-rotate,
the user rotation, the font scale, and the "display over other apps"
permission of the debug app. All four were confirmed restored, and the app's
preference file matched its backup byte for byte.

- **PASS, 21 of 21.** With auto-rotate off, the fullscreen button turned the
  screen, playback ran in fullscreen and Back left it. A rotation forced
  through the system setting went to landscape and back with playback
  running. The popup player showed its window, played and removed the window
  on close. The background player kept playing while a font scale change
  destroyed and recreated the activity twice, and the detail screen came back.
- The rotation was forced through `user_rotation`, because a phone lying on a
  desk cannot be turned over adb. It is not the same as turning the phone.

The app's own log was recorded during this run, filtered to its uid. It had no
crash and no "not responding" report. Three warnings in it led to fixes:

- "Failed to inflate ColorStateList" with a stack trace for every round image
  view, fixed in #112.
- "BitmapDrawable created with null Bitmap" twice per video load, fixed in
  #112.
- "Broadcast receiver already unregistered" on every player start, fixed in
  #113.

Seen and left alone: undeliverable RxJava errors when a screen closes during a
request, which the app's handler expects; ExoPlayer's "Ignoring messages sent
after release" at teardown, whose caller was not traced; dropped frames of
about half a second around the activity restarts and rotations the run itself
caused; and messages from WebView, the codecs and system flags. The leak
watcher reported nothing, but heap dumps are switched off in this build, so
it would not have found a leak.

## YouTube sign-in refusals, 2026-10-11

Moved here from the services guide, where it was first written down.

During the scripted emulator runs of 2026-10-11, which opened videos over and
over from one address, YouTube refused some videos with "YouTube refused to
provide data and requires you to sign in". Those videos loaded again minutes
later, and other videos kept loading throughout. No log of a refused request
was captured for each client, and the refusals stopped before one could be,
so which clients were refused each time is not known. Nothing in these
observations points at the app. [services.md](services.md#youtube) says when
the app shows that message.

## FCast local emulator smoke, 2026-10-17

PVCPipe 0.1.0 debug with the new FCast integration, on a dedicated disposable
API 26 x86_64 emulator. No physical device installation or instrumentation
was performed. The API 37 emulator did not complete boot; this run does not
establish Android 17 permission-prompt behavior.

A throwaway instrumentation scenario launched the actual app/player, used
a generated 120-second H.264/AAC video from a local HTTP origin, and connected
the official sender SDK to a controlled FCast v3 TCP peer. The peer advertised
itself through Android NSD, parsed real protocol frames, fetched byte ranges
through PVCPipe's media proxy, and returned playback/volume updates.
The temporary debug cleartext override and instrumentation source were removed
afterward; neither is part of the shipped integration.

Observed passing scenario:

- Actual player cast button opened the themed picker; mDNS found the peer and
  tapping its row connected.
- Receiver fetched a 206 MP4 range through the LAN proxy. Local video/audio
  had decoded before handoff, and local playback was paused while casting.
- Pause/resume, seek to 42 seconds, speed 1.5x and volume 40% reached the peer.
  The Android media session reflected remote playing/paused states.
- Opening a second video replaced the cast source, and the receiver fetched
  that source. The fixture was reseeded after the app's normal cache clears;
  no service extractor response was being tested.
- **Play on this device** resumed local playback and sent Stop to the peer.
- Picker, connected controls and player entrypoint screenshots were inspected.

This peer is not a real FCast receiver/decoder. The scenario proves local
sender/control/proxy integration, not TV rendering or encrypted v4
interoperability. Hardware checks left by this emulator run:

1. Discover and connect to real Android/desktop receivers; test a pasted
   receiver link and Android 17 permission denial/regrant.
2. Cast real YouTube adaptive video with audio, HLS/live and other supported
   service streams; check codec compatibility, seeking and selected quality/audio.
3. Check queue/repeat, receiver-side controls, screen-off/background playback,
   connection loss, Stop and Play on this device on physical hardware.

## FCast Pixel 6a and Amazon receiver, 2026-10-17

PVCPipe 0.1.0 debug on the Pixel 6a running API 37, installed over Wi-Fi ADB.
The existing package and both APK signing certificates were checked before
updating the app and test package with `adb install -r`. Neither package was
uninstalled or cleared. Database and preference files remained in place,
including the original app UID and older preference/security files.
Normal playback added/updated watch history and cache data.

The app discovered the real `FCast-Amazon-AFTKM` receiver on the local network.
The source was YouTube's official Blender Big Buck Bunny film
(`aqz-KE-bpKQ`), with selected quality 720p60 and separate adaptive audio/video.

The first handoff failed with a receiver `NumberFormatException` for
`cast-0`. The merged DASH manifest had used string AdaptationSet IDs, but
those IDs must be integers. `CastManifest.mergeDash` now assigns distinct
numeric IDs. `CastDashReceiverCompatibilityTest` exercises the real ExoPlayer
DASH parser and checks that both playable track types and byte indexes
survive merging. It passed directly on the Pixel: one targeted test, with
only in-memory manifests, no database, preferences, files or network fixture.
The inspected runner was the standard `AndroidJUnitRunner`; no Gradle
connected-test task, orchestrator or clear-data option was used.

After the correction, observed on the Pixel and its media session:

- The Android 17 local-network prompt appeared only after opening casting.
  Denial left an explanation and disabled address entry; the explicit retry
  action requested permission again and allowing it started discovery.
- The discovered receiver connected, reported playing, and sent advancing
  progress for the YouTube DASH source.
- Pause held the clock at 03:42. Seeking while paused moved it to 01:25.
  Resume advanced progress, with media-session speed 1.5 and remote volume 40
  after changing those controls. Speed and volume were restored to their
  initial 1x and 100% values afterward.
- Casting continued while the app was in the background. The remote
  media-session clock advanced from about 02:16 to 02:49 during that interval.
- **Play on this device** changed the session from remote to local playback
  near the same position (about 04:31), and the expanded Pixel video surface
  was visually checked playing again.
- Manual reconnect using the receiver's IP and default port also reached
  remote playing state, at about 07:38.
- The sleep-key attempt recorded an 11.386-second sleep-to-wake interval;
  casting remained active and the remote clock advanced from 488688 to
  506692 ms across the overall attempt. The phone woke before the final
  sleep-state check, so this is not a sustained screen-off/Doze test.
- The dark-themed picker and remote controls were visually inspected.

A system media-session Stop initially stopped only the paused local
ExoPlayer while the receiver kept playing. The forwarding player's Stop
override now stops/cancels casting before keeping the normal local Stop
behavior. The corrected APK was installed and the parser regression passed
again on the Pixel. With Blender's *Sintel* (`eRsGyueVLvQ`, 720p) reporting
remote playing state, system Stop changed the session to local/non-playing
and released the FCast CPU wake lock. Reconnecting after that Stop worked;
the dialog's **Stop casting** also disconnected without starting the phone.
The final run left casting stopped and receiver speed/volume restored.

A later Big Buck Bunny reload returned HTTP 403 in the local
`YoutubeHttpDataSource`, before casting. That source failure was not changed
as part of the FCast fixes; the fresh Sintel source played and was used for
the final Stop checks. The app UID's captured log contained no fatal exception.

Final post-fix verification passed: the full app unit suite, Android debug
lint, Checkstyle, ktlint, dependency ordering, and debug/minified release
builds. The installed Pixel base APK's SHA-256 matched the final debug APK.

The TV picture and speakers were not directly observable from this session;
receiver playback feedback is not a visual/audible quality check. Secure v4,
pasted receiver links, other codecs/services, HLS/live, queue/repeat and long
screen-off/network-loss behavior still need separate receiver checks.

## FCast regular-player controls, 2026-10-17

The later regular-control/device-picker cutover was exercised on a new,
disposable API 26 emulator. The Pixel's advertised Wi-Fi ADB endpoint did
not accept a connection; its installation was not updated during this run.
The earlier Pixel/TV results above therefore do not verify this newer UI.

A throwaway instrumentation scenario launched the actual app/player and
injected touchscreen events into its normal controls. Cached fixture metadata
and a generated 120-second H.264/AAC video from a loopback HTTP origin made
the source deterministic. Two controlled FCast v3 TCP peers advertised
themselves through Android NSD, exchanged real frames with the official native
sender SDK, fetched 206 MP4 ranges through the app's media proxy, and sent
playback updates. This was not a mocked sender.

The completed scenario passed twice, including:

- The cast button opened the themed device list. Selecting Alpha connected
  and automatically dismissed the picker, leaving the ordinary player overlay.
- The regular play/pause button paused/resumed Alpha while the phone's
  ExoPlayer stayed paused.
- Left/right double-tap gestures sent rewind/fast-forward seeks using the
  configured 10-second interval.
- A held timeline drag stayed in seek state despite incoming receiver progress,
  and release sought to approximately 42 seconds. Seeking to approximately
  66 seconds while paused did not resume playback or leave a buffering spinner.
- The regular speed menu changed receiver speed to 1.5x.
- Opening the cast button while connected kept the device choices visible.
  Switching Alpha to Beta while paused retained the position and pause intent;
  switching back while playing retained the position and play intent.
  Both old targets received Stop, and each new connection fetched its media.
- **Disconnect** stopped Alpha, dismissed the picker, and left the phone paused.
  The final wire log reported Alpha/Beta media loads 2/1 and Stops 2/1.
- Settled device-picker, connected-picker and regular-control screenshots were
  visually inspected. The dummy webpage's auxiliary-content error below the
  player was outside this test; real service extraction was not exercised.

`CastPlaybackTest` also covers frozen position/play intent during receiver
changes and preservation of the player's active scrub state under receiver
progress. These are permanent, isolated JVM regressions.

Final verification passed: the full app unit suite, Android debug lint,
Checkstyle, ktlint, dependency ordering, and debug, Android test and minified
release APK builds.

The temporary instrumentation source, debug cleartext override, HTTP origin,
generated video and emulator were removed afterward. APK inspection confirmed
that the final debug app has no temporary cleartext override and the Android
test APK excludes the throwaway scenario while retaining the permanent parser
regression. This run does not verify a
real receiver's decoder, secure v4, Android 17 permission UI, or physical
device behavior for this control cutover.

## PVCPipe 0.2.0 release review, 2026-10-18

The adversarial review covered FCast transport/manifests, playback ownership
and controls, app/extractor service boundaries, download preparation, shared
version handling, and documentation. It was not a live availability audit
of every supported website or a real-receiver decoder test.

The review corrected signed-URL percent corruption, query-only manifest
resolution, inherited DASH segment-list truncation and empty upstream-error
response framing. It also corrected pending receiver/source controls,
paused buffering/quality intent, queue-occurrence ownership and returning
to the phone during scrubbing. Download preparation now carries a format
inferred by the dialog into the destination and recovery plan; BitChute
recognizes HLS MIME parameters. Version-code calculation rejects values
above Android's maximum instead of overflowing into an installable code.

Three manifest regressions failed against the pre-fix compiled implementation:
escaped signed URLs changed, query-only references lost the manifest filename,
and only the last overriding DASH segment remained. The standalone version
probe showed `429497.0.0` incorrectly mapping to `1002704` before the fix;
out-of-range probes were rejected afterward. Permanent behavior regressions
cover those boundaries and the playback/download transitions.

### Actual app and protocol exercise

A new disposable API 26 emulator ran the rebuilt app with the official native
sender SDK and two controlled FCast v3 TCP peers advertised through NSD.
The scenario used cached synthetic BitChute metadata and a generated local
120-second H.264/AAC video, with two quality labels to exercise source
replacement. It did not exercise live BitChute extraction or receiver decoding.
The dummy webpage's auxiliary-content error below the player was outside the
test. No Pixel installation or private data was changed.

The completed scenario passed with these observations:

- The themed picker discovered both targets, dismissed after handoff and
  remained open when reopened for device choice.
- Real touchscreen play/pause and left/right double-tap seeks controlled the
  receiver while the local player stayed paused.
- Thumb-origin, multi-move timeline drags remained stable under incoming
  receiver progress. Release sought to approximately 42 seconds; seeking to
  approximately 66 seconds while paused stayed paused without a spinner.
- The normal speed menu set receiver speed to 1.5x. A paused quality change
  reloaded at the retained position and remained paused after acknowledgement.
- The receiver row was selected and play/pause, seek and speed controls were
  invoked in one UI-thread turn, before connection callbacks could run.
  The new receiver loaded paused at 73 seconds and 2x without a session error.
- A real `MediaControllerCompat` controlled remote play, pause and seeking to
  78 seconds. Transport speed is deliberately not enabled by the ExoPlayer
  connector's default actions; the unused overrides were removed, not
  presented as a supported feature. The normal player speed menu is supported.
- Switching back while playing preserved position/intent and stopped the old
  target. Disconnect stopped the final target and left the phone paused.
- Actual HTTP requests traversed the app proxy for escaped signed paths,
  query-only HLS variants and a ranged MP4 fetch. Both overriding DASH
  segments and inherited initialization were fetched. A missing manifest
  returned an empty 404 with `Content-Length: 0`.
- Settled discovery-picker, connected-picker and paused-quality screenshots
  were visually inspected.

The final wire log reported:

```text
FCAST_MANIFEST_NETWORK_OK signedQueryAndPath=true inheritedSegments=2 empty404=true
FCAST_REVIEW_SMOKE_OK alphaLoads=3 betaLoads=1 alphaSeeks=[30.231, 22.951, 41.983, 65.95] stops=2/1
```

Initial fixture attempts exposed an inaccurate synthetic drag trajectory,
an unsupported transport-speed probe and a wait on peer state before the UI
had received its acknowledgement. The final scenario dragged from the actual
thumb, exercised advertised transport actions and awaited both sides; no
production seek mapping or new transport-speed feature was introduced.

The temporary instrumentation source and debug cleartext override were removed,
and the HTTP origin, generated media, helper scripts and owned emulator were
stopped/deleted. Real receiver decoding, secure v4, Android 17 permission UI,
rotation during handoff and current live-site availability remain unverified
by this run. Earlier dated hardware records do not verify this newer release.

### Final clean artifacts

After removing the throwaway scenario and override, `make ci` passed again:
build-logic version tests, app unit tests, offline extractor tests,
Checkstyle/ktlint/dependency ordering, Android lint and both APK builds.
`assembleDebugAndroidTest` also passed. APK inspection confirmed version
`0.2.0` / code `1000200` for debug and release, no temporary cleartext flag,
and no throwaway smoke classes in the test APK; the permanent DASH parser
regression remains. The release APK is unsigned.

All 133 local Markdown file/heading references checked across the root,
guides, records and fixture READMEs resolved. Historical observations were
preserved rather than rewritten to claim current hardware coverage.

## PVCPipe 0.2.1 menu release review, 2026-10-18

The main drawer's service-specific Live/Trending section was removed, including
its menu group and selection handler. Existing main-page tabs and the kiosk
selector under Settings → Content → Main page content remain available.

On a disposable API 26 emulator, the actual drawer contained only Subscriptions,
What's New, Bookmarked playlists, Downloads, History, Settings and About & FAQ.
Repeated service-selector toggles and switches through all five services did not
restore feed shortcuts or duplicate entries. Settings opened successfully, the
four existing main-page tabs remained, and the kiosk selector still offered
YouTube Live and the category feeds. No instrumentation was run on the Pixel.

After the version bump, `make ci` passed, including both APK builds. APK metadata
reported `0.2.1` / code `1000201` for debug and release, and the bundled extractor
produced `extractor-0.2.1.jar`. The rebuilt debug APK was installed and its
simplified drawer was verified again on the emulator. The release APK is unsigned;
this record does not establish new playback, receiver or physical-device UI coverage.

## Kick service smoke, 2026-10-18

The Kick-enabled debug APK was installed with `install -r` on the disposable
`castout_api26` emulator (Android 8.0/API 26, x86_64, headless software
rendering). No instrumentation, uninstall or data-clear command was run.
The connected Pixel was not used.

- The service selector listed Kick as the sixth service. Selecting it opened
  Featured with the Kick theme and live broadcast cards.
- Native search suggestions and channel results for `xqc` loaded. Opening
  xQc showed channel metadata, Videos and Live tabs, and retained VODs.
  A local subscription changed to Subscribed and was then removed, returning
  to Subscribe.
- An external Android VIEW intent for `kick.com/sjc_official` opened the
  normal router and live details. The internal player rendered broadcast
  video and its media session reported PLAYING. Background and Popup controls
  were visible; no finished-recording Download control was offered.
- A VIEW intent for
  `kick.com/cazmilligan/videos/01a14728-1a88-7fe2-a32b-88a6b8b9eda4`
  showed `BRAND RISK 16 WATCH ALONG`, duration 4:29:07. The player rendered
  video and reported PLAYING. The download dialog offered 1080p, 720p, 480p,
  360p and 160p; it was cancelled without starting a mission.
- A channel that had gone offline showed Content unavailable. An xQc VOD
  marked mature respected the existing age-content setting and was blocked;
  that setting was not changed.

The app was force-stopped after the smoke without clearing its data. These
checks establish actual API 26 UI and rendered playback, not smooth-playback
performance, a completed download, notification delivery, external-player
playback, casting or physical-device behavior. API 37 did not finish booting
and API 35 repeatedly lost its system server before the new APK could be
installed, so neither supplies runtime evidence for this change.

Release-artifact inspection initially found all three Kick service themes
missing, despite the working debug theme. A dedicated `res/raw/` keep file
corrected this; the shrinker required normalized names such as
`LightTheme_Kick`, not the dotted XML declaration name. After rebuilding,
`aapt2 dump resources` confirmed `LightTheme.Kick`, `DarkTheme.Kick` and
`BlackTheme.Kick`, each with its three color attributes. The native smoke
above preceded this resource-retention-only correction; extractor and
player code did not change afterward.

The final `make ci` passed after the keep-file correction: build-logic and
app unit tests, offline extractor regressions, Checkstyle, ktlint, dependency
ordering, Android lint, and debug/release assembly. The release APK remains
unsigned. All 43 local Markdown file/heading references in the affected
guides and records resolved.

## PVCPipe 0.2.2 release build, 2026-10-18

The shared version was bumped to `0.2.2` for the Kick integration. `make ci`
passed before the release commit. APK inspection reported version `0.2.2`,
code `1000202`, for both debug and release; the composite build produced
`extractor-0.2.2.jar`. The unsigned release retained all three dynamically
selected Kick themes and their color attributes.

The debug APK's signing certificate matched the existing Pixel installation,
which was version `0.2.1` / code `1000201`, allowing an in-place update of
the same application id. This build/version check adds no new physical-device
playback claim to the earlier Kick smoke.

## Kick image-metadata debug update, 2026-10-18

The post-release image-metadata fix was installed on the Pixel as an in-place
debug update, still version `0.2.2` / code `1000202`. The candidate application
id and signing certificate matched the installed package before `adb install -r`.
Installation succeeded; app id `10326`, the private data directory and the
original `2026-10-07 10:06:01` first-install timestamp remained unchanged.
The installed APK's SHA-256 matched the fixed build:
`987875d38ed97b1be55a73d272143cfc44c03f67f351e9e73fd72d705505bfa2`.
No uninstall, data-clear or instrumentation task was run.

The [image-metadata follow-up](service-protocol-observations.md#image-metadata-follow-up--2026-10-18)
records the API 26 native UI verification and release gate. This installation
added no physical-device playback evidence, and no new release tag was created.

## Kick full-width channel banners, 2026-10-18

On the API 26 emulator, xQc's channel reproduced a small, centered header
photo with large side gaps. The shared channel banner view used `fitCenter`
inside a height cap. Switching that view to `centerCrop` preserved the
artwork's aspect ratio and visibly filled the entire 720-pixel screen width.
Sonwabile's wider banner was also checked on the native channel screen.
The change leaves the existing banner-height cap and no-banner handling intact.
`:app:assembleDebug` and `:app:lintDebug` passed.

The rebuilt debug APK was installed on the Pixel with `adb install -r`
after application-id and certificate checks. App id `10326`, its private
data directory and first-install timestamp remained unchanged. The installed
APK matched the build SHA-256:
`4a85552f7ba1932a6bf9cf7ff20ebae925a08068dfa00d979feb9973d3bdfaf2`.
No data-clear, uninstall or instrumentation task was run. This debug update
retained version `0.2.2`; no new tag or physical-device rendering claim was added.

## Subscription service grouping, 2026-10-18

The API 26 emulator used isolated subscription fixtures for YouTube, SoundCloud,
Kick and retired service id `3`, including identical account names across services
and a custom feed group spanning YouTube and Kick. No phone database was read or
modified for these fixtures, and no instrumentation task was run.

The original installed build displayed a mixed alphabetical account list. With
the grouped build, the native Subscriptions tab displayed service headings and
alphabetical accounts within each service. Both list and two-column grid layouts
were inspected; service headings occupied the full width. Retired-service
accounts remained visible under `<unknown>`.

Tapping the Kick account opened Sonwabile's subscribed channel. Its metadata
update preserved the subscription id and both custom-group memberships.
Long-pressing the last retired-service account and choosing Unsubscribe removed
the account and its heading immediately without removing the following Kick
section. Restoring the original empty database showed the normal import/export
hint with no empty service headings. The emulator database and preferences were
restored after the scenario.

`make ci` passed, including app/build-logic tests, offline extractor regressions,
lint, Checkstyle, ktlint, dependency ordering and debug/release assembly.

The debug build was then installed on the Pixel using `adb install -r`, with
matching application id and signing certificate. No active phone cast session
was present before installation. App id `10326`, its private data directory and
first-install timestamp remained unchanged. The installed APK matched the build
SHA-256 `59f2ed12fdd5ceb9edeb2bde5c90a44d23629abcdab3652b3aff1029a400ef88`.
No phone data-clear, uninstall or instrumentation task was run; native rendering
verification above used the emulator, not the phone.

## FCast receiver recovery, 2026-10-18

The Pixel's post-release `0.2.2` debug build connected to the real
`FCast-Amazon-AFTKM` receiver, running FCast Receiver Android `2.1.5-playstore`,
but the TV remained on its ready screen. The phone stayed in remote buffering.
The control connection alone did not establish playback.

A temporary plaintext protocol relay captured a valid HLS Play command.
The phone's receiver-facing proxy returned HTTP 200 for the rewritten playlist
and HTTP 206 for a MPEG-TS byte range. A separate minimal request for a public
MP4 bypassed both Kick and PVCPipe's proxy; the receiver accepted that request
but still reported no playback clock or player error. Its cached source was
present without a playback position. Receiver ADB access was unavailable.

Opening the receiver's ready screen did not restore playback. After the user
rebooted the Fire Stick and opened FCast Receiver, a fresh normal PVCPipe
connection played the same Kick VOD. The receiver reported PLAYING with its
clock advancing from 269.329 to 277.350 seconds; the phone reported remote
PLAYING. The user confirmed both TV picture and audio, and the cast was left
playing under the normal controls.

No FCast code, protocol override, retry policy or APK update was needed for
that recovery. The receiver source contains a pending-play guard, but its
internal state was not observable; attributing the failure to that guard would
be an inference. The temporary relay, ADB reverse tunnel and diagnostic files
were removed. This verifies that Kick VOD on that build and receiver, not
secure-v4 interoperability, every service or later casting changes.

## 0.2.3 adversarial review, 2026-10-18

The review covered the Kick integration and artwork metadata, shared channel
header cropping, service-grouped subscriptions, FCast session policy,
discovery and the receiver-facing media proxy. Current guides and comments
were reconciled with the implementation; earlier dated results remain
evidence for their original builds.

### Controlled media and protocol checks

A standalone JVM runner used production proxy and manifest-rewriter classes
against a loopback HTTP origin. An implicit single-file DASH SegmentList
previously returned MPD XML bytes for media ranges; after correction,
initialization and media requests returned the expected `AB` and `CD` payloads
with HTTP 206. An inline HLS load that previously published a manifest whose
first segment returned 404 now failed preparation before publication, leaving
its existing segment capability usable. Regression coverage also exercises
the exact capacity boundary, paired manifests and bounded route replacement
after preparation for long-running live playback.

Seven unauthenticated clients sent incomplete headers and continued dripping
bytes while an eighth worker served a valid media stream. The corrected
server closed the incomplete requests after 15.010–15.017 seconds, accepted a
fresh capability request and delivered the valid stream's final byte after
the header deadline. The origin deliberately held that stream open for
18 seconds. This establishes the header-reading bound, not immunity to
arbitrary sustained connection floods.

### Native Android app and controlled receiver

Direct, targeted instrumentation ran only on the isolated API 26 emulator.
A disposable driver seeded two in-memory stream-info fixtures, opened the
normal main player and used the real native FCast SDK against a controlled
TCP receiver. A generated 120-second H.264/AAC MP4 supplied actual local
video decoding and ranged proxy requests. An initial public MP4 fixture
returned HTTP 403 and was discarded; that failed setup was not used as
FCast evidence. No production network-security override was added.

The installed `0.2.2` build reproduced unchanged-state buffering: the
receiver was connected, fetched MP4 bytes with HTTP 206 and advanced its
clock, but the app remained preparing. The `0.2.3` build left buffering on
that same protocol pattern. Its ordinary player controls visibly showed
active casting rather than the loading indicator.

The corrected build also exercised:

- A v2 receiver returning to idle released local cast ownership.
- A v3-advertising peer that sent neither Initial nor own-load PlayUpdate
  notifications became ready from playback progress. Its paused-at-duration
  update advanced the real two-item queue and produced a second native Play.
- A source-echoing v3 peer replacing the current video caused PVCPipe to
  relinquish ownership without sending Stop to the replacement.
- Actual Android NSD registration, picker background/foreground, receiver
  removal and rediscovery. The retained picker view had one receiver before
  stopping discovery and none after restart; registering the fixture again
  restored its row.
- The existing DASH compatibility instrumentation passed against the app's
  local ExoPlayer parser. It does not run the installed receiver's parser.

Pure-policy regressions additionally cover unchanged duration callbacks,
once-only completion and repeat, initial versus terminal idle, pending-source
identity, and v4's explicit-end behavior. API 34 service-info callback recovery
uses controlled framework callbacks in JVM regressions; the API 26 emulator
does not exercise that newer Android API.

These results do not establish real receiver decoding, secure-v4
interoperability or playback from every supported service. The earlier
[Fire Stick recovery](#fcast-receiver-recovery-2026-10-18) remains separate
hardware evidence for the post-release `0.2.2` build.

### Release gate and local artifacts

`make ci` passed: 3 build-logic tests, 819 app unit tests and 388 offline
extractor cases, with no failed or skipped cases. Checkstyle, ktlint,
dependency ordering, Android lint and debug/release assembly passed.
Both APKs reported version `0.2.3`, code `1000203`, with their expected
debug and release application IDs.

The locally checked debug APK was signed with the existing debug certificate
and had SHA-256
`4d2f241ab579d61a7a9a94a76aeb841e40730b2db276aed727e8307955058d57`.
The unsigned release APK had SHA-256
`e1c6a09a2fbb3b4a9ee16b35f3df0e19e9ba27ff14ac4c730e1d7986e486962b`.
These identify the local artifacts from this run, not signed production
release assets or a reproducible-build guarantee.

All five native scenarios above passed with that final debug APK. Both
receiver idle and foreign-source replacement released the actual wake lock
and closed the previous media listener.

The disposable driver was removed from source and was absent from the
retained debug and rebuilt instrumentation APKs. Its test package was
uninstalled only from the isolated emulator. The emulator's original database,
preferences and private files were restored, with all ten saved file hashes
matching the backup. The main app was not uninstalled or data-cleared.
The controlled origin, ADB reverse mapping, temporary files and owned emulator
were stopped or removed. The phone was not modified during this review.

## Combined playback integration validation, 2026-10-23

Started from master `f791ba32700f35b142fadee58704f7ae143485e6`
(PRs #134–142 merged). This is focused integration validation, not a new
concurrency audit. Existing observations above and the earlier controlled JVM
records in [playback.md](playback.md) retain their original evidence limits.
Unrelated local tooling/documentation edits were excluded.

### Isolation and execution

Created new disposable AVDs, `pvcpipe_playback_validation_26` and
`pvcpipe_playback_validation_37`, from installed x86_64 images. No physical
device was attached. API 27 had no installed image.

API 37's Google APIs image eventually booted after two bounded startup
attempts. Both APKs installed, but two direct instrumentation attempts exited
before any test result with `Process crashed`. Android's process-exit records
identify both as `BIND APPLICATION ANR` (reason 6, subreason 34), not a reported
playback assertion. The second attempt disabled Google Play services, Search,
Photos and Wellbeing only in this disposable AVD to reduce background contention;
it still failed at startup. The emulator warned that it recommends 16 GiB host
RAM; this host has about 8 GiB and was swapping. That is an environment concern,
not proof of the ANR's root cause. **API 37 integration execution remains blocked;
zero tests passed there.** No ANR timeout or application security policy was
relaxed. Both owned emulators were stopped afterward.

The runner is `androidx.test.runner.AndroidJUnitRunner`. The documented
Gradle connected-test path can uninstall both APKs, so this run built APKs,
installed them explicitly on the owned emulator serials, and used direct
targeted instrumentation instead. No app was uninstalled or data-cleared.
Each new fixture owns its generated local PCM WAV files. Player fixtures
replace and restore the database/cache singletons, use unique preferences
and isolated caches, and close/delete only their own storage. Run these
classes serially in one instrumentation process, not concurrently with other
singleton-replacing tests.

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb -s '<owned-emulator>' install -r app/build/outputs/apk/debug/app-debug.apk
adb -s '<owned-emulator>' install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s '<owned-emulator>' shell am instrument -w \
  -e class org.schabi.newpipe.player.playback.PreparedMediaSourceManagerIntegrationTest,org.schabi.newpipe.player.PlayerRecoveryEngineIntegrationTest,org.schabi.newpipe.player.history.PreparedPlayerSqliteHistoryTest \
  org.seventyfiveohmantenna.pvcpipe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### Prepared engine and SQLite evidence

The final API 26 run passed **16 tests**:

| Boundary | Checks | Observed behavior |
| --- | ---: | --- |
| Prepared source manager | 3 | Queued append/move/remove preserves occurrence correspondence; removing A synchronizes already-preloaded B without another resolution; a retained old-engine completion cannot re-enter the shared listener or alter prepared replacement playback. |
| Pending navigation/recovery | 10 | Real Player/manager/resolver/engine delivery preserves B's explicit or unset recovery through quality/audio reload and removal/reindexing, including distinct same-URL occurrences; B actually starts at its intended position. |
| On-disk Room/SQLite | 3 | Correct nonzero attribution and resume; actual zero filtering without erasing a stored checkpoint; accepted writes and resume barriers survive navigation/destruction; real end-of-source completion commits after earlier held writes. |

The prepared-manager fixture waits for published non-placeholder timelines,
uses playback-thread messages and playlist fences, and observes real Handler
dispatch. It never manually invokes a completion. Its retirement case holds
the old playback thread, prepares and plays a separate replacement through
the shared listener, then releases the old completion and plays the replacement
again. The timed playback message, not exact agreement with the asynchronously
published application clock, proves progression.

Recovery metadata is gated at `PlayQueueItem.getStream()`; resolution,
`LoadedMediaSource` ownership, preparation, application listeners and seeks
are real. Both audio labels use local WAV media. Quality changes exercise
the actual recovery/reload path on audio, not video-quality decoding.
The SQLite fixture uses a unique on-disk Room database and the production
record manager. It independently asserts persisted state and queue recovery,
then reopens the database to check the committed resume value. It installs
its controlled source directly, bypassing the source manager/extractor.
Held writes use bounded executor gates; end-of-source completion is emitted
by actual playback, not by calling Player's completion callback.
That completion/navigation case intentionally has one engine window beneath a
two-item queue; production-managed non-last items normally auto-transition.
It proves accepted-write ordering and process-lifetime completion, not that
normal manager navigation follows that fixture-shaped STATE_ENDED sequence.

These are local PCM engine/application/storage observations, not live-service,
compressed video-decoder, audible-output, process-death or Android-started
service/UI evidence. No mocked record-manager arguments are treated as
database corruption.

### Confirmed production defect and narrow correction

On unchanged master, five pending-selection cases failed after actual playlist
replacement. A played around 42 seconds; B was selected with metadata pending;
quality/audio reload retained B's recovery initially. During unblock,
`setMediaSource(..., false)` synchronously emitted `DISCONTINUITY_REASON_REMOVE`
at old index 0. Player had already changed from blocked to buffering, so history's
normal discontinuity navigation policy changed selection back from B to A.
The engine then resumed A instead of starting B. Reindexed removal cases did
not expose that index mismatch.

The first correction installed the replacement timeline before leaving blocked
display state, then entered buffering and prepared. All ten recovery engine
cases passed locally, retaining the real reentrant engine boundary.

Fresh complete-PR review of candidate `6e859c42cbaf8a249ce5c6e710283b9ab59190fa`
identified that active receiver state delivery can replace BLOCKED before source
installation. Independently added JVM regressions reproduced this: the local
control passed, but a real `onCastingStateChanged(PAUSED)` followed by synchronous
REMOVE during mocked engine installation reselected A. The final correction
also scopes an `installingSource` navigation guard around `setMediaSource`,
cleared in `finally`, independently of display state. Real engine navigation
after installation remains enabled. This is a controlled JVM cast-state boundary,
not real SDK/receiver evidence. No ownership, cancellation, TLS or protocol rule
is relaxed.

The full `PlayerRecoveryTest` JVM class and all 16 API 26 integration cases
passed after this review correction. Recovery cases now also await an actual
playback-thread timed message after B starts, drain application-owned history
writes before checking disabled-history storage, and restore singleton resources
even if cache release fails. The complete-PR review's SQLite completion-shape
note is qualified above rather than claimed as normal manager navigation.

A second fresh complete-PR review approved the production correction at
`412b209a9405c5e2dede26a63092b213eefd6310`, with two non-blocking SQLite-fixture
failure-path findings. Both were accepted: main-thread assertions now return to
the test thread, and cleanup restores only installed singletons while attempting
every owned-resource cleanup even after an earlier failure. A disposable
two-check smoke proved assertion delivery without process death and preservation
of an existing isolated database/cache before setup. Those checks and all 16
durable integrations passed together (18 checks); the smoke source was removed
before rebuilding the final instrumentation APK. These are fixture failure-path
checks, not additional playback or receiver evidence.

Initial fixture failures were separate: assigning synthetic WAV metadata to
YouTube incorrectly requested its indexed DASH resolver. The corrected local
fixture uses SoundCloud's generic progressive path without live extraction.
An exact 300 ms application-clock assertion was also replaced with the real
playback-thread timed-message proof and a seek-processing fence.

### Cast and physical-receiver limits

No retained real-SDK/controlled-peer harness was found; older dated smoke drivers
were explicitly removed. This run does not repeat their transport evidence.
JVM cast policy/transport doubles remain separate from prepared local playback
and SQLite evidence. Neither establishes physical receiver picture/audio,
secure-v4 interoperability, or ambiguous old/new-source timing on no-echo peers.

Physical-receiver checklist for this combined head (not executed here):

| Scenario | Required observation |
| --- | --- |
| Paused startup | Load paused; verify no audible/visible autoplay, frozen sender clock, and retained pause through buffering/first source report. |
| Seek | Seek while paused and playing, including zero; verify receiver output/position and sender controls agree without accepting a pre-seek sample. |
| Replacement | Change quality/audio while B metadata is pending, including same-URL occurrences and removal/reindexing; verify B's intended recovery and actual receiver start. |
| Receiver switching | Switch while paused and playing with pending seek/speed; old receiver stops and the new receiver retains the selected occurrence and latest intent. |
| Disconnect/local return | Disconnect and Play on this device during preparation/replacement; no reset clock overwrites recovery, old receiver stops, and local pause/resume follows the chosen action. |
| History/resume | Save attributable nonzero receiver progress, navigate/destroy Player without killing the process, verify stored resume independently, and check zero filtering and completed-item behavior. |

Record receiver model/version, negotiated protocol, source-echo capability,
APK/head and actual picture/audio observations. Keep certificate/fingerprint
verification enabled; do not infer decoding merely from a media request or
protocol acknowledgement.

### Final repository gate

`make ci` passed both for the initial unblock-order correction and again after
the reviewed installation guard and fixture corrections: build-logic/static
checks, the full app JVM suite (including cast/recovery/history/manager
regressions), offline extractor gate, Android lint, and debug/release assembly.
In the post-review run, app JVM tests, lint and release R8/assembly executed;
unchanged extractor and debug APK tasks reused up-to-date outputs where Gradle
reported them. Instrumentation assembly and the repeated API 26 smoke passed.
Gate attempts caught 101-character Java test lines; both were wrapped before
the successful full runs. The temporary targeted formatter script was removed.
No live-service or physical-receiver check was run.

After the second review's fixture-only failure-path corrections, instrumentation
assembly and the complete `make ci` gate passed again, reusing unchanged outputs
where reported. The rebuilt APK without the disposable smoke source then passed
all 16 durable integration checks again on API 26.

### Failed gated-write cleanup follow-up

The third fresh complete-PR review approved production at
`f5af3406fb7aecaf1c07b6a00044afd86cd79722` and identified one non-blocking
fixture failure path: shutting down a gated executor immediately after an
assertion failure can reject the next accepted write and stall the process-wide
history queue. A disposable real-engine/SQLite check reproduced a subsequent
resume timeout and teardown timeout on that candidate.

Both fixture executor finalizers now await the shared accepted-write barrier
before shutdown, with bounded waits and shutdown in `finally`. The identical
forced-failure check then resumed the committed 9000 ms checkpoint successfully;
all 16 durable integrations passed afterward in the same process (17 checks).
The disposable smoke source was removed. This corrects fixture failure
diagnostics/isolation, not a newly discovered production playback defect.

After this final fixture-ordering correction, the rebuilt APK without the smoke
source passed all 16 durable checks, and `make ci` passed again with unchanged
outputs reused where reported.

## API 37 startup investigation follow-up, 2026-10-23

Started from current master `df6393755eccf03404e74dce5f45d535209921b1`
(PRs #134–143 merged). The failed instrumentation record above remains unchanged.
This follow-up did not reproduce its BIND APPLICATION ANR: the controlled
attempt stopped at emulator boot, before installation or application startup.
No production or test initialization change is justified by this evidence.

### Environment and bounded boot experiment

No ADB device, emulator or Gradle build was running at baseline. The host was
Linux x64, Intel Core i7 Q720 at 1.60 GHz (4 cores/8 threads), with 7.7 GiB
RAM and 9.9 GiB swap. Initially 5.6 GiB RAM and 6.0 GiB swap were used,
2.1 GiB RAM was available, and load averages were 3.85/4.92/6.23.
The existing browser and Java/Kotlin language-server processes remained running;
they were not terminated to manufacture an idle-host result. This is an occupied
host measurement, not an isolated comparison proving resource causality.

Created only the disposable AVD `pvcpipe_anr37_20261023`, using the Pixel 2
profile and installed `system-images;android-37.0;google_apis;x86_64` revision 6.
Guest fingerprint:
`google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys`.
Emulator version was 37.1.11.0, build 15917651. KVM acceleration check passed.
One emulator was launched, with no concurrent build:

```sh
$ANDROID_HOME/emulator/emulator -avd pvcpipe_anr37_20261023 -port 5580 \
  -no-window -no-audio -no-snapshot -gpu swiftshader \
  -cores 2 -memory 2048 -feature -HardwareDecoder
```

Console observations (recorded in the investigation session, not the local
capture files below): the emulator increased guest RAM to **4096 MiB**,
warned that the suggested host minimum was **16384 MiB** versus 7869 MiB
present, and forced **one vCPU**
because the host did not support its modern multicore virtualization features.
Requested memory/core values therefore do not describe the effective setup.
No ANR timeout, SELinux policy, application security mechanism or installed
Google package was changed.

The console's initial readiness wait expired after 240 seconds. A subsequent bounded
readiness observation sampled `sys.boot_completed`, `service check activity`
and `service check package` twelve times over 242 seconds. At the final check,
15:29:56 UTC in the session transcript (roughly ten minutes after launch),
boot completion was still unset
and both services were absent. ADB shell/logcat remained responsive. Boot logs
showed zygote preload taking 29985 ms, system server starting at guest uptime
148030 ms, and scanning 318 system packages taking 139446 ms. The latest
captured package-manager progress was uncompressing the system Trichrome stub;
this locates observed progress, not a demonstrated Trichrome defect.

The retained 15:29:37 UTC host sample shows 2.0 GiB available RAM, 8.1 GiB
swap used and load averages 20.53/16.75/11.49. Its `vmstat` interval samples
show 12–22 runnable tasks, up to 4524 KiB/s swap-out and 1904 KiB/s swap-in,
and CPU idle of 1/2/1/5%. These observations demonstrate contention during
this attempt; they do not prove that it caused either the boot delay or the
earlier application ANRs.
The owned emulator was stopped with `adb -s emulator-5580 emu kill`;
the disposable AVD files were retained. No unchanged boot retry was attempted.
Unlike the earlier record's eventual API 37 boot after two attempts, this
fresh AVD did not reach a usable baseline within the bounded observation.

### Artifact identity and unexecuted boundaries

The existing APKs were fingerprinted but **not installed or rebuilt for this
boot experiment**. Their hashes identify local artifacts, not a proven
reproducible build of the recorded master:

| Artifact | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `b2ad27eb5b80c095c59703b3f0126e7f8512003baf5c908c28ebd163d1b5c7fb` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `89c7cca13fd900a54daa134656366aab381c87b49c8de7c1e6d2ec67dc002827` |

| Boundary | Follow-up result |
| --- | --- |
| Emulator boot/responsiveness | Shell responsive; boot/activity/package readiness failed within the bounded observation. |
| Normal PVCPipe launch | Not reached; no APK installed. |
| Minimal instrumentation startup | Not reached. |
| Prepared manager, recovery, SQLite classes individually | Not reached. |
| Complete serial 16-test suite and repeats | Not reached; zero new API 37 test results. |
| Unchanged pre-#143 comparison | Not run; no usable common runtime baseline. |
| API 26 | Not rerun; no application/test code changed. Earlier 16-test passes retain only their original scope. |

Captured logcat, guest properties/services and host pressure/readiness samples
were retained locally in `/tmp/pvcpipe-anr37-20261023/`; they are temporary local
evidence, not repository artifacts. The guest ANR directory listing was empty;
activity exit-info was unavailable without ActivityManager. No original ANR
trace was recovered in this fresh AVD, so application/provider initialization,
runner startup and test-specific failure remain unseparated for the original
incident. Source inspection alone is not runtime exoneration of any of them.

The remaining prerequisite is a usable API 37 baseline: sufficient free host
resources and supported CPU/emulator execution, with boot completion and stable
activity/package service responses measured before installing the APKs.
A quieter or better-resourced host is a proposed comparison, not a verified fix.
After establishing that baseline, measure normal cold launch, minimal runner
startup, each integration class serially, then the full 16-test suite with
repeat execution and exit-info/log/trace capture. No API 37 playback assertion
has failed in this follow-up; none has executed. No compressed-video,
physical-device or receiver behavior is established.

### Documentation gate

After stopping the emulator, `make ci` passed all five serial stages
(static/build-logic checks, app JVM/offline extractor tests, lint, debug and
release APK assembly), with unchanged outputs reused where Gradle reported
them up to date. This repository gate is not API 37 runtime validation.
