# Services

A service is a website PVCPipe can browse and play from. This guide describes
implemented extraction capabilities and their limits. Dated responses and
live checks are recorded separately in [odysee-observations.md](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/odysee-observations.md)
and [service-protocol-observations.md](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/service-protocol-observations.md).

## How a service is put together

The extractor library in `pvcpipe-extractor/` is part of the Gradle build
(see [building.md](building.md)).
[`ServiceList`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/ServiceList.java)
registers the services. Each one is a
[`StreamingService`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/StreamingService.java)
with two kinds of parts:

- Link handler factories recognize supported addresses, identify streams,
  channels or playlists, and extract their IDs.
- Extractors fetch service data and populate the models shown by the app:
  streams, channels, channel tabs, playlists, search results, suggestions,
  comments and kiosks. A kiosk is a browse page such as Trending.

A service implements only the capabilities it supports. Extractors make
requests through the downloader supplied by the app
(`DownloaderImpl`). The player (`PlayerDataSource`) and the file downloader
(`DownloadMission`) make their own requests for the media addresses the
extractors return.

## The services

| Service | ID | Media | Kiosks, default first | Playlists | Service subscription import |
| --- | --- | --- | --- | --- | --- |
| YouTube | 0 | audio, video, live | `live`, `trending_podcasts_episodes`, `trending_gaming`, `trending_movies_and_shows`, `trending_music` | yes | yes |
| SoundCloud | 1 | audio | `New & hot` | yes | yes |
| BitChute | 5 | video, live | `Trending Today`, `Trending This Week`, `Trending This Month`, `Popular`, `Suggested`, `live` | yes | no |
| Rumble | 6 | audio, video, live | `Today's Battle Leaderboard Top 50`, `Editor Picks`, `Live`, `Trending Today`, `Latest`, `Shorts` | no | no |
| Odysee | 7 | audio, video, live | `Featured`, `Trending`, `live` | yes | no |
| Kick | 8 | video, live | `Featured` | no | no |

YouTube, SoundCloud, BitChute, Rumble and Odysee have comments. All six have
search and search suggestions. Rows follow service-picker order. Kiosk names
in the table are code identifiers; `KioskTranslator` supplies localized display names.

The main drawer contains local-library shortcuts, Settings and About, plus the
service selector. It has no service-specific Live/Trending browse section.
Kiosks remain available as main-page tabs through **Settings → Content →
Main page content**; removing their drawer shortcuts does not remove saved tabs.

These are implemented extractor capabilities, not a promise that every
website or every item is available on a particular network.

YouTube also has a feed extractor. The feed refresh uses it in place of
loading each channel when the dedicated feed setting
(`feed_use_dedicated_fetch_method_key`) is on. The other services have none,
so a refresh loads channel metadata and the stream tabs selected by the feed
tab preferences. This may include multiple tabs; it is separate from which
tabs are visible on the channel screen (`ChannelTabHelper`).

PVCPipe has no service sign-in and cannot follow accounts, vote or post on a
service. Local subscriptions do not change a service account. Subscriptions,
saved playlists, history and queues are stored locally
(see [library-data.md](library-data.md)). Optional Odysee count lookups register
an anonymous visitor token; see [Odysee](#odysee).

`DownloaderImpl` translates HTTP 429 to `ReCaptchaException` only when the
**final response URL** passes `ErrorInfo.isCaptchaSupportedUrl`. The shared
YouTube/Google host allowlist also controls whether `solvableCaptchaUrl` offers
the challenge screen. Other services receive the intact status, headers and
body so their extractor can classify the response. YouTube cookies remain
YouTube-only. Kick reports ordinary 429 as rate limiting and ordinary 403 as
refusal; explicit security-challenge headers/HTML remain challenge errors.
Neither classification bypasses a challenge or adds retries.

"Hide paid content videos" in the PVCPipe settings sets a switch in the
extractor for YouTube, Rumble, Odysee and Kick (`PvcExtractorSettings`). When
enabled, their lists omit content identified as requiring membership or payment.

Online video lists, grids, cards and compact related-video rows show a purple **PAID**
thumbnail badge when the extractor reports membership or payment restrictions.
The badge replaces the duration or live label, following PipePipe's presentation,
and hides watched progress. It does not grant access to restricted videos.
Unknown availability gets no paid badge. Local history, saved playlists and feed
rows do not persist availability, so this badge applies to online results only.

## Search filters

A service declares its search filters in a class that extends
`BaseSearchFilters`: content filters, such as videos or channels, and for each
content filter the sort filters that go with it. Every filter item has an id
that is unique within its service, and the groups list items in the order the
app shows them.

| Service | Content filters |
| --- | --- |
| YouTube | all, videos, channels, playlists, and for YouTube Music songs, videos, albums, playlists, artists |
| SoundCloud | all, tracks, users, playlists |
| BitChute | videos, channels |
| Rumble | videos, channels |
| Odysee | all, videos, tracks, channels |
| Kick | all, live, channels |

Rumble, SoundCloud and Odysee build the sort part of a search address with
`QuerySortFilterItem`. When changing a filter class, check the displayed order
and the exact address or request the search produces. The filter tests, such
as `RumbleFiltersTest` and `OdyseeFiltersTest`, pin both.

## YouTube

YouTube's general Trending kiosk is retired; live and category feeds remain.
Settings migration 9 → 10 removes dedicated general-Trending tabs, preserving
other tabs and restoring defaults only when none remain.
`YoutubeStreamExtractor` tries Android, an embedded-web fallback for age
restriction, visionOS, and optionally iOS. The app supplies proof tokens only
through the web-client provider; see [playback.md](playback.md#youtube-proof-tokens).
A `login_required` response mentioning a bot becomes `SignInConfirmNotBotException`.

### JavaScript compatibility regression coverage — 2026-11-13

The deterministic `forkCiTest` gate now runs exact-output throttling-function
shape tests instead of the disabled legacy live tests. They cover direct and
array-selected names, dollar-named parameters, early-return removal, the regex
fallback, selected-function-only evaluation, URL query boundaries and encoding,
and cold/warm/cache-clear concurrency. Only the first nonempty `n` query value
is transformed; unrelated query bytes, path and fragment are preserved. Malformed
percent encoding fails with a parsing error before downloading player code.

Player cache generations isolate in-flight work from explicit clears. Cold
script/function extraction is synchronized per generation; independent JavaScript
evaluations remain outside that lock. Parameter-only clears retain the selected
player but retire the old result map. Controlled concurrent tests assert one
cold fetch and separate correct results, not physical-device throughput.

The stored January 2026 `c9168c90` player verifies the exact signature output and
timestamp. The signature golden was independently checked with Node 22/V8 and
Rhino using the extracted function, not the full player. Diagnostic cold/warm
timings are informational, not a timing-sensitive test or phone benchmark.

**Known unresolved compatibility gap:** investigation of that same historical
player found the broad throttling-name pattern selects an unrelated URL helper
(`FI`), which leaves the tested `n` unchanged. This is not a valid deobfuscation
result or an intentional compatibility contract. The synthetic shape tests do
not establish modern/live throttling support. A modern algorithm still needs a
verified input/output oracle before replacing the working signature path or
introducing full-player evaluation. No such modernization or live-playback
claim is included in this change.

## SoundCloud

SoundCloud is audio only. Client-ID discovery tries the mobile runtime config
then desktop scripts; a mobile socket timeout permits fallback, other interrupted
I/O stops it. The result is cached in memory. Account import reads public follows.
API-ID extraction accepts track prefixes; non-track API resources and lookalike
hosts are rejected before downloader access. Web/short links can require widget
resolution. Read [the soundcloud implementation](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/soundcloud/linkHandler/) before changing URL acceptance.

## BitChute

- Links: video, embed, torrent, channel and public playlist addresses on
  `bitchute.com`, `www.bitchute.com` and `old.bitchute.com`. Public playlists
  use `/playlist/<id>/` or `/video/playlist?playlistId=<id>`; the extractor
  accepts 12-character ids made of letters, digits, `_` and `-`. Android
  link handling includes `/playlist/` and `/video/`.
- A video comes as one stream, progressive or HLS, whichever the site gives.
  Livestreams use the HLS source from the embed page; the media API can return
  a placeholder MP4 for those. There are no separate audio tracks or subtitles.
- The Live kiosk lists the site's current livestream snapshot. Live cards have
  no fixed duration and may have no publication date.
- Public playlist links open with their description, owner name and ordered
  videos. Pagination advances by the number of returned rows, including
  deleted entries, because the server may adjust the requested page size.
  Empty pages or a reached total end pagination. Private or restricted
  playlists are unavailable. Playlist owners are profiles, so their names
  are not linked to unrelated channel subscriptions.
- A channel has one tab, Videos.
- The player and the downloader send a Firefox user agent to BitChute's
  `seed` media hosts, which close connections from the default one
  (`DownloaderImpl.getMediaUserAgent`).
- Nothing in the app answers a challenge page from BitChute.

## Rumble

- Links: watch, embed and Shorts addresses, and channel addresses under `/c/`
  and `/user/`.
- App HTTP requests use the official Android TV identity
  `Rumble-AndroidTV/2.11.0.10198 (<model>; Android <release> <SDK>) okhttp/5.1.0`
  for HTTPS `rumble.com`, its dot-delimited subdomains, and exactly
  `hugh.cdn.rumble.cloud`. Other hosts and HTTP retain their existing policy.
  The version identifies the emulated client, not PVCPipe. Each constructed
  request selects its identity; automatic redirects can retain the initial UA,
  so this is not per-hop identity confinement or a general Cloudflare solution.
  It adds no credentials, cookies or tracking parameters and does not establish
  which identity the optional challenge WebView uses.
- API responses distinguish explicit errors and malformed envelopes from valid
  empty lists. Explicit challenge headers or paired challenge markers in the
  first 8 KiB also fail on HTTP 200; ordinary refusals remain ordinary failures.
- Full slugged watch links and canonical Shorts links resolve through public
  `media.details`; numeric IDs are converted to internal embed IDs, never
  inferred from public permalink IDs. Normal playback retains `embedJS/u3`
  and response-provided media URLs. Direct embeds do not require details.
  Successful resolution exposes a validated full public URL for new saved
  entries. Shorts use details-backed MP4 sources and a Shorts-feed related list,
  without requiring watch HTML; recommendation ranking equivalence is unproved.
- Cold legacy bare watch links still need the existing HTML embed lookup and
  can fail on a refusal or challenge. Existing saved rows are not rewritten:
  full-URL and legacy-bare rows can coexist because storage identity is
  `(service_id, url)`. This does not universally repair old bookmarks.
- Video searches with unrestricted upload dates, ordinary creator searches and
  Live use public API 8 listings. Shorts keeps API 7. Continuations advance by
  raw returned entries, including filtered/unreadable rows, not the requested
  limit or visible count. Video search and Shorts honor required `has_more`;
  creator search and Live stop on an empty raw page. Creator search never emits
  an offset above its observed 10000 search-depth bound; this is not a claim of
  exhaustive creator enumeration.
- Date-restricted searches and stale creator searches with non-default filters
  still use their existing HTML requests. The leaderboard, Editor Picks,
  Latest and Trending Today kiosks also retain HTML and can fail on challenges.
  Their ranking, time-window and completeness equivalence to other API feeds
  is unproved; a refusal is not an empty list.
- Videos retain progressive files, HLS renditions and available audio. Explicit
  `availability=premium` without `has_access_to_premium=true` is denied; an
  access flag of false alone is not a restriction on ordinary public content.
  Listings mark/filter explicit premium availability, not private/unlisted
  visibility. Unknown restrictions remain unknown. No paid/account gate is bypassed.
- Comments share the media resolver for watch, embed and Shorts inputs, but keep
  the legacy `comment.list` HTML/CSS response and DOM mapper. Malformed/API-error
  pages fail and clear prior comment/image state; only a structurally valid
  no-list envelope indicates disabled comments. An empty list stays enabled.
  Fifteen-item pages and nested replies retain their response bodies. Reply
  counts describe the available nested records, not guaranteed complete server
  threads; complete pinned/owner/deleted/reply parity for the candidate JSON
  comments API remains unproved, so no partial JSON fallback is used.
- Creator metadata uses validated typed IDs from listings or one exact-type,
  exact-slug search page before `video_collection.meta`. An exact-search miss
  retains the HTML/About path; it does not prove the creator is absent. API
  errors remain failures. Public IDs stay `c/<slug>` and `user/<slug>`.
- A channel has Videos and Livestreams tabs, still fetched through their existing
  HTML paths. Their API filtering equivalence is unproved; challenged tabs
  remain an explicit reliability limit. There is no Shorts tab.
- "Handle Rumble Cloudflare challenge" in the PVCPipe settings adds an
  interceptor for 403 answers from Rumble hosts
  (`PvcCfChallenge403Interceptor`). Only GET responses identified as Cloudflare
  challenges open the WebView. Both the original request and the response
  after redirects must be GETs on Rumble hosts. Detection uses the
  `cf-mitigated: challenge` header or the first 8 KiB of the response body
  containing both `challenge-platform` and `Just a moment`. Ordinary refusals
  and POST requests keep their original response. A WebView result replaces
  it only when marked successful, nonblank and free of those challenge
  markers. This needs a working WebView on the device.

### 2026-11-03 rebuilt-extractor follow-up

At 00:27–00:32 UTC, a JDK 21 harness using the rebuilt 0.2.4 extractor exercised
fresh public results with the TV research identity (`ResearchModel`, Android 13,
SDK 33). Requests were GET-only, cookie-free, capped at 1 MiB / 20 seconds,
at most one request start per second; media probes read only 4 KiB ranges.
This was a desktop HTTP client, not Android OkHttp, a decoder or a receiver.

Two VODs, two Shorts and one active live item resolved and replayed in separate
fresh JVMs from their returned public URLs:
[VOD 1](https://rumble.com/v7hnjfy-sci-fi-movie-or-space-history.html),
[VOD 2](https://rumble.com/v7gtth4-beyond-space-defense-war-sabotage-and-survival-beyond-earth.html),
[Short 1](https://rumble.com/shorts/v7hh3vw),
[Short 2](https://rumble.com/shorts/v7hnt9y), and
[live](https://rumble.com/v7hrvsa-ep-4026b-ds-election-panictheir-own-are-turning-on-thempropaganda-failedtim.html).
VOD audio was labeled AAC; automatic English captions were marked generated.
MP4 and HLS probes returned HTTP 206 with matching file/playlist signatures.
Normal autoplay and Shorts-feed related lists had no collector errors.
Cold FYMVideos metadata resolved through `_c1330192`; legacy watch/embed comments
returned two top-level records, and the sampled Short returned eight, without
claiming complete threads.

| Fresh listing | First-page visible rows | Second-page visible rows | Overlapping public URLs |
| --- | ---: | ---: | ---: |
| Video search, `space`, offsets 0/20 | 20 | 20 | 0 |
| Creator search, `space`, API 8, offsets 0/19 | 19 | 19 | 19 |
| Live, offsets 0/20 | 20 | 20 | 0 |
| Shorts, API 7, offsets 0/10 | 10 | 10 | 0 |

**Creator pagination remains a demonstrated server-contract limit:** this query
repeated the entire page despite API 8 and the advanced raw offset. The extractor
does not claim reliable distinct-page enumeration or silently switch feeds.
Earlier NASA offset observations remain historical, not proof for this query.

Follow-up (2026-11-11): creator continuations now carry the preceding page's
visible creator identities. If a fully readable response repeats that identity
set, the extractor stops without appending duplicates, even if metadata or ordering
changes. Partial overlap still continues using raw-row offsets. This is covered
by offline fixtures, not a new live-network validation.

The retained HTML paths also returned HTTP 200 and parsed without collector
errors on this host in the same run:

| Retained path | Visible rows |
| --- | ---: |
| Video search: today | 17 |
| Video search: this week | 25 |
| Video search: this month | 25 |
| Video search: this year | 25 |
| Creator search: most recent | 39 |
| Battle leaderboard | 50 |
| Editor Picks | 25 |
| Latest | 25 |
| Trending Today | 25 |
| FYMVideos Videos tab | 25 |
| FYMVideos Livestreams tab | 0 |

The empty Livestreams result came from valid HTML, not a suppressed challenge.
These dated successes do not establish Android challenge behavior, future
availability, API/HTML ranking equivalence or real playback/download/cast success.

## Odysee

`OdyseeApi` and the service's extractors use LBRY claims and Odysee endpoints;
channel and stream URLs, listing results and playable media are distinct data.
The implementation handles canonical/share addresses and resolves a claim before
playback. A successful listing or resolve does not guarantee playable content.

Live media and ordinary file media use different host paths. Stream selection,
redirects, range handling and host-specific headers remain defined by the
extractor/downloader rather than inferred from a web page. Paid/member content
can be marked or filtered; metadata does not grant access. Optional viewer/follower
count lookups register an anonymous visitor token, so local subscriptions should
not be described as having no service-side requests. Counts can be unavailable.

`DownloaderImpl.getMediaReferer` supplies Odysee's website referrer for
`odysee.live` and `odycdn.com`, including their subdomains, not arbitrary hosts.

Read [the odysee implementation](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/odysee/) for URL forms, claim/channel identity, pagination,
visibility/count handling and media-host selection. The [baseline service guide](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/services.md#odysee)
and [dated host observations](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/odysee-observations.md) preserve the detailed
protocol findings; they do not establish current live availability.

## Kick

Kick supports account-free public live broadcasts, retained VODs and clips,
All/Live/Channels search, channel suggestions, localized Featured discovery,
related items and local subscriptions. Channels have Videos, Livestreams and
Clips tabs. Videos/Live and search do not invent pagination. Clips use the
server's opaque `cursor`; Featured uses `pagination.next_cursor` as **`after`**,
not `page` or `cursor`. Continuation pages are scoped to the exact channel/filter
or localized discovery URL and rebuilt internally before requests. Consumed cursor
history travels with each page so cycles also stop when the app or queue creates
a fresh extractor for the next request.

Plain channel links open the current broadcast. Channel `/videos`, `/about` and
`/clips` collections open the channel; subscriptions always retain `/videos`.
Clips accept both `/<slug>/clips/<clip_id>` and `/<slug>?clip=<clip_id>`, preserving
clip-ID case in the canonical path URL. Ambiguous/duplicate/conflicting selectors
and clip selectors on recording/item paths are rejected, not treated as live.
Channel-scoped VOD links and older `/video/{uuid}` links remain supported, with
legacy fallback only for 404. Nonnegative VOD `t` offsets are read before
canonicalization; invalid offsets become zero and live playback ignores them.

Clips have Views/Upload date and day/week/month/all-time controls above the list,
including empty/error results. Choices belong to the tab handler's explicit
`?sort=...&range=...` URL, survive handler/queue reconstruction, and are not global
defaults. An initial external Clips collection selects its actual visible tab
with those filters; explicitly hidden tabs remain hidden. Feeds use date/all,
independent of transient browsing choices. Explicit saved tab/feed sets remain
authoritative; absent sets enable recognized tabs.

Broadcast-specific `?live=<epoch-seconds>` identities prevent an old feed/history
entry from becoming a different broadcast. An ongoing VOD can use fresh live
playback only when its normalized start identity matches the current broadcast;
missing identity is a parsing error and ended/mismatched broadcasts are unavailable.
UUIDs and legacy numeric livestream IDs are not compared for ownership.

Native Kick HLS video loads the original master while selecting the requested
video rendition and compatible audio/subtitle groups. High-frame-rate qualities
remain distinct, such as `720p` and `720p60`. Native HLS captions are not progressive
VTT sidecars. Audio mode uses a genuine audio rendition when provided; a muxed
fallback does not imply audio-only transfer savings. Missing selected video/audio
renditions remain playback errors. A selected caption's HTTP failure can report a
Media3 load-error event while video and audio continue; this does not promise
caption error feedback in the app UI. External players/downloads retain actual media
playlist URLs; direct MP4 clips never fetch their media as playlist text.
Video-only HLS and live/ongoing streams are rejected before download destination
effects. M4A-labeled AAC HLS audio uses the download remux path; other HLS audio codecs
and HLS subtitles remain excluded. Receiver caption selection
is not newly promised; the main player remains the only cast playback-control panel.

Thumbnails retain actual deduplicated candidates and filename-derived dimensions,
not inaccurate srcset descriptors. Channel biography/ownership remains authoritative,
including clips created by another user. Category, tags, language, counts and
membership availability are exposed when supplied. Subscriber-only clips,
recordings and broadcasts raise a paid-content error before media access.
Explicit restrictions in either status or privacy metadata take precedence over
public aliases and retained media URLs. Related failures are optional `StreamInfo`
errors rather than media-fetch failures or silently empty successes. Source caches remain limited
to one minute; hidden or unknown live viewer counts are not invented.

Playlists, comments, live chat, account management and account-follows import are
not implemented. Playback has no Pusher/Centrifugo dependency. See
[the Kick implementation](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/kick/)
and the [dated protocol record](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/service-protocol-observations.md#kick-follow-up--2026-10-18).
Earlier transport observations do not verify newer code or real receivers.

### Public extraction follow-up — 2026-10-30

A temporary `KickPublicSmokeTest` used `DownloaderTestImpl` directly with
`-Ddownloader=REAL`, not recorded/mock responses. Search `xqc`, channel metadata,
Videos/Clips, a distinct second Clips cursor page and a distinct second
Featured `after` page succeeded. Selected public content:

- VOD: `https://kick.com/xqc/videos/01a18946-1d20-7dc3-ba54-8b71a4c0a844`.
  Extraction exposed five video qualities and five subtitle playlists; master,
  a video media playlist and a subtitle playlist were fetched successfully.
- Clip: `https://kick.com/xqc/clips/clip_01M66HHJD5N91Z25HG2V10XM6Y`.
  Extraction and its HLS media playlist succeeded, with one combined video stream.

Signed media URLs/tokens are intentionally not recorded. The temporary harness
was removed after execution. This is public HTTP/extractor evidence, not Android
decoded playback, in-app UI, completed device download or real FCast receiver proof.
The native caption/download instrumentation classes compile but were not run:
no Android device was attached, and this host had no emulator binary or KVM.
Host/controller checks covered filter transitions, external collection visible-tab
selection and hidden-tab preference precedence; they do not substitute for device UI.

The integrated `make ci` gate passed: fixture sanitation, build-source tests,
style/dependency-order checks, complete app JVM/extractor offline suites,
Android lint, and debug/release assembly. `assembleDebugAndroidTest` also passed.
The host's composite-build lock script raised `OverlappingFileLockException`;
verification used a temporary Gradle home reusing the existing caches, with the
same global file lock held once around each invocation. Host configuration was
not changed. No APK was installed and no application data was cleared.

### Integration review follow-up — 2026-10-30

Reviewing the integration on current `master` reproduced six failing regression
cases: Clips and Featured could follow multi-page cursor cycles when consumers
created fresh extractors; clip extraction ignored deleted/pruned/banned statuses
and could ignore subscriber-only privacy when a public status was also present.
Continuation pages now retain cursor history. Listings and direct playback use
the same restriction predicates, including legacy recording metadata.
All 202 Kick offline regression cases passed after those corrections.

Two further controller regressions reproduced Clips choices reverting to date/all
after parent refresh or saved-state reconstruction. The parent now captures a
lightweight handler before clearing metadata and saves it independently of child
fragments. Both tests exercise the real refresh/selection methods with controlled
metadata and child destruction; they are not device UI or process-death evidence.

The integration-review environment had a disposable API 26 x86_64 emulator.
It exposed two assumptions missed by host-only conversion and compilation:
the original bundled FFmpeg lacked MOV/MP4 input, and concat additionally needed
the H.264 MP4-to-Annex-B filter. The converter now includes both for all four
ABIs, with its wrapper/metadata unchanged and 16 KiB alignment retained for the
64-bit binaries. See [the pinned build recipe](../app/libs/README.md).
The rebuilt Android executable produced an 8.000313s MP4 with audio and video
from the mapped fixture in a controlled binary-level check.

The selected-caption failure test had incorrectly required `Player.onPlayerError`.
The native engine instead reported the actual caption HTTP 404 through its load
listener while audio/video decoding continued. That case now checks both the load
failure and continued decoding; master/video failure assertions remain fatal.

After the native corrections, the packaged app passed all **21 tests** in
`Media3SourceEngineIntegrationTest` and `HlsDownloadIntegrationTest` on that
disposable emulator. This includes TS/fMP4 mission completion, restored plans,
decoded reopening, native captions, quality changes and genuine error paths.
The JVM suites passed **1,250 app cases** and **935 extractor cases** with three
pre-existing extractor skips and no failures. The phone installation was not
used. These controlled results do not establish live-site or receiver behavior.

## Removed services

media.ccc.de, PeerTube and Bandcamp were removed from the extractor and app.
Their service IDs, 2, 3 and 4, remain retired: database rows, backups and saved
main-page tabs use these persistent identifiers. Assign new services the next
unused ID. IDs are not contiguous; use `NewPipe.getService(id)` or
`Menu.findItem(id)`, never a position in `ServiceList.all()`.

Settings migration 8 to 9 (`SettingMigrations`) drops main-page tabs that
show a kiosk, channel or playlist of a removed service, deletes the PeerTube
instance preferences and the saved search presets of the three services, and
clears the selected service if it was a removed one. It tells the user once
if a tab went or the selected service changed.

Database rows that carry a retired id are not deleted. This is what the app
does with them:

- Subscriptions, history entries, playlist entries and bookmarked playlists
  stay listed. Subscription headings identify the unavailable ID. Opening one shows
  "PVCPipe does not support this content yet", because `NewPipe.getService`
  throws `ContentNotSupportedException` for an id without a service.
- A feed refresh leaves such subscriptions out (`FeedLoadManager`), so they
  are not reported as errors on every refresh.
- The channel and playlist pickers for main-page tabs do not offer them.
- A subscription import leaves out channels of services the app does not have
  and says how many it skipped. Without that, one such channel would fail the
  whole import.

Use `ServiceHelper.hasService` when checking whether a stored service ID is
currently supported.

## Adding or changing a service

- Give a new service the next unused ID in `ServiceList` and append it to
  the registration list.
- The app side of a service is small: a theme in `styles_services.xml` and
  `colors_services.xml` named after the service, an icon in
  `PvcServiceHelper`, a link filter in `AndroidManifest.xml`, and names for
  any new kiosk in `KioskTranslator`.
- Themes resolved by `ThemeHelper` through `getIdentifier` need an explicit
  [resource keep file](https://developer.android.com/topic/performance/app-optimization/customize-which-resources-to-keep)
  under `res/raw/`, with `tools:keep` on its root `<resources>` element.
  Add the service's three themes to `pvcpipe_theme_keep.xml` with normalized R
  names such as `@style/LightTheme_Kick`, not the dotted XML style names.
  `tools:keep` on an individual style has no effect. `ServiceThemeKeepTest`
  fails if a service's theme is missing from either file. Inspect the shrunk
  APK's resource table: a working debug theme does not prove release retention.
- Tests are in
  `pvcpipe-extractor/extractor/src/test/java/org/schabi/newpipe/extractor/services/`,
  one directory per service. Tests tagged `offline` run in `make ci`. The
  others may contact the website. For Odysee the one class that does is
  `OdyseeLiveTest`.

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.rumble*'
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.bitchute*'
./gradlew :pvcpipe-extractor:extractor:test --tests '*OdyseeLiveTest*'
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.kick*'
```

An offline test shows that the code handles a recorded or made-up answer. It
cannot show that a website still gives that answer.
