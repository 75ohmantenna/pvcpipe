# Service protocol observations — 2026-10-14

FUTO's Grayjay plugins (AGPL-3.0,
<https://gitlab.futo.org/videostreaming/plugins>) were consulted as references
for service protocol behavior. Protocol facts used in the changes described
here were independently verified against the live services. PVCPipe's new
implementation and synthetic fixtures were written independently; no Grayjay
implementation code, fixtures or documentation text was incorporated.
This statement describes these changes, not an audit of historical code.

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
