# Service protocol observations — 2026-10-14

FUTO's Grayjay plugins (AGPL-3.0,
<https://gitlab.futo.org/videostreaming/plugins>) were consulted as references
for service protocol behavior. Protocol facts used in the changes described
here were independently verified against the live services. PVCPipe's new
implementation and synthetic fixtures were written independently; no Grayjay
implementation code, fixtures or documentation text was incorporated.
This statement describes these changes, not an audit of historical code.

This record describes the checks and changes made on that date. For current
service capabilities and request behavior, see [services.md](services.md).

## SoundCloud discovery

A GET of <https://m.soundcloud.com/discover> returned HTTP 200. The page's
`script#__NEXT_DATA__` contains JSON with a root `runtimeConfig` object and
its `clientId` and `buildVersion` fields. Using the discovered client id in
an anonymous `api-v2.soundcloud.com/search/tracks` request returned HTTP 200
and track results. The implementation uses the structured field; unrelated
page text cannot supply the id. Desktop asset discovery remains the fallback.

Original synthetic tests cover malformed and missing configuration, an
unexpected configuration type, invalid identifiers, unsuccessful responses,
network failures, socket timeouts, caching and cancellation. No live client
identifier or captured page is included in the fixtures.

### Offline stream replay follow-up — 2026-10-24

The five recorded `SoundcloudStreamExtractorTest` groups lacked the mobile
client-discovery request before and after PR #156. Their 198 failures stopped
before extraction assertions; they did not establish a redaction regression.

Each group now includes a synthetic HTTP 200 response for
`GET https://m.soundcloud.com/discover`, with the minimal
`script#__NEXT_DATA__` JSON shape consumed by
`SoundcloudParsingHelper.clientId()`: a root `runtimeConfig` object and a
32-character `clientId`. This shape is supported by the implementation,
the original synthetic discovery tests and the dated observation above.
The new page is synthetic, not a new recording. Its identifier is
`0123456789abcdef0123456789abcdef`, already used by the discovery tests.
The same identifier replaces the anonymous client id in these five groups'
request URLs, response `latestUrl` fields and two related-item `next_href`
values. Track metadata, media authorizations and extraction expectations are
unchanged. Production discovery runs normally; no client id is injected into
its cache.

Replay uses `MockDownloader` and exact `Request.equals` matching: method,
URL, headers, payload bytes and localization. The discovery wrappers use
`en-GB` localization and `Accept-Language: en-GB, en;q=0.9`, matching the
existing group requests. A missing mock throws without a live fallback.
`SoundcloudStreamExtractorTest` is now tagged `offline`; `forkCiTest`, which
is part of `make ci`, fixes the factory downloader to `MOCK` even if the
invoking JVM requests another downloader.

Serial runs in fresh test JVMs with separate logs and reports produced:

| Suite | Passed | Failed | Existing skips |
| --- | ---: | ---: | ---: |
| CreativeCommonsOpenMindsEp21 | 40 | 0 | 0 |
| SoundcloudGoPlusTrack | 39 | 0 | 1 |
| SoundcloudGeoRestrictedTrack | 39 | 0 | 1 |
| SmallTrackId | 40 | 0 | 0 |
| LongTrackId | 40 | 0 | 0 |

Each fresh-JVM suite completed discovery, and all 198 enabled cases executed
their existing extraction checks. The three playable tracks exercised audio
extraction; the two restricted tracks asserted empty audio-stream lists.
The normal combined gate can reuse the cached client id between groups;
each group's discovery wrapper was verified by its isolated run. The two
restricted tracks' related-item tests remain disabled as before. No newly
exposed missing fixture, obsolete expectation or production defect was observed.
These results establish the enabled recorded extraction paths, not current
live-service availability, media downloads or playback. Existing duplicate
transcoding request keys in the Creative Commons group remain unchanged;
this correction adds one unique discovery key per group and does not resolve
historical response-selection ordering for those duplicates.

The fixture sanitizer checked all 685 wrappers without further replacements.
A scoped comparison found none of the 25 original sensitive values detected
in the five pre-redaction groups in the repaired fixtures. The client-id remap
preserved existing request-key equivalence classes: no merges or splits, and
no collisions with the five added discovery keys. Other fixture groups were
unchanged.

## YouTube channel cards

The live <https://www.youtube.com/@YouTube/videos> response contained 30
metadata parts identified by `leadingIcon.name = PLAY_ARROW_OUTLINED`.
One collaboration card placed creator credits and an additional-creators
label before that part. Its count used compact numeric text, while the
accessibility label included the localized word for views. PVCPipe now
selects the icon-marked count and retains the positional fallback for older
layouts. Synthetic tests check collaboration credits, upload dates, older
layouts, unknown icons and empty counts.

## Odysee recommendations and counters

An anonymous request to `lighthouse.odysee.tv/search` provided a current claim
id. A GET of `recsys.odysee.tv/search` with `s`, `related_to`, `from`, `size`,
`free_only=true` and `nsfw=false` returned HTTP 200 and a JSON array containing
`claimId` and `name`. No visitor token or user id was supplied. A request
without `related_to` returned HTTP 400. Recommendation failures fall back to
the existing search path; cancellation does not start that fallback.

The counter request protocol is unchanged from [the earlier observations](odysee-observations.md).
The cache is a PVCPipe policy: at most 512 successful counters, separated by
endpoint, expiring five minutes after insertion using a monotonic clock.
It is never persisted. Tests cover expiry, eviction, endpoint separation,
partial hits, invalidation and responses completing after invalidation.

## Rumble challenge classification

A GET of <https://rumble.com> returned HTTP 403, `cf-mitigated: challenge`,
HTML containing a `Just a moment` title, and a `challenge-platform` script.
The interceptor uses that header or bounded inspection of those HTML markers
before opening its WebView. It never converts a POST into a WebView GET or
reports an empty or still-challenged WebView response as HTTP 200.

Synthetic tests check ordinary refusals, lookalike hosts, redirects outside
Rumble, POST requests, failed challenges and response-body ownership.
The network remained challenged, so this is not evidence of successful
Rumble playback or of TLS browser impersonation. No native HTTP replacement
or IP-discovery service was added.

## BitChute public playlists and live playback

The live website's `js/app.d1922799.js` confirmed the public playlist and
playlist-video POST endpoints under `https://api.bitchute.com/api/beta/`.
An anonymous request for public playlist `RIFWcsKn7jt0` returned HTTP 200,
`is_public: true`, metadata identifying its owner as a profile, and
`video_count: 64`. The videos response wraps each video in a row containing
`position` and `video`. Requests with offsets 0 and 2 and limit 2 returned
ten rows starting at positions 0 and 2 respectively. The extractor therefore
advances by the actual row count, without dropping intentional duplicates.
These requests also succeeded using PVCPipe's default user agent and no
account cookies. Captured content is not included in the fixtures.

The live site's code and a POST to `/api/beta9/cache/livestreams` confirmed
a `videos` array whose live entries have `state_id: live`, a null publication
date and duration `0:00`. For one such entry, `/api/beta9/video` confirmed the
live state, but `/api/beta/video/media` supplied a placeholder MP4 address.
The video's `/embed/<id>` page instead assigned an HTTPS `.m3u8` address to
`media_url`. That address returned HTTP 200 with an HLS master containing five
renditions, including when requested with PVCPipe's default media headers.
This verifies source extraction and manifest access, not playback on a device.

Synthetic regression tests cover URL ownership, malformed query strings,
private playlists, empty and malformed pages, foreign continuation pages,
deleted and duplicated entries, server-adjusted page lengths, live media
selection, missing manifests and nullable live publication dates.

## Scope

Grayjay's Widevine sources and YouTube UMP/SABR request executors require
separate player architecture work. These changes keep PVCPipe's current
account-free service model and do not add a Grayjay runtime dependency.

## Kick follow-up — 2026-10-18

The [Grayjay Kick plugin](https://gitlab.futo.org/videostreaming/plugins/kick)
was consulted for protocol ideas. Its license is AGPL-3.0. The new Java
implementation and synthetic fixtures were written independently; no
Grayjay implementation code was incorporated.

Direct workstation `curl` and Python HTTP requests, using PVCPipe's
Chrome/145 user agent, returned HTTP 200 for public channel metadata,
current channel search, Featured, current VOD details and the dedicated
live playback-URL endpoint. Missing-channel and missing-video probes
returned JSON with HTTP 404.

Both a current live broadcast and an xQc recording supplied HLS masters
whose rendition playlists and a media segment returned HTTP 200. These
direct requests worked without Origin/Referer; repeating the master
requests with Kick Origin/Referer also returned HTTP 200. No global Kick
media-header workaround was added. This describes this network and these
requests, not every media host or future access policy.

The current VOD resource uses a different UUID from the older video API
for the same recording. Current durations are seconds; older
`livestream.duration` values are milliseconds. The channel video list also
contains an ongoing broadcast with `is_live: true` and an empty
`recording_url`; it is not a completed downloadable VOD.

Live tokens observed in channel and fresh playback responses expired about
ten minutes after issuance. The current website's playback query uses a
294-second stale time and 300-second garbage-collection time. Legacy
channel metadata can retain a playback URL while the channel is offline.
It also omits viewer-count visibility even when the Featured response for
the same channel says `show_view_count: false`.

Current search returned a real legacy channel with slug `@xqc`; both its
channel API and website route remained available. The first JVM smoke
successfully fetched live media but reported a search-item parsing error
for this slug. The slug grammar was corrected rather than hiding the item
or ignoring the extraction error.

The current website negotiates chat transport with
`POST https://web.kick.com/api/v1/realtime/connection` and the corresponding
`/api/v1/realtime/channels/{channelId}/chat/connection` endpoint. Direct
anonymous probes offering both Pusher and Centrifugo returned HTTP 200
and selected `mode: websocket`, provider `centrifugo`. The site code retains
the `chatrooms.{chatroomId}.v2` topic and `App\Events\ChatMessageEvent`.
This is not evidence that Grayjay's older hard-coded Pusher endpoint is a
universal current transport. No WebSocket chat session was exercised, and
no chat UI or viewer-tracking connection was added to PVCPipe.

Separate adversarial correctness and security reviews found live-source
list, viewer-visibility, feed-date/identity, ongoing-recording, ordinary-403,
and encoded-query-boundary defects. They were corrected with regressions,
including stale broadcast rejection and preserving query-value boundaries.
Execution also caught a private-content exception being swallowed by the
older-UUID fallback; that fallback is now restricted to a genuine HTTP 404.

After those corrections, all 48 Kick regression cases passed with
Checkstyle enabled. A throwaway JVM runner using the real OkHttp test
downloader exercised complete `KioskInfo`, `StreamInfo`, `SearchInfo`,
`ChannelInfo` and `ChannelTabInfo` models: Featured → sjc_official live
playback, and search → xQc → a 33,751-second VOD. Both live and VOD masters,
one rendition and one media segment returned HTTP 200; each master had five
video renditions. The missing-channel scenario remained typed unavailable.
This establishes JVM extraction and media access, not hardware playback.

The [API 26 UI smoke](pvcpipe-device-smoke.md#kick-service-smoke-2026-10-18)
records rendered live/VOD playback, service selection, search, channel tabs
and local subscription interaction, together with the untested paths.

### Image metadata follow-up — 2026-10-18

Kick artwork originally supplied neither dimensions nor an estimated quality,
so the Description tab labeled thumbnails and uploader avatars `?x?`.
Direct image downloads succeeded with both the app's ordinary OkHttp user
agent and the browser user agent; this was not evidence for adding image
Referer headers or bypass logic.

Live CDN WebP variants measured 1920×1080, 1280×720, 853×480, 640×360 and
284×160 for filenames `1080`, `720`, `480`, `360` and `160`, respectively.
The observed srcset width descriptors for the 480- and 360-high variants
were incorrect, so the extractor records only the filename's known height.
Converted avatar widths measured 150 for `medium` and 350 for `fullsize`.
Fullsize samples included 350×350, 350×438 and 350×387; assuming square
avatars would be wrong. Original search avatars need not share these sizes.
Two current VOD preview endpoints returned 1280×720 images; these receive
an estimated High quality classification while retaining unknown exact axes.

All 61 Kick regression cases passed, including 13 image-metadata cases
covering partial dimensions, portrait avatars, quality estimates and
unrecognized or misleading URLs. The seven new metadata expectations failed
before the correction. `make ci` then passed with debug/release assembly.
An API 26 emulator running the fixed debug build visibly rendered the
cazmilligan VOD artwork and avatar; its Description tab displayed
`THUMBNAILS High quality` and `UPLOADER AVATARS 350x?`. This is emulator
UI evidence, not a new physical-device playback claim.

### Refresh and seek follow-up — 2026-10-18

The `0.2.3` review found that a retained Livestreams tab handler kept its
embedded channel snapshot indefinitely. A standalone runner using complete
`ChannelInfo` and `ChannelTabInfo` models exercised one handler across
offline → live → offline fixture responses. The frozen `0.2.2` extractor
returned item counts `0 → 0 → 0`; the corrected extractor returned
`0 → 1 → 0`.

The same runner opened a retained recording with `?t=25`. The old extractor
returned start position zero; the correction returned 25 seconds without
changing the canonical recording URL. Regression execution also exposed
oversized offsets that would overflow playback's time conversions. Invalid
or unrepresentable optional offsets now default to zero; they do not make
an otherwise playable recording unavailable. Live playback ignores these
recording offsets.

These are controlled extraction scenarios, not new live-site availability
or hardware-playback evidence. The
[0.2.3 runtime record](pvcpipe-device-smoke.md#023-adversarial-review-2026-10-18)
describes the separate casting and proxy checks.

