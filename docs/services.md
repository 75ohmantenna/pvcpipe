# Extractor services

PVCPipe bundles `pvcpipe-extractor` through the root Gradle composite build.
`ServiceList` registers services; each `StreamingService` supplies URL link
handlers and the extractors it supports. Link handlers identify and normalize
URLs, while stream, channel, search, comments, and kiosk extractors translate
remote responses into app-facing models. Not every service supports every
extractor type. The configured extractor downloader handles metadata requests;
playback and downloads of returned media URLs use separate app network paths.

## Rumble, BitChute and Odysee

- **Rumble:** public watch, embed, and Shorts links; video/audio formats
  (including HLS), live streams, channels and their Videos/Live tabs, browse
  and Shorts kiosks, video/channel search, suggestions, and comments/replies.
  No service playlists, channel Shorts tab, subscription import, or account
  actions.
- **BitChute:** public video, embed, and torrent links; progressive or HLS
  video when supplied by the site; channel videos, browse kiosks, video/channel
  search, suggestions, and comments/replies. No service playlists, subscription
  import, separate audio-only tracks, subtitles, or live-stream support. The
  site may provide only one playable quality.

- **Odysee:** public video, audio, livestream, embed, channel and playlist
  links on `odysee.com`, `lbry.tv` and `open.lbry.com`; the uploaded file and,
  where Odysee has transcoded it, HLS renditions; livestreams that are on air;
  channels with Videos, Livestreams and Playlists tabs; Featured, Trending and
  Live kiosks; search for videos, audio and channels with sort, date and
  length filters; suggestions; comments with likes and replies; like and
  dislike counts; view counts and follower counts through an anonymous
  visitor token. No subscription import, subtitles, or documents and images.
  See [Odysee](#odysee) below.

App subscriptions, saved playlists, history, queues, and playback modes are
local app features, not service account integration. None of these services
signs in, follows, votes, or posts comments. Odysee's anonymous visitor token,
described below, is the one identifier the app registers with a service. Private, Premium, and otherwise restricted
content cannot be validated through anonymous access. Website markup, APIs,
and media hosts can change without notice; a successful sample request does
not establish general availability.

Rumble's optional challenge handling uses Android WebView and requires a
working WebView provider; enabling it does not prove that a real challenge was
solved. BitChute has **no verified challenge recovery flow**. HTTP challenges,
rate limits, geographical restrictions, and client-dependent responses must be
distinguished from parsing regressions. Earlier emulator playback and download
observations were historical samples, **not** a claim of current live capability
or universal device support. Recheck the relevant flow against the live website
and a device when assessing current compatibility.

Search-filter groups use registered item IDs in display order; the extractor
factory resolves those IDs without changing each service's defaults or sort
variants. Rumble and SoundCloud share URL sort-parameter assembly, while their
content endpoints remain service-specific. When changing filter declarations,
check both the displayed order and the exact generated search URL.

## Odysee

Odysee is a website on top of the LBRY network. Everything on it is a *claim*:
a video, an audio file, a livestream page, a channel, a playlist or a repost.
The extractor makes the requests the website makes for a visitor who is not
signed in:

| Request | Host | Used for |
| --- | --- | --- |
| `resolve`, `claim_search`, `get` | `api.na-backend.odysee.com` | claims by address or id, lists, the media address |
| `search` | `lighthouse.odysee.tv` | search, suggestions, related videos |
| `comment.List`, `reaction.List` | `comments.odysee.tv` | comments and their likes |
| `reaction/list` | `api.odysee.com` | likes and dislikes of a video |
| `user/new`, `file/view_count`, `subscription/sub_count` | `api.odysee.com` | the visitor token, view counts, follower counts |
| `livestream/is_live`, `livestream/all` | `api.odysee.live` | who is on air |
| `$/api/content/v2/get` | `odysee.com` | the channels behind the Featured row |

**Addresses.** A video's id is its website path, such as
`@channel:c/video:v`; the API wants the same thing as `lbry://@channel#c/video#v`.
The part after the colon is the start of a 40-character claim id and may be
any length or missing, so one claim has several addresses. Lists use the
address Odysee calls canonical, and a channel that was opened by another
address takes its canonical one once it is loaded, so that a subscription is
the same whichever link it was made from. A video keeps the address it was
opened with.

**View counts and follower counts need a visitor token.** `api.odysee.com`
answers `file/view_count` and `subscription/sub_count` only for a request
that carries an `auth_token`; without one both say "authentication required"
(checked on 2026-10-12). The website gets a token by registering an anonymous
visitor for everyone who is not signed in (`user/new`, with a random
application id of 66 letters and digits), and the extractor does the same
(`OdyseeAuth`) when `OdyseeSettings.SHOW_COUNTS` is on. Registering takes no
name, address or password.

- The app switches this on by default; "Show Odysee view and follower counts"
  in the PVCPipe settings switches it off, which also deletes the token.
- One token is registered when a count is first asked for and kept in the
  app's preferences (`pvc_odysee_visitor_token_key`), so the app does not
  register a new visitor at every start. Like the rest of the preferences it
  is part of a settings export.
- A token that Odysee refuses is replaced once. A refusal is a 401 or 403
  whose answer says the visitor could not be authenticated; a 403 from
  something in front of Odysee leaves the token alone. If the new token is
  refused too, or registering fails, nothing more is registered for ten
  minutes, then twenty, and so on up to about ten hours, and the counts are
  left out meanwhile. A registration that was abandoned because the user left
  the page does not count as a failure.
- Switching the setting off while a registration is under way discards the
  token that comes back.
- The token is sent in the body of a `POST`, never in an address. Each list
  page costs one request for the view counts of its videos, and one more for
  follower counts if it lists channels; a video page asks for its own views
  and its channel's followers.
- With the token Odysee can tell that the requests of one installation belong
  together, which it could not before. Counts are an extra: a page opens the
  same without them, with the numbers missing.

A livestream shows how many are watching it now. That number comes with the
livestream state and needs no token.

**Who published a claim.** A claim names its channel and carries a signature.
The API marks a claim whose signature does not hold with
`is_channel_signature_valid: false`; such a claim is shown as published by
"Anonymous", as on the website, and not under the channel it names. The
claims of this kind seen on 2026-10-13 came with a `signing_channel` that held
only a `channel_id`.

**A video page** needs the claim, the media address, the answer to the `HEAD`
request and, for a transcoded video, the playlist, one after another. Likes,
the view count and the follower count are asked for at the same time as
these, on threads of their own, and none of them holds the page up for longer
than its own request takes.

**Media.** `get` returns one address on `player.odycdn.com`. That host and
the livestream host `cloud.odysee.live` want `Referer: https://odysee.com/`.
Observed on 2026-10-12 and 2026-10-13:

- `cloud.odysee.live` answers 403 to every playlist and segment request
  without the referrer.
- `player.odycdn.com` answers a `GET` without the referrer with 401, unless
  the file is in its CDN cache (`x-77-cache: HIT`), in which case it is served
  to anyone. A file gets into the cache once somebody has fetched it whole.
  Testing against files that were just fetched therefore makes the referrer
  look unnecessary; it is not.
- A `GET` with the referrer returns the uploaded file, with ranges. This is
  the progressive stream, and the one a download uses.
- A `HEAD` without the referrer is answered with 401, cached or not. With it,
  the answer is 200 for a file Odysee has not transcoded and a redirect to an
  HLS master playlist for one it has. The extractor asks this way on purpose
  and reads the renditions from that playlist.
- The host can answer 429 "Try again later" to requests for uncached files
  that do carry the referrer. Here that began after roughly a hundred test
  requests to some fifteen videos within an hour, and an hour later it still
  answered 429 for uncached files, from both of its origin servers
  (`x-powered-by: use-p1` and `use-p2`), to range requests and at last to
  whole-file requests too. Cached files and HLS renditions kept working.
  More than four hours after the first 429 an uncached file still could not
  be played from this address, in the app or with `curl`, and asking again
  every fifteen seconds for six minutes did not change the answer. Whether
  that is a limit on this address or how the host treats any visitor is not
  known; trying an uncached, untranscoded video from another network would
  tell. Odysee's own Android app waits and retries on 429; this app does not,
  and reports the error.

The app therefore sends the referrer on every media request to these two
hosts (`DownloaderImpl.getMediaReferer`): from the player, from the downloader
and from the extractor downloader. And because a `HEAD` request to
`player.odycdn.com` never answers for the file, the app does not send one
there (`DownloaderImpl.isHeadUnusable`): the downloader asks with `GET` for
the same range where it would use `HEAD` (`DownloadMission.openConnection`),
and the size shown in the download dialog comes from a `GET` for the first
byte, whose `Content-Range` gives the total
(`PvcDownloaderImplUtils.getContentLengthViaGet`).

A player outside the app gets the bare address and no referrer, so "open in
external player" works for an Odysee file only while the file is in the CDN
cache.

Checked on the disposable emulator on 2026-10-13 with a debug build: a
transcoded video played (at the player's default of 720p, which for that
video only an HLS rendition offers), an untranscoded one played from its
file, a livestream played, and a 1.6 MB file was downloaded in three
parallel ranges, with a checksum equal to the original's. View counts and
follower counts showed on video pages, channel pages, the Featured kiosk and
search results, and the setting that switches them off removed the token.
The two file checks used a file that was in the CDN cache, because of the
429 answers described above; an uncached one failed in the player with
HTTP 429. That an uncached file needs the referrer was seen with `curl`
only: 401 without it, and the file with it before the 429 answers began.

Checked on a Pixel 6a on 2026-10-13 with the same build, installed over the
existing one: the same transcoded video, untranscoded video and livestream
played; the channel, a playlist, the Featured kiosk and a search opened; and
all of them showed their counts. An uncached video failed there too with
HTTP 429. The phone was on the same network as the emulator. No download was
tried on the phone.

**Paid content.** A video that has to be bought, rented or needs a membership
is marked by tags (`c:purchase`, `c:rental`, `c:members-only`) or an LBC fee,
and `get` answers "authentication required" for it. The app reports it as paid
content. The kiosks leave such videos out; search and channel lists show them
unless "Hide paid content videos" is on.

**Livestreams.** A stream claim without a file is a livestream page. It plays
only while its channel is on air with that claim; otherwise the app says it is
not available. A recording that the publisher later attaches turns the page
into an ordinary video.

**Suggestions** are the titles of the first search results, which is what the
website shows while typing. Odysee has no service that completes a query.

Nothing about these hosts is documented or promised by Odysee. yt-dlp's
`lbry.py` and the source of Odysee's own Android app were the references for
the request shapes.

## Removed services

media.ccc.de, PeerTube and Bandcamp were removed from the extractor and the app.
Their service ids (2, 3 and 4) are retired: the database, backups and saved
main-page tabs refer to services by id, so a new service must take the next
unused id and never one of these. Because the ids are no longer contiguous,
look a service up by id (`NewPipe.getService`, `Menu.findItem`) and never by
its position in `ServiceList.all()`.

Settings migration 8 to 9 drops main-page tabs that show a kiosk, channel or
playlist of a removed service, deletes the PeerTube instance preferences and
the saved search presets of the three services, and clears the selected
service if it was a removed one. It tells the user once if a tab went or the
selected service changed.

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

## YouTube "sign in to confirm you're not a bot"

The app shows "YouTube refused to provide data and requires you to sign in"
when the extractor throws `SignInConfirmNotBotException`. That only happens
when YouTube's own player response says `login_required` with a reason that
mentions "a bot" (`YoutubeStreamExtractor.checkPlayabilityStatus`). For
streams the extractor asks the Android client first, then visionOS, then iOS
if that is enabled; the app supplies no proof token for any of the three. If
none of them returns streams, the restriction one of them reported is the
error the user sees.

Observed on 2026-10-11 during scripted emulator runs that opened videos over
and over from one address: some videos were refused this way and loaded again
minutes later, while others kept loading throughout. No per-client log of a
refused request was captured, and the refusals stopped before one could be,
so which clients were refused each time is not known. Nothing in these
observations points at the app.

## Source and verification

- Service registration:
  `pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/ServiceList.java`;
  service capabilities and factories:
  `pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/rumble/RumbleService.java`,
  `pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/bitchute/BitchuteService.java`
  and
  `pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/odysee/OdyseeService.java`.
  Their sibling `linkHandler/` and `extractors/` (BitChute: `extractor/`)
  directories define URL routing and response parsing.
- Regression tests:
  `pvcpipe-extractor/extractor/src/test/java/org/schabi/newpipe/extractor/services/rumble/`,
  `pvcpipe-extractor/extractor/src/test/java/org/schabi/newpipe/extractor/services/bitchute/`
  and
  `pvcpipe-extractor/extractor/src/test/java/org/schabi/newpipe/extractor/services/odysee/`.
  The deterministic fork suite is separate from broader tests that may contact
  third-party services. For Odysee the one test class that contacts the
  website is `OdyseeLiveTest`.

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.rumble*'
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.bitchute*'
./gradlew :pvcpipe-extractor:extractor:test --tests '*OdyseeLiveTest*'
make ci
```

Run live-service checks separately from the reproducible gate; offline
regressions cannot establish that current website responses or device playback
still work.
