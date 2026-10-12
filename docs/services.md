# Extractor services

PVCPipe bundles `pvcpipe-extractor` through the root Gradle composite build.
`ServiceList` registers services; each `StreamingService` supplies URL link
handlers and the extractors it supports. Link handlers identify and normalize
URLs, while stream, channel, search, comments, and kiosk extractors translate
remote responses into app-facing models. Not every service supports every
extractor type. The configured extractor downloader handles metadata requests;
playback and downloads of returned media URLs use separate app network paths.

## Rumble and BitChute

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

App subscriptions, saved playlists, history, queues, and playback modes are
local app features, not service account integration. Neither service signs in,
follows, votes, or posts comments. Private, Premium, and otherwise restricted
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

## Removed services

media.ccc.de, PeerTube and Bandcamp were removed from the extractor and the app.
Their service ids (2, 3 and 4) are retired: the database, backups and saved
main-page tabs refer to services by id, so a new service must take the next
unused id and never one of these. Because the ids are no longer contiguous,
look a service up by id (`NewPipe.getService`, `Menu.findItem`) and never by
its position in `ServiceList.all()`.

Settings migration 8 to 9 drops main-page tabs that show a kiosk, channel or
playlist of a removed service, deletes the PeerTube instance preferences, and
clears the selected service if it was a removed one. Database rows that carry
a retired id (subscriptions, history, playlist entries, bookmarked playlists)
are not deleted.

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
  `pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/rumble/RumbleService.java`
  and
  `pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/bitchute/BitchuteService.java`.
  Their sibling `linkHandler/` and `extractors/` (BitChute: `extractor/`)
  directories define URL routing and response parsing.
- Regression tests:
  `pvcpipe-extractor/extractor/src/test/java/org/schabi/newpipe/extractor/services/rumble/`
  and
  `pvcpipe-extractor/extractor/src/test/java/org/schabi/newpipe/extractor/services/bitchute/`.
  The deterministic fork suite is separate from broader tests that may contact
  third-party services.

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.rumble*'
./gradlew :pvcpipe-extractor:extractor:test --tests '*services.bitchute*'
make ci
```

Run live-service checks separately from the reproducible gate; offline
regressions cannot establish that current website responses or device playback
still work.
