# Rumble integration and verification

PVCPipe connects to Rumble through its bundled extractor, using public website HTML and the
endpoints used by Rumble’s web player. There is no Rumble account integration or API key.
Website access depends on Rumble’s response to the client and network: a successful embed or
Shorts request does not establish that search, channels, or every video is accessible.

## Requests and supported interactions

`DownloaderImpl` supplies OkHttp requests, a browser user agent, and a cookie jar. When enabled
in Settings → PVC settings → Handle Rumble cloudflare challenge, the Rumble interceptor asks
the challenge service to load challenged requests through Android WebView. Interactive challenge
handling is a separate setting. A functioning WebView provider is required.

| Interaction | Request and parsing |
| --- | --- |
| Browse | Editor Picks (`/editor-picks`), Live (`/browse/live`), Battle Leaderboard (`/battle-leaderboard`), Trending Today (`/videos?sort=views&date=today`), and Latest (`/videos?date=this-week`) use HTML cards and `link[rel=next]`. A separate Shorts kiosk uses the Shorts feed below. |
| Video and channel search | `/search/video?q=…` and `/search/channel?q=…` use HTML; supported filters build query parameters. |
| Suggestions | `/service.php?name=search.autocomplete&api=8&query=…` returns channel and category titles. |
| Channels and users | `/c/{slug}` and `/user/{slug}` use HTML for channel details and their Videos (`/videos`) and Live (`/livestreams`) tabs; linked About pages provide descriptions. The service does not expose a channel Shorts tab. |
| Watch and embed links | Watch HTML supplies an embed ID through an iframe, script URL, structured data, or inline `Rumble("play", …)`. Embed links supply the ID directly. |
| Video and audio playback | `/embedJS/u3/?request=video&ver=2&v={embedId}` supplies formats, metadata, and captions. HLS playlists and progressive streams come from the returned media URLs. |
| Shorts | `/service.php?name=shorts.feed&offset=…&limit=10&api=7&options=video.full%2Cvideo.related_video` supplies the feed. `/shorts/{permalink}` embeds `rum-shorts` JSON for playback. |
| Comments and replies | `/service.php?video={internalIdInBase36}&name=comment.list` returns HTML and CSS in JSON. Pagination and replies use that response body locally. Shorts resolve the internal numeric video ID from their JSON; it differs from the public permalink ID. |
| Live viewer count | For a non-embed watch page, `https://wn0.rumble.com/service.php?video_id=…&viewer_id=…&name=video.watching-now` can supply a count independently of playback; an unavailable count is reported as unknown. |

Playlists and subscription import are not implemented by the Rumble service. App subscriptions,
bookmarks, history, and queues are local features; they do not follow, vote, or post comments on
Rumble. Account and Premium access cannot be verified through an anonymous request.

## Corrections

Android’s Rumble link filter now accepts `/shorts/` and `/user/` links, matching the URL types
already supported by the extractor. Previously Android could not route these website links into
PVCPipe through its normal VIEW intent.

HTML and JSON entry points now validate HTTP errors before parsing. A Cloudflare 403 raises the
existing actionable challenge exception instead of becoming an empty list or a JSON parser error.
Other HTTP errors also fail explicitly. Search, browse, and channel pagination resolve relative
next links against the final response URL, including after redirects.

Channel search accepts legacy `channel-item` cards, subscribe/notify metadata, and current
profile links with headings. It skips unrelated articles, excludes verification badge labels from
channel names, and handles channels without image avatars.

The Shorts feed now advances through offsets 0, 10, and 20, stopping after an empty response.
Previously, the initial next-page calculation skipped offset 10 and never stopped on an empty feed.
Channels without a banner return an empty image list. Channel About links resolve against their
page URL and receive the same response validation.

Inline player scripts can supply embed IDs. Embedded-video comments use the supplied embed ID
without fetching a watch URL with a different ID. Shorts comments convert the internal numeric
ID to base 36. Comment requests run during `fetchPage`, so access errors propagate before the
extractor reports comments as disabled. Comment item URLs point to the original video so the app
can create a fresh extractor for replies; the internal `service.php` endpoint is not an accepted
video link. UTF-8 encoding preserves comment text between pages.

## Verification

Observations from the investigation on 2026-10-05 and 2026-10-06:

- Direct requests to the homepage and video search returned Cloudflare HTTP 403 challenge HTML.
- The public embed sample `https://rumble.com/embed/v5pv5f/` returned metadata and MP4/WebM
  media URLs with a plain curl request. The application’s browser user agent received HTTP 403
  for the same endpoint in a separate request. These results are client dependent.
- Channel search required retaining cookies across a self-redirect before returning its current
  HTML. The current cards use profile links and headings without subscribe/notify metadata.
- Autocomplete for `space` returned JSON channel results. The Shorts feed returned ten items;
  the first item’s Shorts page contained the expected `rum-shorts` JSON.
- A comments request using the first Short’s internal ID returned four comments and reply markup.
- Android 8 emulator navigation selected Rumble and loaded the Battle Leaderboard. An embed
  playback attempt reproduced the HTML-as-JSON parser error; an updated APK later displayed
  the sample video's metadata and playback actions.
- Android 15 (`pvcpipe_rumble_api35`) booted with WebView 124. The final APK resolved Shorts
  and user-channel VIEW links. Live video search, suggestions, the Channels filter, channel cards,
  and a channel's Videos, Live, and About tabs worked.
- A live Short displayed three root comments and its child reply. The earlier APK rejected the
  reply's API URL; the final APK displayed the reply after the comment item URL correction.
- Shorts, a direct embed link, and regular watch-page playback reached Android's PLAYING state
  and advanced. Pause and seek worked. Switching the regular sample from 720p to 360p produced
  a 640×360 video track and resumed playback. Background audio remained active with the launcher
  visible; popup playback displayed video over the launcher, including replay after completion.
- A 360p MP4 download completed through HTTP 206 range requests. The saved file contained
  13,435,079 bytes; ffprobe identified H.264 video, AAC audio, 640×360 resolution, and a duration
  of 173.035 seconds. The sample was
  `https://rumble.com/v5a7yh0-spacex-starship-test-flight-5-update.html`.
- A current public livestream displayed the LIVE badge, a viewer count, and comments, rendered
  video, and reached Android's PLAYING state. Its player JSON reported `live: 2` and supplied
  an HLS playlist URL, with no progressive MP4/WebM URLs.
- That regular video's comments API returned no root comment list. The app's disabled-comments
  display matched the response.

The API 26 image had no usable WebView provider, and API 37 startup attempts encountered a
system ConnectivityService boot timeout. API 35 resolved the provider and startup limitations,
although first-boot System UI stalls and slow UI automation required recovery and fresh snapshots.
The successful Android 15 search/channel/watch/embed requests returned HTTP 200 with challenge handling
enabled. Those requests do not establish that a real Cloudflare challenge was solved.

The offline regression tests cover challenged browse/search/channel requests, JSON endpoints,
HTTP errors, redirected and relative pagination, Shorts offsets and termination, absent banners,
inline embeds, embedded-video comments, Shorts comment IDs, and comment pagination/replies.
Existing stream tests cover progressive media, HLS variants/fallbacks, audio, captions, and live
states. These deterministic checks do not prove that every current Rumble page has compatible
markup or that media actually plays on every device.

The original investigation checks passed: 142 offline extractor tests (40 for Rumble),
281 application unit tests, and 283 tests in the broader Rumble suite. The broader suite includes
historical fixtures and live browse checks for all five categories. Historical fixtures were
repaired for missing empty About pages and current image representations; test setup no longer
mutates the global downloader type.
The debug APK built successfully.

Run the deterministic checks and build the debug APK:

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest :app:testDebugUnitTest :app:assembleDebug
```

The broader Rumble suite includes historical mocks and live requests:

```sh
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.rumble*'
```

Remaining device coverage includes every search filter combination, search/channel scrolling
past page one, captions, sharing and opening links through the system chooser, and recovery
from an actual Cloudflare challenge. A channel Shorts tab is not exposed by the extractor.
The sampled player metadata did not supply captions. The automated suite also covers pagination
and media-format behavior, including live states and HLS; those checks are separate from the
successful device playback samples. Premium/private content requires its own access checks;
anonymous failures do not establish a parser defect.

Android lint analysis was canceled after a prolonged stall. The debug build, extractor Checkstyle,
unit tests, and on-device link resolution passed; a completed Android lint run is not claimed.
The test emulator was stopped after verification. The `pvcpipe_rumble_api35` AVD and its installed
debug APK remain available for repeat checks.

## Adversarial PR review

The PR review reproduced five failures with offline regression tests before applying fixes:

- Comment routing matched `/embed/` or `/shorts/` inside query parameters and selected the
  wrong internal video ID. Routing now checks the parsed URL path while keeping the original
  watch URL for fetching and comment-item links.
- Inline player extraction rejected script attributes and single-quoted `play` calls. It now
  reads script data through Jsoup and accepts either quote style, including multiline scripts.
- An unavailable About page aborted a channel that had otherwise loaded successfully. About
  failures now surface through the description getter as nonfatal `ChannelInfo` errors; the
  original challenge exception is preserved.
- Channel cards without optional subscriber counts or avatars aborted search. Missing counts
  now use the unknown-count value, and missing image avatars return an empty image list.
- An unrelated heading link before a profile link hid the channel. Extraction now selects the
  profile link and keeps nested heading text while removing verification SVG labels.

`RumbleAdversarialReviewTest` contains the five reproductions. All five failed on the initial
PR tree. The repository-instruction change is reviewed and submitted separately.

After the fixes, all 188 offline extractor tests (45 for Rumble), all 288 tests in the broader
Rumble suite, and all 283 application unit tests passed with no failures or skipped tests.
Both extractor and application Checkstyle passed, and the debug APK built successfully.

## References

[Rumble’s embedding guide](https://rumble.support/en/help/rumble-basics-how-to-embed-your-video)
describes hosting the Rumble player on other websites. The endpoint, format, live-state, URL, and
inline-player behavior can also be compared with the
[current yt-dlp Rumble extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/rumble.py).
PVCPipe’s own implementation is under
`pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/rumble/`.
