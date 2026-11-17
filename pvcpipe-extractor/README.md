# PVCPipe extractor

PVCPipe's extraction library reads metadata, delivery types and media URLs from
the [registered services](../docs/services.md#the-services). It descends from
NewPipe Extractor.

[`ServiceList`](extractor/src/main/java/org/schabi/newpipe/extractor/ServiceList.java)
defines registration order and persistent service IDs. Use
`NewPipe.getService(id)` to resolve a stored ID. IDs 2, 3 and 4 remain retired.

The app uses this directory as a composite build, substituting it for the
extractor dependency declared in the app's version catalog. Run these commands
from the repository root.

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest   # Checkstyle and offline tests
./gradlew :pvcpipe-extractor:extractor:test        # includes live-network tests
make ci                                          # local checks and both APKs
```

The two Gradle projects are `extractor` and `timeago-generator`. The generator
produces relative-date patterns in `timeago-parser/`, a source directory compiled
into `extractor`.

The app implements playback, downloads and FCast integration. See
[building and testing](../docs/building.md) for test suites and downloader
selection, and [playback](../docs/playback.md) for FCast controls and media limits.

## Regex helpers

`Parser.matchGroup`, `matchGroup1` and `matchGroup1MultiplePatterns` return
nullable strings. An unmatched optional capture returns null. A participating
empty capture returns `""`, and group zero returns the whole match. These helpers
use `find()` and throw `Parser.RegexException` if it finds no match.
An invalid group number after a match throws `IndexOutOfBoundsException`.

`matchGroup1MultiplePatterns` stops at the first pattern with a match, even
when its capture is null. `Utils.getStringResultFromRegexArray` instead tries
later patterns after null captures and throws if none supplies a non-null
result. Handle nullable group results in Java and Kotlin callers.
`matchOrThrow` returns a non-null matcher or throws `RegexException`.

`ParserTest`, tagged `offline`, checks these distinctions and the regex-array
caller's fallback behavior without a downloader or service connection.

## Maintained and generated sources

Preserve the copyright and license headers on inherited NewPipe Extractor code.

Regenerate relative-date patterns from the source data using the
[time-ago generator instructions](timeago-parser/raw/README.md).
BitChute response models under
`extractor/src/main/java/com/github/pvcpipe/json2java4nanojson/` carry
json2java4nanoJson generation notices. The extractor build also generates Java
from Protocol Buffers and Wire definitions. Preserve those generation and
provenance notices when updating the corresponding sources.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
