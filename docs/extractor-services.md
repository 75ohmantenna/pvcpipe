# Extractor service architecture

PVCPipe uses the bundled extractor to resolve supported service URLs and fetch
stream and channel metadata. The app's extractor downloader issues metadata
requests; playback and downloads from the resulting media URLs use separate network
paths (for example, `DownloadMission.openConnection` uses `HttpURLConnection`).

## Request flow

1. `ServiceList` assigns stable numeric service IDs used by the app and database;
   the list currently includes YouTube, SoundCloud, MediaCCC, PeerTube, Bandcamp,
   BitChute, and Rumble.
2. Each `StreamingService` supplies link-handler factories for the URL types it supports.
3. A link handler validates a supported URL, extracts its ID, and produces a
   canonical URL.
4. The service creates a matching stream, channel, search, comments,
   playlist, or kiosk extractor (not every service supports every type).
5. The extractor fetches remote data and exposes service-neutral models consumed
   by the app. Its network requests use the configured extractor downloader.

The root Gradle build includes `pvcpipe-extractor` as a composite build and substitutes the
published extractor dependency. App builds and tests therefore always exercise the source in this
repository.

## yt-dlp-derived behavior

yt-dlp is the cross-implementation reference for service behavior. The Java implementation is
adapted to NewPipe Extractor's models and downloader API; Python code is not copied directly.

| Service | Adapted behavior | yt-dlp reference |
| --- | --- | --- |
| YouTube | Current client identities; Safari identity for web embeds; independent Android/visionOS player validation and age-gate web-embed fallback; collaborator follower counts; safe manifest query composition | `yt_dlp/extractor/youtube/_base.py`, `_video.py` |
| BitChute | API media extraction; HLS recognition; old/embed/torrent URL forms; strict host validation | `yt_dlp/extractor/bitchute.py` |
| Rumble | Format classification; HLS variants; audio and captions; live-state semantics; channel/user URL validation; canonical-path Shorts routing | `yt_dlp/extractor/rumble.py` |

The behavior was reviewed in a local yt-dlp checkout. Relevant upstream yt-dlp changes
include `1d0f6539c` (BitChute API), `58d0c8345` (Rumble formats), `5d5b634d8` (YouTube web-embed
fallbacks), `5d6b8c8cd` (collaborator follower counts), and `c7fb478d2` (Safari web-embed identity).

The YouTube player clients retain only responses for the requested video with a
supported stream descriptor or manifest URL. A failed Android request does not
prevent a valid visionOS response from supplying metadata and formats; duration
fallback also checks that client's adaptive formats. If no client succeeds, the
extractor preserves a specific access restriction over a generic client failure.
Rumble routes Shorts by the normalized video path, not query or fragment text.

## Rumble website verification

[Rumble integration and verification](rumble-integration.md) records the request paths,
compatibility corrections, emulator observations, and remaining live acceptance checks.

## BitChute website verification

[BitChute integration and verification](bitchute-integration.md) records the current website API,
comment authorization and replies, search/filter corrections, and emulator verification.

## Verification

Run the fork's deterministic offline extractor regression tests independently:

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest
```

Maven publications are local-only, under `pvcpipe-extractor/extractor/build/maven`.
Snapshot publications default to `<extractor-version>-SNAPSHOT`; override the
version with `-PextractorSnapshotVersion=<version>`. Configuring or building the
extractor does not require Git metadata or publishing credentials.

Run all application checks, both APK builds, and the extractor's deterministic
regression tests:

```sh
make ci
```

The complete extractor suite (`:extractor:test`) may use live third-party
services and is intentionally kept separate from the reproducible gate. Run it
when validating broader service compatibility:

```sh
./pvcpipe-extractor/gradlew -p pvcpipe-extractor :extractor:test
```
