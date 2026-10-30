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

`DownloaderImpl` reports every HTTP 429 as a `ReCaptchaException`. The app
can only do something about a captcha from YouTube: `ReCaptchaActivity` keeps
YouTube's cookies and `DownloaderImpl` sends them back to YouTube only. So
`ErrorInfo.solvableCaptchaUrl` offers the captcha screen for YouTube and
Google-related hosts in its explicit allowlist. For other hosts it offers
the service-specific “is not accepting requests right now” message rather
than a challenge it cannot use. This classification does not establish the
cause or scope of the refusal; Kick security blocks also use `ReCaptchaException`.

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

YouTube's general Trending kiosk is retired; live and category feeds remain.
Settings migration 9 → 10 removes dedicated general-Trending tabs, preserving
other tabs and restoring defaults only when none remain.
`YoutubeStreamExtractor` tries Android, an embedded-web fallback for age
restriction, visionOS, and optionally iOS. The app supplies proof tokens only
through the web-client provider; see [playback.md](playback.md#youtube-proof-tokens).
A `login_required` response mentioning a bot becomes `SignInConfirmNotBotException`.

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

Kick supports public live broadcasts, retained VODs, channel search/suggestions,
Featured discovery and local subscriptions. Channels have Videos and Livestreams
tabs. `KickApi` defines the channel, fresh live playback URL, VOD, legacy-video,
search and featured endpoints; extractors do not invent continuation pages.

Plain channel links open the current broadcast; channel `/videos` and `/about`
links open the channel. Channel-scoped VOD links and older `/video/{uuid}` links
are supported. Channel-scoped VOD lookup falls back to the legacy API only for
404. Nonnegative VOD `t` offsets are read before canonicalization; invalid values
fall back to zero and live playback ignores those recording offsets.
Broadcast-specific `live` markers prevent an old feed/history entry from becoming
a different broadcast. Subscription URLs remain channel Videos URLs.

The internal live player uses the HLS master. External playback and VOD download
selection can use combined HLS renditions. Ongoing recordings switch to live
playback rather than being offered as finished downloads. Metadata/source caches
are limited to one minute because live tokens expire quickly. A playback URL
alone is not proof of live state; hidden or unknown viewer counts are omitted.
Ordinary 403 responses are not challenges without challenge markers.

Clips, playlists, comments, live chat, subtitles and account-follows import are
not implemented. Playback has no Pusher/Centrifugo dependency. See
[the kick implementation](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/kick/) and the [dated protocol record](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/service-protocol-observations.md#kick-follow-up--2026-10-18)
for source/image details and the limits of earlier transport observations.

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
