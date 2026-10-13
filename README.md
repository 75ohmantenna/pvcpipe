# PVCPipe

PVCPipe is an Android app that plays videos and audio from YouTube,
SoundCloud, BitChute, Rumble and Odysee without an account on any of them.
Subscriptions, playlists and history stay on the device.

It is a fork of [BravePipe](https://github.com/bravepipeproject/BravePipe),
which is based on [NewPipe](https://github.com/TeamNewPipe/NewPipe). It is in
development and has no release yet. It runs on Android 8.0 and later.

## Build

```sh
./gradlew :app:assembleDebug   # debug APK
make ci                        # checks, unit tests and both APKs
```

You need Android SDK platform 37 and JDK 11, 17 and 21.
[docs/building.md](docs/building.md) has the details.

Run instrumentation tests only on a disposable emulator.
`connectedDebugAndroidTest` can uninstall the app and erase its data.

## Documentation

[docs/](docs/README.md) lists the developer guides and the dated test
records. [GLOSSARY.md](GLOSSARY.md) defines the terms they use.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
