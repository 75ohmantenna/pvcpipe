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

## Regex helpers

`Parser.matchGroup`, `matchGroup1` and `matchGroup1MultiplePatterns` return
nullable strings: an unmatched optional capture is null, while a participating
empty capture is `""`. If no pattern matches, these helpers throw
`Parser.RegexException`; an invalid group number after a match throws
`IndexOutOfBoundsException`.
Group zero returns the whole matched substring. Matching uses `find()`.

`matchGroup1MultiplePatterns` stops at the first pattern with a match, even
when its capture is null. `Utils.getStringResultFromRegexArray` instead tries
later patterns after null captures and throws if none supplies a non-null
result. Java and Kotlin consumers must handle nullable group results;
Kotlin source relying on the former non-null/platform types may need changes.
`matchOrThrow` still returns a non-null matcher or throws `RegexException`.

`ParserTest`, tagged `offline`, checks these distinctions and the regex-array
caller's fallback behavior without a downloader or service connection.

## Maintained and generated sources

The service implementation and offline regression tests are maintained in this
fork. Existing NewPipe Extractor copyright and license headers identify inherited
code; they are not statements of the app's current service support.

Do not hand-edit generated relative-date patterns in `timeago-parser/`; their
source data and generator entry points are described in
[`timeago-parser/raw/README.md`](timeago-parser/raw/README.md).
BitChute response models under
`extractor/src/main/java/com/github/pvcpipe/json2java4nanojson/` carry
json2java4nanoJson generation notices. The extractor build also generates Java
from Protocol Buffers and Wire definitions. Preserve those generation and
provenance notices when updating the corresponding sources.

The commands above are maintained build examples, not evidence of current
service availability. Service tests using fake downloaders exercise only their
supplied responses.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
