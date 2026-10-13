# PVCPipe extractor

The library that reads YouTube, SoundCloud, BitChute, Rumble and Odysee for
PVCPipe. It descends from NewPipe Extractor.

It lives in the app's repository and is not published as an artifact. The
app's build includes this directory as a composite build and substitutes it
for the app's extractor dependency, so the commands below run from the
repository root.

```sh
./gradlew :pvcpipe-extractor:extractor:forkCiTest   # Checkstyle and the offline tests
./gradlew :pvcpipe-extractor:extractor:test         # every test, including those that contact the websites
make ci                                             # all checks and both APKs
```

There are three modules. `extractor` is the library. `timeago-parser` reads
relative dates such as "3 days ago" in many languages, and
`timeago-generator` builds that parser's pattern classes.

[docs/services.md](../docs/services.md) describes each service, and
[docs/building.md](../docs/building.md) the build and the tests.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
