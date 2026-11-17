<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="PVCPipe logo" width="112">

# PVCPipe

**Watch and listen on Android without an account.**

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-C62828)](LICENSE)

</div>

PVCPipe stores subscriptions, playlists and playback history on your device.
Browse subscriptions by site in a list or grid. PVCPipe is a fork of
[BravePipe](https://github.com/bravepipeproject/BravePipe), which is based on
[NewPipe](https://github.com/TeamNewPipe/NewPipe).

> [!NOTE]
> PVCPipe is in development. Playback depends on the site, item and network.

## Supported sites

| Site | Video | Audio | Live |
| :--- | :---: | :---: | :---: |
| [YouTube](https://www.youtube.com/) | ✓ | ✓ | ✓ |
| [SoundCloud](https://soundcloud.com/) | | ✓ | |
| [BitChute](https://www.bitchute.com/) | ✓ | | ✓ |
| [Rumble](https://rumble.com/) | ✓ | ✓ | ✓ |
| [Odysee](https://odysee.com/) | ✓ | ✓ | ✓ |
| [Kick](https://kick.com/) | ✓ | | ✓ |

See the [service guide](docs/services.md) for supported links and features.

## Casting

Cast videos to [FCast](https://fcast.org/) receivers on your local network. Use
the cast button to connect, switch devices or disconnect. The regular player
controls then operate playback on the receiver. See
[setup and limits](docs/playback.md#fcast-video-casting).

PVCPipe is independent of FUTO. FCast is a registered trademark of FUTO.

## Building

See the [build guide](docs/building.md) for requirements and commands. The
[documentation index](docs/README.md) links to the other project guides.

## License

PVCPipe is licensed under [GPL-3.0-or-later](LICENSE).
