# PVCPipe action plan and documentation

Consolidated 2026-10-30 against `d9f00de9f`, with subsequent corrections below.
This is the current work queue; dated failures and completed fixes are evidence,
not a second backlog. Establish
behavior from implementation, callers and configuration. Tests support only the
conditions they actually assert.

## Action plan

Work on open items in the order below when unblocked. This plan does not resume
deferred FCast work or authorize changes to capture defaults, translations or
release publication.

| ID | Status | Next action and completion criteria |
| --- | --- | --- |
| A1 | **Defensive fix verified, 2026-11-11** | The exact synthetic ExoPlaybackException → UnexpectedLoaderException → ExtractionException chain failed with StackOverflowError before the narrow [ErrorInfo](../app/src/main/java/org/schabi/newpipe/error/ErrorInfo.kt) descent fix. `WrappedPlaybackErrorTest` now proves termination, inner message, service/action and unchanged outer retry/report/link metadata, plus unrelated-wrapper controls. Normal extraction uses a different wrapper; production reachability of this exact chain remains unproven. [Original finding](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/documentation-alignment.md#unknown-service-display-label--2026-10-30). |
| A2 | **Implemented and focused-tested (2026-11-15)** | New local diagnostic-log opt-in defaults off, including upgrades; imports preserve current local consent. New ACRA records select only stack trace and required bookkeeping. Existing files are not purged. 59 focused JVM/Robolectric tests pass, including real archive imports, activation/rollback, actual ACRA collectors/sender, report exports, timestamp and unchecked attachment recreation; reporting instrumentation sources compile but were not run on a device. See [reporting guide](error-reporting.md#local-collection-is-separate-from-attachment-consent). |
| A3 | **Guidance-only recovery improvement, focused-tested (2026-11-15)** | Deletion-pending rows and finished rows with a deletion receipt offer read-only Deletion help: the file may already be gone; check storage/provider availability, access and free internal space, then explicitly retry Delete. 24 focused JVM/Robolectric tests and Checkstyle/Ktlint pass, including real adapter/menu/dialog resources; no new device verification. This adds no abandonment, permission grant or deletion-state change. The provider/receipt crash gap remains: unavailable or denied access is not proof of erasure. See [current deletion behavior](download-preparation.md#removing-entries) and the [prior SIGKILL evidence](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/download-preparation.md#deletion-process-death-verification-2026-10-30). |
| A4 | **Synchronization correction covered by controlled JVM tests** | Source preparation now waits for coherent occurrence metadata and retries after synchronization, including return to an already-current occurrence. `CastPlaybackTest` covers metadata/completion ordering, recovery/default seeks and canceled-preparation retries; `CastClockAttributionTest` covers pending recovery and clock ownership. These tests use controlled engine/resolver/session behavior, not a real receiver. See [current behavior](playback.md#cast-synchronization-liveness); fresh hardware observations remain A5. |
| A5 | **Deferred / hardware dependent** | On an agreed build, verify receiver cold startup, active-connection loss, paused handoff/seek/speed, replacement, queue navigation and disconnect/play-here. Add second-receiver switching, secure-v4 and Android Auto/Desktop Head Unit checks only when those environments are available. Record fresh picture/audio/time observations; acknowledgements or mocked peers are insufficient. [Last receiver record](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/media3-migration.md#october-28-physical-receiver-measurements). |
| A6 | **Out of scope by owner decision (2026-11-15)** | Translations are not important for this personal fork. The translation pass was canceled and its changes removed; default-English resources remain. Do not treat this as pending locale selection or resume without a new request. [Current label boundary](error-reporting.md#report-formatting-and-boundaries). |
| A7 | **Conditional maintenance: native dependency evidence** | When a release or native dependency update needs broader attribution evidence, extend the exact-version inventory for FCast native crates/FFmpeg rather than treating the old Java/Android audit as a complete native inventory. Preserve original notices and record evidence limits. [Dated inventory and limitations](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/license-audit.json). |

### A2/A3 combined verification (2026-11-15)

After independent adversarial review, the combined changes passed `make ci`:
1,476 app tests, 1,122 extractor tests (three existing skips), three build-logic
tests, 17 Python tests, static checks, Android lint, and debug and optimized
release assembly. Reporting instrumentation sources also compiled separately.
These are host/controlled tests and build results, not new physical-device
verification. No existing reports or logs were purged and no installed app was
updated during this work. A5 remains deferred; A6 is out of scope by owner decision;
A7 remains conditional because this change updates no native dependency.

### Working rules and closure

- A1 is a defensive exact-input fix, not a reproduced production crash.
  A3 is an implemented limitation, not a
  promise that every interruption can be recovered automatically. Deliberate
  format/platform limits stay in the guides unless separately scheduled here.
- A4's scheduling correction has controlled JVM coverage. It does not close
  A5's receiver/device verification or turn older hardware results into evidence
  for the current code.
- For each accepted code change: trace callers and defaults, reproduce the
  changed path, perform adversarial review and fix related findings, then run
  `make ci`. Distinguish static inspection, JVM/doubles, controlled peers,
  emulators and actual hardware/service observations.
- Follow [device safety](building.md#testing-on-an-existing-installation).
  Use disposable data; never uninstall or clear a user's app to run a check.
- When closing an item, record its commit, checks and remaining boundaries here
  or in the relevant guide. Keep detailed dated evidence outside this compact
  checkout, tied to the tested revision. Do not rewrite an old failure as a pass.
- Keep the Git pre-push guard and [agent rules](../AGENTS.md). Remote submission
  is restricted to `github.com/75ohmantenna/pvcpipe`; this plan creates no GitHub
  issues, PRs, tags or releases.

## Closed: UI audit of 2026-10-30

The 15 audit findings were fixed in #162 to #166. Evidence is from a Pixel 6a on
API 37 with 3-button navigation, an API 26 emulator, and `make ci` on each branch.

Open boundaries:

- Not run: gesture navigation, split-screen, TalkBack itself (names were read from
  the accessibility tree), a real CAPTCHA page, the local player with a real
  download, and a 360dp-wide window.
- The popup player shares `player.xml`, so controls it shows keep their old size.
  The pitch semitone step buttons are also still 24dp wide.
- On API 27 to 34 the local player's navigation area shows the window background.
- In landscape, the toolbar's cutout-side inset shows the window background.
- Fixed-height text at large font sizes remains a runtime check.

## Current guides

| Guide | Scope |
| --- | --- |
| [Building and testing](building.md) | Requirements, commands, signing/versioning, safe native testing and host tools |
| [Services](services.md) | Persistent IDs, supported capabilities, request/URL boundaries and retired services |
| [Playback](playback.md) | Source/queue ownership, FCast controls and limits, recovery/history and proof tokens |
| [Library data](library-data.md) | Playlists, bookmarks, feeds, subscriptions and backup/restore failure boundaries |
| [Downloads](download-preparation.md) | Submission, checkpoints, range recovery, deletion, HLS and file sharing |
| [Error reporting](error-reporting.md) | Copy/share, capture, timestamps, display labels and diagnostic exposure |
| [Glossary](../GLOSSARY.md) | Project terminology |

## History

The dated audits, test transcripts, screenshots and earlier full guides were
removed from this checkout after checksum-verified local archiving. No historical
result is reclassified as a pass. The archive preserves original results, notices
and attribution byte for byte. The baseline
remains reachable through Git history:

- [Full documentation at the consolidation baseline](https://github.com/75ohmantenna/pvcpipe/tree/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs).
- [Audit and completed follow-ups](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/documentation-alignment.md).
- [Version-specific license/attribution inventory](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/license-audit.json).

Those pages describe their stated builds and environments. Temporary paths in
old records identify provenance, not guaranteed current artifact locations.
Use the current guides for commands and safety procedures.
