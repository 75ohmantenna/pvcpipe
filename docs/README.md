# PVCPipe documentation

The implementation and build configuration define current behavior. Use the
guides to find the relevant code. Check what each test exercises before using
its results to describe device, receiver or service behavior.

## Current guides

| Guide | Scope |
| --- | --- |
| [Building and testing](building.md) | Requirements, build commands, signing, versioning and device-test safety |
| [Services](services.md) | Persistent IDs, implemented capabilities, URL handling and retired services |
| [Playback](playback.md) | Source and queue ownership, FCast, recovery, history and proof tokens |
| [Library data](library-data.md) | Playlists, bookmarks, feeds, subscriptions and backup/restore |
| [Downloads](download-preparation.md) | Submission, checkpoints, range recovery, deletion, HLS and sharing |
| [Error reporting](error-reporting.md) | Copy/share, local capture, timestamps and report contents |
| [Glossary](../GLOSSARY.md) | Project terminology |

## Open verification

- The November 15 physical FCast run covered discovery, paused handoff, play,
  pause, paused seek and Disconnect on one Fire Stick receiver. Cold startup,
  active connection loss, speed, source changes, queue navigation and
  **Play on this device** still need receiver verification. Switching between
  receivers and secure v4 require the corresponding receiver setups.
  Android Auto/Desktop Head Unit is a separate environment.
  [Recorded receiver run](https://github.com/75ohmantenna/pvcpipe/blob/e7cb3f6b25b55cc47b2b409e9b2f621fe9e311bb/docs/README.md#later-pixel-and-receiver-verification-2026-11-15)
- Physical rotation and popup-surface behavior still need device checks. The
  fullscreen black-screen report remains an investigation lead. The
  [player mode guide](playback.md#rotation-and-player-modes) identifies the
  controlled coverage.
- The YouTube throttling transform still needs a verified input/output pair
  for the stored player where the matcher selects an unrelated helper.
  [Player JavaScript compatibility](services.md#player-javascript-compatibility)
- [Dependency verification](building.md#dependency-integrity-boundary) requires
  reviewed initial checksums and build and host coverage. Native dependency
  attribution should be revisited when a release or dependency change requires
  a broader inventory. [Previous attribution inventory](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/license-audit.json)

Default-English resources are the maintained UI copy. The owner closed the
translation pass in November 2026.

## Historical records

- The [November action plan and verification notes](https://github.com/75ohmantenna/pvcpipe/blob/e7cb3f6b25b55cc47b2b409e9b2f621fe9e311bb/docs/README.md)
  preserve the completed findings, test counts and limits for the tested builds.
- The [October documentation audit](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/documentation-alignment.md)
  records the findings from the earlier consolidation. The
  [baseline documentation tree](https://github.com/75ohmantenna/pvcpipe/tree/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs)
  includes protocol observations, device results and attribution records.

Use each record with its stated build and environment. Temporary paths in old
records identify artifacts from those runs. Follow the current
[device-test procedure](building.md#testing-on-an-existing-installation).
