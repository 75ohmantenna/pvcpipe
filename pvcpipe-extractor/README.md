# PVCPipe Extractor

This directory contains the extractor library used by PVCPipe. It is maintained
in the parent monorepo; its Gradle build also defines local Maven publication tasks.

The parent Gradle build includes this project as a composite build and substitutes
the application's extractor dependency with `:extractor` automatically.

## Development

Run extractor tests from the repository root with:

```sh
./pvcpipe-extractor/gradlew -p pvcpipe-extractor :extractor:test
```

The `:extractor:test` task runs the full extractor test suite, including tests
that may require network access. For the fork's deterministic offline regression
tests, use `./gradlew :pvcpipe-extractor:extractor:forkCiTest` from the repository root.

Run the complete application verification with:

```sh
make ci
```

## Supported sites

The following sites are currently supported:

- YouTube
- SoundCloud
- media.ccc.de
- PeerTube (no P2P)
- Bandcamp
- BitChute
- Rumble

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)  

NewPipe Extractor is Free Software: You can use, study share and improve it at your
will. Specifically you can redistribute and/or modify it under the terms of the
[GNU General Public License](https://www.gnu.org/licenses/gpl.html) as
published by the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.  
