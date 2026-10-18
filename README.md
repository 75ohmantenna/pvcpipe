# PVCPipe

PVCPipe is a video and audio app for **Android 8.0+**. Subscriptions,
playlists and history stay on your device. No service account required.

**Still in development.** Expect bugs and changes in site support.

**Supported sites:** [YouTube](https://www.youtube.com/),
[SoundCloud](https://soundcloud.com/), [BitChute](https://www.bitchute.com/),
[Rumble](https://rumble.com/), [Odysee](https://odysee.com/) and [Kick](https://kick.com/).

## Casting

Cast to [FCast](https://fcast.org/) receivers on your local network using
the regular player controls. Use the cast button to connect, switch devices
or disconnect. See [setup and limits](docs/playback.md#fcast-video-casting).

## Build

```sh
./gradlew :app:assembleDebug   # debug APK
make ci                      # checks and debug/release APKs
```

[Build guide](docs/building.md) · [Documentation](docs/README.md)

**Device tests can erase app data.** Follow the [safety procedure](docs/local-playlist-mutations.md#testing-on-an-existing-installation).

Forked from [BravePipe](https://github.com/bravepipeproject/BravePipe),
based on [NewPipe](https://github.com/TeamNewPipe/NewPipe).
Licensed under [GPL-3.0-or-later](LICENSE).

PVCPipe is independent of FUTO; FCast is FUTO's registered trademark.
