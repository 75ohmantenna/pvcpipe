# Pixel 6a device smoke — 2026-10-07

The smoke test used commit `169113e7c4605297fa7184c7e307b21a08cd7cdb` on clean
`master`, a Pixel 6a (`bluejay`) running Android 17/API 37, and Wi-Fi ADB serial
`adb-25311JEGR13654-vFFNKQ._adb-tls-connect._tcp`. The APK was built with
`./gradlew :app:assembleDebug --console=plain` (58 tasks up to date), then
installed `app/build/outputs/apk/debug/app-debug.apk` with
`adb -s <serial> install -r`. The installed signer matched this APK; app data
was preserved. Package: `org.seventyfiveohmantenna.pvcpipe.debug` (debug
suffix on `org.seventyfiveohmantenna.pvcpipe`). These are **device observations**,
not inferences from offline tests. All URLs are public; no account was used.

## Startup and playback

- **PASS — Startup/navigation:** YouTube Live loaded. The service picker offered
  YouTube and Rumble. Searching `Me at the zoo` returned jawed's public video;
  its 00:19 details and download controls opened. After an app process restart,
  the main feed loaded again without a crash.
- **PASS — YouTube playback:**
  `https://www.youtube.com/watch?v=YE7VzlLtp-4` (`Big Buck Bunny`, 09:57)
  showed moving video; detail time advanced 00:21→00:44→00:49. Pause held at
  01:19; seeking moved to 04:49; resume advanced 05:03→05:07. After Home,
  `media_session` was PLAYING; notification Pause produced PAUSED at 404115 ms,
  and its Play action returned to PLAYING.
  [Video frame and details](device-smoke/youtube-playback.webp).
- **BLOCKED — Client-specific fallback:** Neither the client supplying playback
  nor an Android-failure/visionOS-fallback branch was observable in the player
  UI or bounded logs. No controlled client failure was injected on device.
- **PASS — Rumble watch:**
  `https://rumble.com/v5a7yh0-spacex-starship-test-flight-5-update.html`
  opened `SpaceX Starship Test Flight 5 Update`, duration 02:53; moving video
  and progress logs 19168→20166 and 54154→55158 ms; media pause at 98333 ms.
  [Watch video frame and details](device-smoke/rumble-watch.webp).
- **PASS — Genuine Rumble Short:** The public feed item
  `https://rumble.com/shorts/v7g6wz4` opened
  `hitman 2 Short No Cometary :Golden Handshake Cut scene` (02:58). Playback
  detail time advanced 00:20→00:30; media session paused at 31437 ms.
- **PASS — Query/fragment routing:** The watch URL above with
  `?next=/shorts/v7g6wz4`, with `#/shorts/v7g6wz4`, and with both still
  resolved to the **02:53 SpaceX watch**, not the 02:58 Short. The watch
  position was retained. Malformed links were not tested on device.

## Downloads and resume

- **PASS — Small normal download:** The Rumble watch's 360p MP4 used three
  threads; Downloads displayed **Finished / 100%**, 12.81 MB. The saved file
  measured **13,435,079 bytes**. Local `ffprobe` identified H.264, 640×360,
  AAC, and duration 173.035 seconds; `ffmpeg -v error -i <file> -t 2 -f null -`
  decoded successfully. Opening it through the app into VLC displayed a
  moving frame and 02:53 duration.
  [Decoded saved media](device-smoke/download-decoded.webp).
- **PASS — Parallel requests observed:** Bounded `DownloadMission` debug logs
  for the 360p mission showed workers 0, 1, and 2 issuing byte ranges and
  receiving compatible `Content-Range` responses with a total of 13,435,079 bytes.
  Final length and decode agreed. Later one-thread 1080p logs showed
  `Range=bytes=0-`, `Content-Range=bytes 0-81542670/81542671`, then
  `Range=bytes=81542661-81542671` answered with end 81542670. Those
  initial logs did **not** expose `If-Range`; the controlled transfer below
  observed conditional requests.
- **PASS — Reopen finished missions:** Leaving and reopening Downloads showed
  the completed public-media entries at **Finished / 100%**.
- **Earlier limitation, resolved below:** The 20.43 MB and 77.77 MB public
  missions finished before they could be paused. Rapid taps on a finished card
  also preceded a persistent `Loading Metadata…` overlay. Those missions did
  not test partial resume; a separate controlled transfer did.

### Controlled local-range follow-up

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

- **PASS — Active UI pause/resume (mission A):** Downloads showed Pending at
  45.80% while writing. Its own More options → Pause stopped the active worker
  at **2,629,632 / 4,194,304 bytes** (62.70%). A device harness read the
  pending mission and twice hashed the partial file, 350 ms apart: both hashes
  were `55a61ed8028851560f7c7e97232c0f95cdc43e85e99e71206e1f0a3f2bef8fb4`,
  and progress did not move. The UI showed **paused**;
  [paused mission](device-smoke/controlled-paused.webp). UI Start resumed it;
  Downloads then showed **Finished / 100%**, and an independent device check
  found the expected 4,194,304-byte length and exact source SHA-256.
- **PASS — Partial persistence across app restart (mission B):** A distinct
  active mission was paused through the UI at **2,220,032 / 4,194,304 bytes**
  (52.93%). Before and after `am force-stop` of only the debug app, the
  pending mission reported the same progress and unchanged partial SHA-256
  `3138131d5966629ce1b923d60eb4752c9639ebfc0099b0292fee3aa7cfc70fb6`.
  Reopened Downloads showed the same **paused / 52.93%** state;
  [recovered mission](device-smoke/controlled-persisted.webp). UI Start
  resumed to **Finished / 100%**; the resulting 4,194,304-byte file matched
  the source SHA-256 above.
- **PASS — Observed conditional HTTP ranges:** The fixture logged ranged
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
- **BLOCKED — Representation replacement/recovery:** No controlled version
  change, validator mismatch, refreshed URL, HTTP 200 replacement, or 416 was
  exercised. Completed downloads do not validate these paths.

A bounded app-PID error-log sample had no playback crash or extractor error
associated with the successful checks. A null-thumbnail `RealImageLoader`
`NullRequestDataException` and a `LegacyGraphicsTracker` buffer warning also
occurred; neither established a media failure. The root cause of the later
metadata overlay is **unconfirmed**: rapid taps hit a finished card and no
corresponding extraction failure was isolated. Raw device-wide logs, cookies,
tokens and notification-shade screenshots are deliberately not attached.

## Cleanup and limits

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

