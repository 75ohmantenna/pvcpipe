# Extractor service architecture

PVCPipe routes every remote URL through the bundled extractor. The application does not parse
YouTube, BitChute, or Rumble responses itself.

## Request flow

1. `ServiceList` owns the stable numeric service IDs used by the app and database.
2. Each `StreamingService` supplies link-handler factories for the URL types it supports.
3. A link handler validates the host and path, extracts a stable ID, and produces a canonical URL.
4. The service creates the matching stream, channel, search, comments, playlist, or kiosk
   extractor.
5. The extractor fetches remote data and exposes the service-neutral models consumed by the app.

The root Gradle build includes `pvcpipe-extractor` as a composite build and substitutes the
published extractor dependency. App builds and tests therefore always exercise the source in this
repository.

## yt-dlp-derived behavior

yt-dlp is the cross-implementation reference for service behavior. The Java implementation is
adapted to NewPipe Extractor's models and downloader API; Python code is not copied directly.

| Service | Adapted behavior | yt-dlp reference |
| --- | --- | --- |
| YouTube | Current client identities; Safari identity for web embeds; web-embed age-gate fallback; collaborator follower counts; safe manifest query composition | `yt_dlp/extractor/youtube/_base.py`, `_video.py` |
| BitChute | API media extraction; HLS recognition; old/embed/torrent URL forms; strict host validation | `yt_dlp/extractor/bitchute.py` |
| Rumble | Format classification; HLS variants; audio and captions; live-state semantics; channel/user URL validation | `yt_dlp/extractor/rumble.py` |

The source behavior was reviewed from the local yt-dlp checkout. Relevant upstream yt-dlp changes
include `1d0f6539c` (BitChute API), `58d0c8345` (Rumble formats), `5d5b634d8` (YouTube web-embed
fallbacks), `5d6b8c8cd` (collaborator follower counts), and `c7fb478d2` (Safari web-embed identity).

## Verification

Run extractor unit tests independently:

```sh
./pvcpipe-extractor/gradlew -p pvcpipe-extractor :extractor:test
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

The complete extractor suite uses live third-party services and is intentionally
kept separate from the reproducible gate. Run it when validating broader
service compatibility:

```sh
./pvcpipe-extractor/gradlew -p pvcpipe-extractor :extractor:test
```
