# Services

A service is a website PVCPipe can browse and play from. This guide describes
implemented extraction capabilities and their limits. Dated responses and
live checks are recorded separately in [odysee-observations.md](odysee-observations.md)
and [service-protocol-observations.md](service-protocol-observations.md).

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
| YouTube | 0 | audio, video, live | `live`, `trending_gaming`, `trending_music`, `trending_movies_and_shows`, `trending_podcasts_episodes` | yes | yes |
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
so a refresh loads the channel and its tab.

PVCPipe has no service sign-in and cannot follow accounts, vote or post on a
service. Local subscriptions do not change a service account. Subscriptions,
saved playlists, history and queues stay on the device
(see [library-data.md](library-data.md)). Optional Odysee count lookups register
an anonymous visitor token; see [Odysee](#odysee).

`DownloaderImpl` reports every HTTP 429 as a `ReCaptchaException`. The app
can only do something about a captcha from YouTube: `ReCaptchaActivity` keeps
YouTube's cookies and `DownloaderImpl` sends them back to YouTube only. So
`ErrorInfo.solvableCaptchaUrl` offers the captcha screen for YouTube and
Google hosts, and for any other host the app says that the site is refusing
requests from this network for now.

"Hide paid content videos" in the PVCPipe settings sets a switch in the
extractor for YouTube, Rumble and Odysee (`PvcExtractorSettings`). When enabled,
their lists filter out content identified as requiring membership or payment.

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
| Kick | channels |

Rumble, SoundCloud and Odysee build the sort part of a search address with
`QuerySortFilterItem`. When changing a filter class, check the displayed order
and the exact address or request the search produces. The filter tests, such
as `RumbleFiltersTest` and `OdyseeFiltersTest`, pin both.

## YouTube

YouTube's general `Trending` page is no longer offered in the kiosk selector.
YouTube [announced its retirement in July 2025](https://support.google.com/youtube/thread/356702168/changes-to-discovering-trending-content-on-youtube?hl=en).
Live and the category feeds remain available as main-page tabs; the English
movies-and-shows label is shortened to "Trending movies/shows".
Settings migration 9 → 10 removes any dedicated YouTube `Trending` tabs that
were added after the earlier retirement migration. Other tabs keep their
order, including Odysee's `Trending`; if no custom tabs remain, the default
main-page tabs are restored.

`YoutubeStreamExtractor.onFetchPage` asks YouTube's Android client for a
video first. If that client says the video is age-restricted, it asks the
embedded web client. It then asks the visionOS client, and the iOS client if
that is switched on. The app supplies proof tokens only through the
web-client provider, not the embedded-web, Android or iOS providers
(see [playback.md](playback.md)). If no client returns a player response,
a restriction that one client reported is shown in preference to another
client's network error.

The app shows "YouTube refused to provide data and requires you to sign in"
when the extractor throws `SignInConfirmNotBotException`. That happens when a
player response says `login_required` with a reason that mentions "a bot"
(`YoutubeStreamExtractor.checkPlayabilityStatus`).

`YoutubeStreamInfoItemLockupExtractor` reads channel-card view counts from
the metadata part marked `PLAY_ARROW_OUTLINED`, so creator credits before
that part do not become the count. Cards without the marker use the older
positional layout.

## SoundCloud

SoundCloud is audio only. Client-id discovery first reads the mobile discovery
page's `__NEXT_DATA__.runtimeConfig.clientId`; if that is absent or unavailable,
it searches the desktop site's JavaScript assets. The discovered id is cached
in memory. A mobile-page socket timeout allows the desktop fallback; other
interrupted I/O stops discovery. A subscription import takes a SoundCloud
profile address and imports the channels that profile follows.

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
- A video comes as progressive files, HLS renditions and, where Rumble gives
  one, an audio file. Livestreams play.
- A channel has Videos and Livestreams tabs. There is no Shorts tab.
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

## Odysee

Odysee is a website on top of the LBRY network. Everything on it is a claim: a
video, an audio file, a livestream page, a channel, a playlist or a repost.
The extractor makes the requests the website makes for a visitor who is not
signed in. They are all in
[`OdyseeApi`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/odysee/OdyseeApi.java).

| Request | Host | Used for |
| --- | --- | --- |
| `resolve`, `claim_search`, `get` | `api.na-backend.odysee.com` | claims by address or id, lists, the media address |
| `search` | `lighthouse.odysee.tv` | search, suggestions, fallback related videos |
| `search` | `recsys.odysee.tv` | anonymous related videos |
| `comment.List`, `reaction.List` | `comments.odysee.tv` | comments and their likes |
| `reaction/list` | `api.odysee.com` | likes and dislikes of a video |
| `user/new`, `file/view_count`, `subscription/sub_count` | `api.odysee.com` | the visitor token, view counts, follower counts |
| `livestream/is_live`, `livestream/all` | `api.odysee.live` | who is on air |
| `$/api/content/v2/get` | `odysee.com` | the channels behind the Featured kiosk |

The implementation uses the request shapes in `OdyseeApi`. The development
records describe the external references and live checks used to establish
those shapes; they are evidence for the implementation, not an API contract.

### Addresses

[`OdyseeUrl`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/odysee/OdyseeUrl.java)
reads addresses on `odysee.com`, `www.odysee.com`, `lbry.tv` and
`open.lbry.com`: videos, channels, embed and download links, and playlists.

A video's id is its website path, such as `@channel:c/video:a`. The API wants
the same thing as `lbry://@channel#c/video#a`. The part after the colon is
one to forty hexadecimal characters from the start of a claim id, or it may
be missing, so one claim has several addresses. Lists use the address Odysee
calls canonical. A channel that was opened by another address takes its
canonical one once it is loaded, so that a subscription is the same whichever
link it was made from. A video keeps the address it was opened with.

### What is listed and played

- A video offers the uploaded file and, where Odysee has transcoded it, its
  HLS renditions. An audio claim offers its file as an audio stream.
  Documents and images are not listed and do not open. There are no
  subtitles.
- An HLS master playlist states the peak bitrate of each rendition. The app
  estimates the size of an HLS download from the bitrate, so a rendition is
  given the average if the playlist states one and 85% of the peak otherwise,
  the factor the Rumble extractor uses.
- A stream claim without a file is a livestream page. It plays only while its
  channel is on air with that claim. Otherwise the app says it is not
  available. Once the publisher attaches a recording, the claim has a file
  and is an ordinary video. A channel keeps the page of every livestream it ever announced,
  so lists leave livestream pages out unless they are known to be on air. The
  Live kiosk and a channel's Livestreams tab list them. Search and related
  videos do not. A livestream shows how many are watching it.
- A channel has Videos, Livestreams and Playlists tabs.
- The Featured kiosk is the website's own featured row: recent uploads of a
  list of channels that the website's homepage names. Trending is the
  site-wide trending order. Both leave out mature and paid content.
- Suggestions are titles from the first search results.
- Related videos first use `recsys.odysee.tv` with the current claim id, title,
  `free_only=true` and `nsfw=false`. A network, extraction or JSON-parsing
  failure falls back to Lighthouse search. Cancellation stops the request.
  Returned claim ids are resolved before the usual content filtering.
- A repost counts as the claim it reposts.

### Who published a claim

A claim names its channel and carries a signature. The API marks a claim
whose signature does not hold with `is_channel_signature_valid: false`.
`OdyseeClaim.channel` treats such a claim as published by "Anonymous" and
does not show it under the channel it names.

### Paid, members-only and unlisted content

Content requiring purchase, rental or membership is identified by tags
(`c:purchase`, `c:rental`, `c:members-only`), an LBC fee or an authentication
error from `get`. A livestream can also be marked protected in its live
state. PVCPipe reports these as paid content. Kiosks omit them; search and
channel lists show them
unless "Hide paid content videos" is on.

The publisher of an unlisted video shares it as a link that carries
`signature` and `signature_ts`. The extractor passes both to `get`. The app
stores a video's address without its query, so a video reopened from history
has no signature, and the app then says the video opens only from its signed
link.

### View counts and follower counts

`api.odysee.com` answers `file/view_count` and `subscription/sub_count` only
for a request that carries an `auth_token`. The website gets one by
registering an anonymous visitor for everyone who is not signed in, and
[`OdyseeAuth`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/odysee/OdyseeAuth.java)
does the same when `OdyseeSettings.SHOW_COUNTS` is on. Registering takes no
name, address or password.

- "Show Odysee view and follower counts" in the PVCPipe settings is on by
  default. Switching it off deletes the token and stops the requests. A token
  that comes back from a registration under way at that moment is discarded.
- One token is registered when a count is first asked for. The app keeps it
  in its preferences under `pvc_odysee_visitor_token_key`, so it does not
  register a new visitor at every start. Like the rest of the preferences it
  is part of a settings export.
- The token is sent in the body of a `POST`, never in an address.
- A token that Odysee refuses is replaced once. `OdyseeApi.callAccountApi`
  identifies an authentication-error envelope, including in a 401 or 403
  response. A 403 from something in front of Odysee that does not identify an
  authentication error leaves the token alone.
- If the new token is refused too, or registering fails, nothing more is
  registered for ten minutes, and for twice as long after each further
  failure, up to about ten hours. A registration that was abandoned because
  the user left the page does not count as a failure.
- Successful view and follower counts share a memory-only cache of at most
  512 entries, keyed by endpoint and claim id. Entries expire five minutes
  after insertion using a monotonic clock; reads do not extend their life.
  Requests contain only cache misses, up to 100 distinct valid claim ids.
  Clearing caches, forgetting the visitor token or replacing its store
  invalidates entries and prevents earlier requests from repopulating them.
- Each uncached list page requests view counts for its videos and follower
  counts for its channels in separate batches. A video page asks for its own
  views and its channel's followers. Uncancelled I/O, extraction or parsing
  failures suppress count lookups for a minute, including cache reads. A
  failed request for missing counts returns no counts for that call, even
  if some were cached.
- With the token Odysee can tell that the requests of one installation belong
  together. Counts are an extra. A page opens the same without them, with the
  numbers missing.

### A video page

`OdyseeStreamExtractor.onFetchPage` resolves the claim and checks that it is
playable and free. A file claim then needs the media address from `get`.
For video files, `fetchTranscodedStreams` follows a `HEAD` request and reads
an HLS master if one is found; failure of that optional lookup leaves the
uploaded file available. Audio files do not need that rendition lookup.
A livestream instead needs its current live state and media address.

Likes, the view count and the follower count are requested concurrently
after the claim (and, for a livestream, its live state) is available. The
page waits for those tasks as well as media preparation, so a slow optional
request can delay it. A failure in an optional task leaves its numbers
unknown rather than failing the page.

### Media hosts

`get` returns one address on `player.odycdn.com`. Livestreams play from
`cloud.odysee.live`. Both hosts refuse requests that do not name the website
as their referrer, so the app sends `Referer: https://odysee.com/` on every
media request to them (`DownloaderImpl.getMediaReferer`): from the player,
the downloader and the extractor downloader.

The extractor uses `HEAD` on `player.odycdn.com` to discover HLS renditions:
with the referrer, an uploaded file may redirect to a transcoded HLS master
instead of describing the original file. The app therefore does not use
`HEAD` to determine the uploaded file's size (`DownloaderImpl.isHeadUnusable`):

- Where the downloader would use `HEAD`, it asks with `GET`. Where that
  request would have asked for everything from a position on, it asks for one
  byte, whose `Content-Range` gives the total
  (`DownloadMission.openConnection`).
- The size shown in the download dialog comes from the same kind of request
  (`PvcDownloaderImplUtils.getContentLengthViaGet`).
- The playlists and segments of the HLS renditions answer `HEAD` like any
  file and are asked as before.

An external player receives the media URL without PVCPipe's referrer header.
[odysee-observations.md](odysee-observations.md) records what the hosts
answered with and without it, and the HTTP 429 answers that kept uncached
files from being checked.

## Kick

Kick supports public live broadcasts and retained VODs without a service
account. Channel search requests up to 50 results; suggestions request up to
ten names. Channels expose Videos and Livestreams tabs and support local
subscriptions. Featured is a single live-discovery list. These extractors
do not create continuation pages when the endpoint supplies none.

[`KickApi`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/kick/KickApi.java)
uses the following public resources:

| Host and path | Purpose |
| --- | --- |
| `kick.com/api/v2/channels/{slug}` | channel metadata and current broadcast state |
| `kick.com/api/v2/channels/{slug}/playback-url` | fresh signed live HLS master |
| `web.kick.com/api/v1/channels/{id}/videos` | retained VOD list |
| `web.kick.com/api/v1/channels/{id}/videos/{uuid}` | current VOD metadata and recording URL |
| `kick.com/api/v1/video/{uuid}` | older shared VOD UUIDs |
| `search.kick.com/api/v1/channels/search` | channel search and suggestions |
| `web.kick.com/api/v1/livestreams/featured?language=en` | Featured |

Plain `kick.com/{slug}` links open the current broadcast; `/{slug}/videos`
and `/{slug}/about` open the channel. VOD links use
`/{slug}/videos/{uuid}`; older `/video/{uuid}` links are also understood.
Some real legacy channel slugs begin with `@`; that character is preserved.
Current VOD lookup falls back to the older UUID API only after HTTP 404,
not after a private, expired, blocked or malformed response.
VOD links can include a nonnegative `?t=<seconds>` start offset. Extraction
reads it from the original URL before canonicalization. Invalid or oversized
values and encoded parameter-boundary lookalikes default to zero. Live playback
ignores recording offsets.

Live feed items and extracted live streams use
`/{slug}?live={start-time-in-UTC-seconds}` as a broadcast-specific identity.
The extractor preserves this marker and rejects a marked link when the
current broadcast has a different start time. This keeps successive
broadcasts distinct in feed/history storage. Subscription URLs remain
`/{slug}/videos`. Reloading a channel's Livestreams tab fetches its current
broadcast even when the tab handler is reused; the tab does not retain an old
broadcast snapshot. Live start dates are retained for feed notification eligibility.

Both live and VOD extraction expose combined audio/video HLS renditions,
including URLs usable by the external-player chooser. The internal live
player uses the master playlist. VOD download selections use rendition
playlists, not the master. Ongoing broadcasts are omitted from the VOD tab;
opening an ongoing recording link switches to live playback instead of
offering an incomplete recording as a finished download.

Kick information and loaded-source caching is limited to one minute because
live playback tokens expire quickly. A playback URL alone does not prove
that a channel is live. Viewer counts are omitted when visibility is false
or unknown; legacy channel responses do not carry that visibility flag.
Ordinary HTTP 403 errors are not treated as challenges without a challenge
header or HTML markers.

Artwork metadata follows known CDN URL conventions:

- Live thumbnail filenames provide height, not width.
- Converted uploader avatars provide width: 150 pixels for `medium`, 350 for
  `fullsize`. Height stays unknown because avatars need not be square.
- Current VOD previews receive an estimated **High** quality label without
  inventing exact dimensions.
- Original and unrecognized image URLs keep unknown dimensions and quality.

This metadata requires no extra image requests. In the Description tab, `?`
means an unknown image dimension, not a failed image load.

Channel banners use the shared channel header: they fill its width without
stretching and are center-cropped to fit the height cap. This also applies to
other services, not just Kick.

Clips, playlists, comments, live chat, subtitles and importing a Kick account's
followed channels are not implemented. PVCPipe has no live-chat surface.
Playback does not depend on a Pusher or Centrifugo connection. See the
[dated protocol checks](service-protocol-observations.md#kick-follow-up--2026-10-18)
for the observed transport negotiation and verification limits.

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
  stay listed, with `<unknown>` where the service name goes. Opening one shows
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
  Kick uses normalized R names such as `@style/LightTheme_Kick` in
  `pvcpipe_kick_keep.xml`, not the dotted XML style names. Inspect the shrunk
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
