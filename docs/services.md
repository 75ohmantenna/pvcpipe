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
