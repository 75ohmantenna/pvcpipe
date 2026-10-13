# Services

A service is a website the app can browse and play from. This page says what
each one supports and where its code is. It describes the code. What the
websites themselves did on a given day is in the records, mainly
[odysee-observations.md](odysee-observations.md).

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
| YouTube | 0 | audio, video, live | `live`, `Trending`, `trending_gaming`, `trending_music`, `trending_movies_and_shows`, `trending_podcasts_episodes` | yes | yes |
| SoundCloud | 1 | audio | `New & hot` | yes | yes |
| BitChute | 5 | video | `Trending Today`, `Trending This Week`, `Trending This Month`, `Popular`, `Suggested` | no | no |
| Rumble | 6 | audio, video, live | `Today's Battle Leaderboard Top 50`, `Editor Picks`, `Live`, `Trending Today`, `Latest`, `Shorts` | no | no |
| Odysee | 7 | audio, video, live | `Featured`, `Trending`, `live` | yes | no |

All five have comments, search and search suggestions. The kiosk names in the
table are the ids the code uses. `KioskTranslator` turns the ones it knows
into display names.

YouTube also has a feed extractor. The feed refresh uses it in place of
loading each channel when the dedicated feed setting
(`feed_use_dedicated_fetch_method_key`) is on. The other services have none,
so a refresh loads the channel and its tab.

The app has no sign-in for any service. It does not follow, vote or post.
Subscriptions, saved playlists, history and queues are kept on the device
(see [library-data.md](library-data.md)). The one identifier the app registers
with a service is Odysee's anonymous visitor token, described under
[Odysee](#odysee).

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

`YoutubeStreamExtractor.onFetchPage` asks YouTube's Android client for a
video first. If that client says the video is age-restricted, it asks the
embedded web client. It then asks the visionOS client, and the iOS client if
that is switched on. The app supplies a proof token only for web clients (see
[playback.md](playback.md)). If no client returns a player response, a
restriction that one client reported is shown in preference to another
client's network error.

The app shows "YouTube refused to provide data and requires you to sign in"
when the extractor throws `SignInConfirmNotBotException`. That happens when a
player response says `login_required` with a reason that mentions "a bot"
(`YoutubeStreamExtractor.checkPlayabilityStatus`).

## SoundCloud

SoundCloud is audio only. A subscription import takes the address of a
SoundCloud profile and imports the channels that profile follows.

## BitChute

- Links: video, embed and torrent addresses on `bitchute.com`,
  `www.bitchute.com` and `old.bitchute.com`, and channel addresses.
- A video comes as one stream, progressive or HLS, whichever the site gives.
  There are no separate audio tracks, subtitles or livestreams.
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
  (`PvcCfChallenge403Interceptor`). It hands the challenge to a WebView, so
  it needs a working WebView on the device.

## Odysee

Odysee is a website on top of the LBRY network. Everything on it is a claim: a
video, an audio file, a livestream page, a channel, a playlist or a repost.
The extractor makes the requests the website makes for a visitor who is not
signed in. They are all in
[`OdyseeApi`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/odysee/OdyseeApi.java).

| Request | Host | Used for |
| --- | --- | --- |
| `resolve`, `claim_search`, `get` | `api.na-backend.odysee.com` | claims by address or id, lists, the media address |
| `search` | `lighthouse.odysee.tv` | search, suggestions, related videos |
| `comment.List`, `reaction.List` | `comments.odysee.tv` | comments and their likes |
| `reaction/list` | `api.odysee.com` | likes and dislikes of a video |
| `user/new`, `file/view_count`, `subscription/sub_count` | `api.odysee.com` | the visitor token, view counts, follower counts |
| `livestream/is_live`, `livestream/all` | `api.odysee.live` | who is on air |
| `$/api/content/v2/get` | `odysee.com` | the channels behind the Featured kiosk |

Odysee documents none of this. yt-dlp's `lbry.py` and the source of Odysee's
own Android app were the references for the request shapes.

### Addresses

[`OdyseeUrl`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/odysee/OdyseeUrl.java)
reads addresses on `odysee.com`, `www.odysee.com`, `lbry.tv` and
`open.lbry.com`: videos, channels, embed and download links, and playlists.

A video's id is its website path, such as `@channel:c/video:v`. The API wants
the same thing as `lbry://@channel#c/video#v`. The part after the colon is
the start of a 40-character claim id. It may be any length or missing, so one
claim has several addresses. Lists use the address Odysee calls canonical. A
channel that was opened by another address takes its canonical one once it is
loaded, so that a subscription is the same whichever link it was made from. A
video keeps the address it was opened with.

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
- Suggestions are the titles of the first search results. Odysee has no
  service that completes a query.
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
- A token that Odysee refuses is replaced once. A refusal is a 401 or 403
  whose answer says the visitor could not be authenticated. A 403 from
  something in front of Odysee leaves the token alone.
- If the new token is refused too, or registering fails, nothing more is
  registered for ten minutes, and for twice as long after each further
  failure, up to about ten hours. A registration that was abandoned because
  the user left the page does not count as a failure.
- Each list page costs one request for the view counts of its videos, and one
  more for follower counts if it lists channels. A video page asks for its
  own views and its channel's followers. After a count request that got no
  answer, none is made for a minute.
- With the token Odysee can tell that the requests of one installation belong
  together. Counts are an extra. A page opens the same without them, with the
  numbers missing.

### A video page

`OdyseeStreamExtractor.onFetchPage` needs the claim, the media address, the
answer to a `HEAD` request for that address and, for a transcoded video, the
playlist, one after another. Likes, the view count and the follower count are
asked for at the same time as these, on threads of their own. None of them
holds the page up for longer than its own request takes, and none of them
fails the page.

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
