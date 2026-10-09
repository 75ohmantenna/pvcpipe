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
