# PVCPipe Extractor

This directory contains PVCPipe's extractor library. It is maintained in the
parent repository and is not published as a separate artifact. The parent build
includes the extractor as a composite build and automatically substitutes it for
the app's extractor dependency.

## Development

From the repository root, run the extractor tests with:

```sh
./pvcpipe-extractor/gradlew -p pvcpipe-extractor :extractor:test
```

`:extractor:test` runs the full extractor suite, including tests that may need
network access. For deterministic offline regression tests, run
`./gradlew :pvcpipe-extractor:extractor:forkCiTest` from the repository root.

Run all application checks and builds with:

```sh
make ci
```

## Supported sites

Supported sites:

- YouTube
- SoundCloud
- media.ccc.de
- PeerTube (no P2P)
- Bandcamp
- BitChute
- Rumble

## License

NewPipe Extractor is free software. You may use, study, share, improve, and
redistribute it under the [GNU General Public License](https://www.gnu.org/licenses/gpl.html)
as published by the Free Software Foundation, either version 3 or (at your
option) any later version.
