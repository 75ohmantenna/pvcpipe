# Documentation

The code and its tests say what the app does. These pages say where to look
and why things are the way they are. If a page and the code disagree, the
page is wrong.

## Guides

A guide describes the code as it is now and names the classes to read.

| Page | What it covers |
| --- | --- |
| [building.md](building.md) | Requirements, layout, `make ci`, the test suites |
| [services.md](services.md) | The five services, their search filters, removed services, adding a service |
| [playback.md](playback.md) | The player: sources, queue, audio focus, history, errors, the detail screen, proof tokens |
| [library-data.md](library-data.md) | Feed refresh, subscription import and export, backup and restore |
| [download-preparation.md](download-preparation.md) | Downloads: preparation, checkpoints, sharing a finished file |
| [../GLOSSARY.md](../GLOSSARY.md) | The terms the code and these pages use |

## Records

A record says what was seen on a device or a server on a given day. It is not
kept in step with the code, and nobody should rewrite it after the fact. Read
a record for evidence, and the guides for how things work today.

| Page | What it records |
| --- | --- |
| [pvcpipe-device-smoke.md](pvcpipe-device-smoke.md) | Manual runs on a Pixel 6a and an emulator, October 2026 |
| [odysee-observations.md](odysee-observations.md) | What Odysee's servers answered while its service was written, October 2026 |
| [local-playlist-mutations.md](local-playlist-mutations.md) | The instrumentation run that uninstalled the app from a phone, and how to test on an installed app without that |

`AGENTS.md` at the top of the repository holds the rules for coding agents.
