# PVCPipe extractor

PVCPipe's extraction library reads metadata and media addresses from YouTube,
SoundCloud, BitChute, Rumble, Odysee and Kick. It descends from NewPipe Extractor.

Service IDs are persistent data identifiers: YouTube is 0, SoundCloud 1,
BitChute 5, Rumble 6, Odysee 7 and Kick 8. IDs 2, 3 and 4 remain retired.
Use `ServiceList` for registration order and `NewPipe.getService(id)` for
lookup; a service ID is not a list index.

It lives in the app's repository and is not published as an artifact. The
app's build includes this directory as a composite build and substitutes it
for the app's extractor dependency, so the commands below run from the
repository root.

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest   # Checkstyle and offline tests
./gradlew :pvcpipe-extractor:extractor:test         # all tests, including live-network tests
make ci                                           # all checks and both APKs
```

The extractor's settings file includes two Gradle projects: `extractor`, the
library, and `timeago-generator`, which generates relative-date patterns.
The `extractor` source set also compiles Java sources from `timeago-parser/`,
which reads dates such as "3 days ago" in many languages. That directory is
not a separately included Gradle project.

[services.md](../docs/services.md) describes service capabilities and limits;
[building.md](../docs/building.md) covers the build and test suites.

The library extracts metadata, delivery types and media addresses. Playback,
downloads and FCast sender/session/media-proxy integration are app code, not
extractor features. See [docs/playback.md](../docs/playback.md) for FCast
controls and media limits. Offline fixtures establish parser behavior, not
current website availability; live observations remain dated records.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
