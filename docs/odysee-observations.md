# Odysee observations, October 2026

A record of what Odysee's servers answered and what the app did on two devices
while the Odysee service was written. It is dated and it is not derived from
the code. [services.md](services.md#odysee) says what the code does with all
of this.

Everything was seen from one home network. The tools were `curl`, the
extractor's `OdyseeLiveTest`, a disposable emulator (AVD `pvcpipe_ci_api37`,
Android 17, API 37, x86_64, software video decoding) and a Pixel 6a.

## View counts and follower counts, 2026-10-12

- `file/view_count` and `subscription/sub_count` on `api.odysee.com` answered
  "authentication required" with HTTP 401 for a request without an
  `auth_token`, and "could not authenticate user" with HTTP 403 for a made-up
  one.
- `user/new` with an empty `auth_token`, a random `app_id` of 66 letters and
  digits and `language=en` returned a user with a 32-character `auth_token`.
  An `app_id` of another shape was refused as invalid.
- With that token both calls answered, with the token in the address of a
  `GET` or in the body of a `POST`. Asked about several claims at once, they
  returned one number per claim in the order asked, and 0 for a claim id that
  does not exist.
- `reaction/list`, which gives likes and dislikes, answered without a token.

## Media hosts, 2026-10-12 and 2026-10-13

`get` returns an address of the form
`https://player.odycdn.com/v6/streams/<claim id>/<6 characters>.mp4`.

- A `GET` without `Referer: https://odysee.com/` was answered with 401 "this
  content cannot be accessed at the moment", unless the file was in the CDN
  cache (`x-77-cache: HIT`). A cached file was served to any request. A file
  got into the cache once it had been fetched whole with the referrer. For
  the first day this made the referrer look unnecessary, because every file
  tested had just been fetched with it.
- A `GET` with the referrer returned the uploaded file and honoured ranges.
- A `HEAD` without the referrer was answered with 401, cached or not. With
  the referrer the answer was 200 for a file Odysee had not transcoded, and a
  308 redirect to
  `/v6/streams/<claim id>/<96 characters>/master.m3u8` for one it had.
- The master playlist, the rendition playlists and the segments answered
  `GET` and `HEAD` with and without the referrer. This was only tried on a
  transcoded video that had been played before.
- `cloud.odysee.live`, the livestream host, answered 403 to the master
  playlist, a rendition playlist and a segment without the referrer, and
  served all three with it. An `Origin` header in its place did not help.
- Adding `?download=true` to the file address gave a `HEAD` answer for the
  file itself, 200 with its length, also for a transcoded video. The app does
  not use this form.

## HTTP 429 for uncached files, from 2026-10-13 00:27 UTC

After roughly a hundred test requests to some fifteen videos within an hour,
`player.odycdn.com` began to answer requests for uncached files that did
carry the referrer with 429 "Try again later".

- It began after four requests to one uncached file within about two seconds.
- Both origin servers answered this way (`x-powered-by: use-p1` and
  `use-p2`).
- At first only range requests were refused and two whole-file requests
  succeeded. Later whole-file requests were refused too.
- Cached files, HLS playlists and HLS segments kept working throughout.
- It was still so after seventeen minutes without any request, after more
  than four hours, and at 09:10 UTC when the Pixel tried an uncached video.
- Asking again every fifteen seconds for six minutes did not change the
  answer.

Whether this is a limit on the address the requests came from or how the host
treats any visitor is not known. Trying an uncached, untranscoded video from
another network would tell. Odysee's own Android app waits and retries on
429, with a comment that the host answers 429 while a fresh upload is still
being transcoded. PVCPipe does not retry and reports the error.

## What was not checked

- Playing and downloading a file that is not in the CDN cache, from start to
  finish, because of the 429 answers. That such a file needs the referrer was
  seen with `curl` only: 401 without it, and the file with it before the 429
  answers began.
- An unlisted video. The extractor passes the `signature` and `signature_ts`
  of a shared link to `get`, as yt-dlp does. That was written from yt-dlp's
  code and never tried on a real unlisted video.
- "Open in external player" for an Odysee file. An outside player gets the
  address without the referrer, so by the answers above it should work only
  while the file is in the CDN cache. Nobody tried it.
- A download on the phone, and an HLS download that completes. See the two
  sections below.

## Other answers, 2026-10-12 and 2026-10-13

- `resolve` for an address that does not exist returned an entry with an
  `error` of name `NOT_FOUND`.
- `get` for a video with an LBC fee, a `c:purchase` tag or a
  `c:members-only` tag returned the error "authentication required".
- A claim search for claims with an invalid channel signature returned claims
  with `is_channel_signature_valid: false` whose `signing_channel` held only
  a `channel_id`.
- `livestream/is_live` for a channel that never streamed answered with
  success, `Live: false` and an empty `ActiveClaim`.
- The search host accepted `claimType`, `mediaType`, `sort_by` with
  `release_time` or `^release_time`, `time_filter`, `min_duration`,
  `max_duration` and `related_to`. A channel search combined with a sort or a
  duration returned nothing.
- The image service at `thumbnails.odycdn.com/optimize/s:<w>:<h>/…` scaled an
  image to the asked width and kept its proportions.

## Emulator checks, 2026-10-13

With a debug build of the final code:

- A transcoded video opened with "8,281 views" and "2.2M subscribers" and
  played. The player's default of 720p is for that video an HLS rendition.
- A 1.6 MB untranscoded file was downloaded in three parallel ranges. The
  first request was a `GET` for `bytes=0-0`, answered with
  `Content-Range: bytes 0-0/1628769`. The saved file had the checksum of the
  original.
- The Livestreams tab of a channel on air listed that broadcast and nothing
  else.

With a debug build from before the second review's fixes:

- An untranscoded video played from its file, and a livestream played
  steadily for forty seconds.
- Counts showed on a channel page, the Featured kiosk and search results.
- Switching "Show Odysee view and follower counts" off removed the token
  from the preferences, and a video page then showed likes but no views or
  followers. Switching it on again registered a new token on the next video
  page.

An HLS download of the 144p rendition fetched all 42 segments from Odysee and
then failed in the app's own last step, which runs the bundled
`libffmpeg.so` to join them. The file was not on disk
(`lib/x86_64/libffmpeg.so: No such file or directory`). That step is shared
by every service. The same download was not tried on a build from before the
Odysee service.

## Pixel 6a checks, 2026-10-13

A debug build from before the second review's fixes, installed over the
existing one with `adb install -r` after confirming that both carried the
same signing certificate. 24 of 24 checks passed.

- A transcoded video, an untranscoded video and a livestream opened with
  their counts and played.
- A channel, a playlist, the Featured kiosk and a search for "linux" opened.
  The channel, the kiosk and the search results showed counts.
- An uncached video failed with HTTP 429. The phone was on the same network
  as the emulator.
- No download was tried. The phone had no download folder set, and the
  system's folder picker is outside the app.

The app's preference file was saved before the run and written back
afterwards. The run left "linux" in the app's search history.
