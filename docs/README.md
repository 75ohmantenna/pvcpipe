# Documentation

The code and its tests say what the app does. These pages say where to look
and why things are the way they are. If a page and the code disagree, the
page is wrong.

PVCPipe is in development. The guides describe implemented behavior and
limits, not a claim that all supported sites, receivers or devices have
passed live testing. The dated records distinguish observed results from
paths that have not been exercised.

## Guides

A guide describes the code as it is now and names the classes to read.

| Page | What it covers |
| --- | --- |
| [building.md](building.md) | Requirements, layout, `make ci`, the test suites |
| [services.md](services.md) | The five services, their search filters, removed services, adding a service |
| [playback.md](playback.md) | The player: sources, FCast casting, queue, audio focus, history, errors, the detail screen, proof tokens |
| [library-data.md](library-data.md) | Local playlists, feed refresh, subscription transfers, backup and restore |
| [download-preparation.md](download-preparation.md) | Downloads: preparation, checkpoints, sharing a finished file |
| [../GLOSSARY.md](../GLOSSARY.md) | The terms the code and these pages use |

## Records

A record describes what was observed on a stated date and build. Later code
changes do not turn an earlier failure into a pass. Keep the observations
intact, add dated follow-ups when new evidence is available, and use the
guides and implementation for current behavior.

| Page | What it records |
| --- | --- |
| [pvcpipe-device-smoke.md](pvcpipe-device-smoke.md) | Manual runs on a Pixel 6a and an emulator, October 2026 |
| [odysee-observations.md](odysee-observations.md) | What Odysee's servers answered while its service was written, October 2026 |
| [local-playlist-mutations.md](local-playlist-mutations.md) | The uninstalling instrumentation run and the procedure for testing an existing installation |
| [service-protocol-observations.md](service-protocol-observations.md) | Independent protocol checks and implementation provenance, October 14, 2026 |

`AGENTS.md` at the top of the repository holds the rules for coding agents.
