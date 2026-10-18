# Services

A service is a website the app can browse and play from. This page says what
each one supports and where its code is. Dated service responses are recorded
in [odysee-observations.md](odysee-observations.md) and
[service-protocol-observations.md](service-protocol-observations.md).

## How a service is put together

The extractor library in `pvcpipe-extractor/` is part of the Gradle build
(see [building.md](building.md)).
[`ServiceList`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/ServiceList.java)
registers the services. Each one is a
[`StreamingService`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/StreamingService.java)
with two kinds of parts:

- Link handler factories recognise an address, say whether it is a video, a
  channel or a playlist, and reduce it to an id.
- Extractors fetch a page and turn it into the models the app shows: stream,
  channel, channel tab, playlist, search, suggestions, comments and kiosk. A
  kiosk is a browse page such as Trending.

A service leaves out the parts its website has no use for. The extractors
make their requests through the downloader the app hands the library
(`DownloaderImpl`). The player (`PlayerDataSource`) and the file downloader
(`DownloadMission`) make their own requests for the media addresses the
extractors return.

## The services

| Service | Id | Media | Kiosks, default first | Playlists | Subscription import |
| --- | --- | --- | --- | --- | --- |
| YouTube | 0 | audio, video, live | `live`, `trending_gaming`, `trending_music`, `trending_movies_and_shows`, `trending_podcasts_episodes` | yes | yes |
| SoundCloud | 1 | audio | `New & hot` | yes | yes |
| BitChute | 5 | video, live | `Trending Today`, `Trending This Week`, `Trending This Month`, `Popular`, `Suggested`, `live` | yes | no |
| Rumble | 6 | audio, video, live | `Today's Battle Leaderboard Top 50`, `Editor Picks`, `Live`, `Trending Today`, `Latest`, `Shorts` | no | no |
| Odysee | 7 | audio, video, live | `Featured`, `Trending`, `live` | yes | no |

All five have comments, search and search suggestions. The kiosk names in the
table are the ids the code uses. `KioskTranslator` turns the ones it knows
into display names.

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

The app has no sign-in for any service. It does not follow, vote or post.
Subscriptions, saved playlists, history and queues are kept on the device
(see [library-data.md](library-data.md)). The one identifier the app registers
with a service is Odysee's anonymous visitor token, described under
[Odysee](#odysee).

`DownloaderImpl` reports every HTTP 429 as a `ReCaptchaException`. The app
can only do something about a captcha from YouTube: `ReCaptchaActivity` keeps
YouTube's cookies and `DownloaderImpl` sends them back to YouTube only. So
`ErrorInfo.solvableCaptchaUrl` offers the captcha screen for YouTube and
Google hosts, and for any other host the app says that the site is refusing
requests from this network for now.

"Hide paid content videos" in the PVCPipe settings sets a switch in the
extractor for YouTube, Rumble and Odysee (`PvcExtractorSettings`). With it on,
their lists leave out videos that need a membership or a payment.

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

A video that has to be bought, rented or needs a membership is marked by tags
(`c:purchase`, `c:rental`, `c:members-only`) or an LBC fee, and `get` answers
"authentication required" for it. A livestream can also be marked as
protected in its livestream state. The app reports all of these as paid
content. The kiosks leave such videos out. Search and channel lists show them
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

A `HEAD` request for an uploaded file on `player.odycdn.com` does not answer
for the file. With the referrer it redirects to the HLS master playlist when
the file has been transcoded, which is how the extractor finds the
renditions. The app therefore never asks for a file's size that way
(`DownloaderImpl.isHeadUnusable`):

- Where the downloader would use `HEAD`, it asks with `GET`. Where that
  request would have asked for everything from a position on, it asks for one
  byte, whose `Content-Range` gives the total
  (`DownloadMission.openConnection`).
- The size shown in the download dialog comes from the same kind of request
  (`PvcDownloaderImplUtils.getContentLengthViaGet`).
- The playlists and segments of the HLS renditions answer `HEAD` like any
  file and are asked as before.

A player outside the app gets the bare address and no referrer.
[odysee-observations.md](odysee-observations.md) records what the hosts
answered with and without it, and the HTTP 429 answers that kept uncached
files from being checked.

## Removed services

media.ccc.de, PeerTube and Bandcamp were removed from the extractor and the
app. Their service ids, 2, 3 and 4, are retired. The database, backups and
saved main-page tabs refer to services by id, so a new service takes the next
unused id and never one of these. Because the ids are not contiguous, look a
service up by id (`NewPipe.getService`, `Menu.findItem`) and never by its
position in `ServiceList.all()`.

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

Use `ServiceHelper.hasService` for any new code that reads a stored service
id.

## Adding or changing a service

- A new service takes the next unused id in `ServiceList` and goes at the end
  of its list.
- The app side of a service is small: a theme in `styles_services.xml` and
  `colors_services.xml` named after the service, an icon in
  `PvcServiceHelper`, a link filter in `AndroidManifest.xml`, and names for
  any new kiosk in `KioskTranslator`.
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
```

An offline test shows that the code handles a recorded or made-up answer. It
cannot show that a website still gives that answer.
