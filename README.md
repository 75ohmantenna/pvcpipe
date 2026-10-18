# PVCPipe

PVCPipe is an Android video and audio app. Subscriptions, local playlists
and history stay on the device; no service account is required.

**PVCPipe is still in development.** Expect bugs and changing behavior.
Third-party sites can change independently of the app; the guides document
current capabilities and limits, not a guarantee that every site is available.

It is a fork of [BravePipe](https://github.com/bravepipeproject/BravePipe),
which is based on [NewPipe](https://github.com/TeamNewPipe/NewPipe). It runs
on Android 8.0 and later. The app and bundled extractor share the version in
[version.properties](version.properties).

## Supported sites

- [YouTube](https://www.youtube.com/)
- [SoundCloud](https://soundcloud.com/)
- [BitChute](https://www.bitchute.com/)
- [Rumble](https://rumble.com/)
- [Odysee](https://odysee.com/)

Features differ by service. See the [service guide](docs/services.md) for
search, channel, playlist, livestream and download capabilities and limitations.

## FCast casting

Cast videos to [FCast](https://fcast.org/) receivers on the same network.
Use the player's cast button to discover or add a receiver, switch devices,
disconnect, or return playback to the phone. The regular player controls
operate the cast video: play/pause, timeline seeking and double-tap
rewind/fast-forward.

The phone serves media to the receiver, so keep the app and its network
connection available. FCast is not Chromecast or AirPlay.
See [casting setup and limits](docs/playback.md#fcast-video-casting).

## Build

```sh
./gradlew :app:assembleDebug   # debug APK
make ci                        # checks, unit tests and both APKs
```

You need Android SDK platform 37 and JDK 11, 17 and 21.
[docs/building.md](docs/building.md) has the details.

Use a disposable emulator for Gradle-managed instrumentation tests.
`connectedDebugAndroidTest` can uninstall the app and erase its data. For an
existing installation, follow the
[device-safety procedure](docs/local-playlist-mutations.md#testing-on-an-existing-installation).

## Documentation

[docs/](docs/README.md) lists the developer guides and the dated test
records. [GLOSSARY.md](GLOSSARY.md) defines the terms they use.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

PVCPipe is independent of and not affiliated with FUTO. FCast is a registered
trademark of FUTO. The FCast sender SDK is MIT-licensed; its attribution and
Java Native Access are listed in the app's third-party licenses.
