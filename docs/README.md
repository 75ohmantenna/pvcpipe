# Documentation

The implementation and tests define PVCPipe's behavior. These pages explain
that behavior, its limits and the code behind it. If a guide disagrees with
the implementation, the guide needs correcting.

PVCPipe is in development. The guides describe implemented behavior and
limits, not a claim that all supported sites, receivers or devices have
passed live testing. The dated records distinguish observed results from
paths that have not been exercised.

## Guides

A guide describes the code as it is now and names the classes to read.

| Page | What it covers |
| --- | --- |
| [building.md](building.md) | SDK and JDK requirements, build tasks, application IDs, signing, versioning and safe testing |
| [services.md](services.md) | Service IDs, extraction capabilities, search filters, Kick metadata, retired services and integration requirements |
| [playback.md](playback.md) | Source selection, FCast setup and limits, queue control, audio focus, history, errors and YouTube proof tokens |
| [library-data.md](library-data.md) | Local playlists, service-grouped subscriptions, feed refresh, subscription transfers, backup and restore |
| [download-preparation.md](download-preparation.md) | Download selection and submission, checkpoints, HLS processing and finished-file sharing |
| [../GLOSSARY.md](../GLOSSARY.md) | The terms the code and these pages use |

## Records

A record describes what was observed on a stated date and build. Later code
changes do not turn an earlier failure into a pass. Keep the observations
intact, add dated follow-ups when new evidence is available, and use the
guides and implementation for current behavior.

| Page | What it records |
| --- | --- |
| [pvcpipe-device-smoke.md](pvcpipe-device-smoke.md) | Dated device and emulator runs, FCast receiver recovery, and the 0.2.3 adversarial review, October 2026 |
| [odysee-observations.md](odysee-observations.md) | What Odysee's servers answered while its service was written, October 2026 |
| [local-playlist-mutations.md](local-playlist-mutations.md) | The uninstalling instrumentation run and the procedure for testing an existing installation |
| [service-protocol-observations.md](service-protocol-observations.md) | Protocol observations, implementation provenance and controlled extraction follow-ups, October 2026 |

`AGENTS.md` at the top of the repository holds the rules for coding agents.
