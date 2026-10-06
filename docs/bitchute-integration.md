# BitChute integration and verification

PVCPipe connects anonymously to BitChute through the bundled Java extractor (service ID 5).
The root Gradle composite build uses the extractor source in this repository. `DownloaderImpl`
sends requests with OkHttp, the application's browser user agent, and its cookie jar. No API key
or BitChute account is required for the public interactions below.

## Website request paths

The current BitChute homepage is a JavaScript application. Its `app.d1922799.js` bundle was
inspected on 2026-10-06, along with live JSON responses. The endpoints are website implementation
details, rather than a documented, versioned public API contract.

| Interaction | Request |
| --- | --- |
| Trending Today/Week/Month | POST `https://api.bitchute.com/api/beta9/videos`, with `selection` set to `trending-day`, `trending-week`, or `trending-month`. |
| Popular and Suggested | The same endpoint, with distinct `popular` and `suggested` selections and offsets in steps of 20. |
| Video search | POST `/api/beta/search/videos`, with `query`, `offset`, `limit`, and sensitivity, sort, and duration filters. |
| Channel search | POST `/api/beta/search/channels`; video duration and sort filters are omitted, matching the website. |
| Suggestions | POST `/api/beta/search2/videos/autocomplete`; the response is an array of messages with highlighted HTML. |
| Channel details | POST `/api/beta/channel`, with `channel_id` set to the public ID or slug. |
| Channel Videos tab | POST `/api/beta/channel/videos`, using the resolved channel ID and offsets in steps of 20. |
| Video details | POST `/api/beta9/video`, with `video_id`. |
| Playback and download URL | POST `/api/beta/video/media`; the response supplies `media_url` and `media_type`. |
| View/like/dislike counts | POST `/api/beta/video/counts`. |
| Related videos | POST `/api/beta9/videos`, with the `suggested` selection. |
| Comment authorization | POST `/api/beta/apps/commentfreely/video/`, with `video_id`; the response supplies a signed anonymous `auth` token. |
| Comments | Form POST `https://commentfreely.bitchute.com/api/get_comments/`, with `cf_auth` and `commentCount=0`. Omitting `isNameValuesArrays` requests ordinary JSON comment objects. |

All paths without a host above use `https://api.bitchute.com`.

The media response currently supplies a single URL, rather than a list of selectable qualities.
The extractor recognizes progressive MP4/WebM/3GPP and HLS delivery. Background and popup modes
use the application's player with that same stream. Separate audio-only tracks, subtitles,
playlists, subscription import, and live BitChute streams are not implemented. Subscriptions,
queues, history, and saved playlists are local app features; they do not sign in, follow, vote,
or post comments on the website.

## Compatibility corrections

- Comments obtain a fresh API auth token instead of searching the SPA's HTML for a legacy
  `cf_auth` script. Authorization and HTTP failures propagate instead of appearing as an empty
  comment list. Fresh tokens avoid retaining an expired signed token.
- Root comments and replies use the API's `parent` IDs. Reply pages carry the JSON snapshot and
  original video URL so a fresh extractor can display replies and nested replies. Comments whose
  parent has been deleted remain visible at the root.
- Absolute comment avatar URLs remain intact. Relative and protocol-relative URLs are resolved
  correctly. Comment dates accept ISO offsets with variable fractional precision or no fraction.
- Suggested requests its own feed rather than Popular.
- Each search request uses the filters stored in its own link handler. Starting a Channels search
  no longer changes the endpoint or filters used when an earlier Videos search loads another page.
  Exact multiples of 20 stop at the reported total instead of offering an extra empty page.
- BitChute's current sensitivity reference omits `safe`, and a live search with that value returned
  HTTP 400 (`safe invalid`). The UI no longer offers it. Old saved Safe filters use `normal`, the
  lowest sensitivity currently accepted by the website.
  Video age labels follow the current BBFC reference values: Normal 12, NSFW 15, and NSFL 18.
- Suggestions use the current autocomplete endpoint and strip highlight tags, decode entities,
  and remove duplicate/empty messages. Channel search descriptions also display decoded text.
- A channel tab reopened without cached channel metadata resolves the channel through the API.
  Missing upload dates are treated as unknown rather than throwing a null-pointer exception.
- Channel links validate HTTP(S) and the exact BitChute hostname, matching stream link validation.
  Android VIEW routing includes `old.bitchute.com`, torrent links, and the website's current
  `/api/beta9/embed/` and `/api/beta9/selfembed/` links, alongside legacy video/embed/channel links.
- API error parsing checks all error entries, including a geographic restriction following a
  generic HTTP error. HTML server errors preserve their HTTP status. Recognizable HTTP 403
  Cloudflare challenges raise a dedicated challenge exception.
- BitChute's seed CDN closed connections using the app's Chrome/145 user agent without sending
  an HTTP response. This reproduced in Android's player and in desktop curl/requests probes;
  Firefox/128, mobile Chrome/124, Chrome/146, and the default requests user agent returned HTTP
  206 for the same media URL. Playback and download requests now use the verified Firefox
  identity on exact `seed[a-z0-9]+.bitchute.com` hosts. Other hosts retain the app's existing
  identity. Player headers are resolved for each media request, including HLS segments.

## Verification

Live website/API investigation and Android 15 and Android 8 emulator checks were performed on
2026-10-06.

All five browse selections returned HTTP 200 with 20 video objects. Video and channel search,
autocomplete, channel metadata, video metadata, media, counts, and comment authorization returned
the expected JSON shapes. A current video returned 18 comments including two parent/child links.
An older telescope video returned an empty comments array, which is a valid empty result.

Relevance, Newest, Oldest, all four duration filters, and Normal/NSFW/NSFL searches returned video
results. A burst of filter probes initially encountered HTTP 429 Cloudflare challenge HTML;
the remaining filters returned HTTP 200 when retried later with requests spaced apart.
The application has no verified
BitChute challenge recovery flow: the Rumble WebView interceptor is restricted to Rumble hosts,
and the existing reCAPTCHA cookie flow handles YouTube cookies. A public request succeeding does
not prove challenge recovery, access from another region, or access to private/restricted content.

The existing Android 15 AVD `pvcpipe_rumble_api35` was used with the debug APK. Cold boot initially
caused System UI and application startup timeouts; after recovery, the app loaded BitChute browse
lists, search results, autocomplete suggestions, and channel search cards with thumbnails.
Playback checks enable the emulator's existing “Show age restricted content” preference because
the extractor marks ordinary BitChute content with an age limit. This preference is restored
after verification.

The Android 8 AVD `ffread_review_api26` verified playback of video `e-eScuPupHE`: ExoPlayer
reached READY, Android reported PLAYING, the timer advanced, and video frames were rendered.
A three-thread download completed with HTTP 206 range responses and saved the expected
9,819,310-byte MP4. `ffprobe` identified 640×360 H.264 video and AAC audio, duration 175.82
seconds; a complete `ffmpeg` decode finished without errors. The app also displayed a current
video’s comments, avatar images, and relative dates. Its “1 reply” action opened a reply page
and the fresh extractor returned the child comment. A direct channel link resolved JohnLocksley’s
channel and loaded its Videos tab with titles, durations, dates, views, and subscriber count.
About displayed the channel description and links; scrolling past the first 20 videos loaded
additional videos. The current `/api/beta9/embed/` URL routed to the video. Background
playback remained PLAYING after leaving the app for the launcher. Popup playback also reached
READY/PLAYING with overlay permission temporarily enabled; that permission was restored afterward.
The emulator used the media-fix build. The final BBFC age-label adjustment was checked with
parameterized tests and included in the final APK build.

Initial workspace verification passed 169 deterministic extractor tests and 283 app unit tests,
including the separately preserved Rumble changes. The initial broader BitChute run contained
123 tests with one existing disabled test and no failures. After the adversarial fixes, the clean
PR tree passed 166 deterministic extractor tests and 283 app unit tests, both app and extractor
Checkstyle, and the debug APK build. The final broader BitChute suite contained 137 tests with
one existing disabled test and no failures.

The deterministic regression suite covers search isolation and pagination, channel-tab metadata
recovery, autocomplete, current embeds, invalid hosts, retired sensitivity values, comment auth,
avatars/dates, root/reply/nested-reply routing, geographic errors, challenges, and server errors.
The broader BitChute suite also includes live legacy search/channel samples; a disabled default
filter test remains disabled.

Run the reproducible checks and APK build:

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest :app:testDebugUnitTest :app:runCheckstyle :app:assembleDebug
```

Run the broader BitChute suite separately (it includes live requests):

```sh
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.bitchute*'
```

## Adversarial review

The PR review added regressions for these compatibility gaps:

- A torrent URL contains the channel ID followed by the video filename. Extracting the first
  ID opened the wrong video. The handler now extracts the video ID from `.webtorrent` filenames,
  and the earlier test that expected the channel ID was corrected.
- The media-host match excluded the documented `seedp29xb.bitchute.com` CDN host. The match now
  accepts alphanumeric seed names on the exact BitChute domain and still rejects lookalike hosts.
- Counts and Suggested were mandatory during stream extraction. A 503 from either endpoint
  prevented playback despite successful video/media requests. Those failures now surface through
  the optional metadata getters, which `StreamInfo` records without discarding playable streams.
- The SPA also supports root channel slugs such as `/bitchute`. Shared and pasted root slug URLs
  now resolve to channels, while the SPA's reserved root routes are rejected. Channel identifiers
  accept URI unreserved characters, including dots. Android link interception retains the specific
  video/embed/torrent/channel prefixes rather than claiming every BitChute web page.

The review checks use a clean PR tree with the separate Rumble work preserved outside it.
The root `bitchute` slug was also resolved through the live channel API (HTTP 200).

## Sources

- [BitChute's current website](https://www.bitchute.com/)
- [Inspected website JavaScript](https://www.bitchute.com/js/app.d1922799.js)
- [yt-dlp BitChute implementation](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/bitchute.py)

The endpoint and schema observations above were verified directly against BitChute's website
JavaScript and responses. They should be rechecked when the website bundle or API changes.
