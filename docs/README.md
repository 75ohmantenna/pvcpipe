# PVCPipe action plan and documentation

Reviewed 2026-10-30 against `d9f00de9f`. This is the current work queue; dated
failures and completed fixes are evidence, not a second backlog. Establish
behavior from implementation, callers and configuration. Tests support only the
conditions they actually assert.

## Action plan

Work in the order below when unblocked. This plan does not resume deferred FCast
work or authorize changes to capture defaults, translations or release publication.

| ID | Status | Next action and completion criteria |
| --- | --- | --- |
| A1 | **Next: suspected defect** | Reproduce the wrapped-exception self-call in [ErrorInfo](../app/src/main/java/org/schabi/newpipe/error/ErrorInfo.kt#L256), trace Media3/caller reachability, then correct the established failure. Done when controlled baseline/fixed tests prove termination and correct message, service, retry/report and link behavior without changing unrelated errors. [Finding](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/documentation-alignment.md#unknown-service-display-label--2026-10-30). |
| A2 | **Decision needed: reporting collection** | Decide log-capture default and ACRA data allowlist separately from prompt visibility and per-report attachment consent. Then implement only the chosen policy and test capture, opening, attachment selection, recreation and both exports. Current behavior/remaining exposure: [reporting guide](error-reporting.md#local-collection-is-separate-from-attachment-consent). |
| A3 | **Design review: deletion recovery** | Decide whether an additional user recovery flow is needed for media removed before its receipt becomes durable. Provider access errors must not become proof of erasure. Any change must retain cancellation, preserve unrelated data and allow an explicit safe retry. Existing [SIGKILL evidence](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/download-preparation.md#deletion-process-death-verification-2026-10-30) confirms the limitation and recovery after access is restored; it does not establish an atomic provider/database transaction. |
| A4 | **Deferred by user: FCast source startup** | Resume only when requested. Reproduce metadata-before-synchronization and already-current-occurrence resume risks before changing source-start policy. Keep exact-occurrence clock ownership and pending recovery intact. Done requires bounded scheduling regressions and appropriate receiver observations. [Scope](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/playback.md#active-cast-checkpoint-attribution--corrected-2026-10-23). |
| A5 | **Deferred / hardware dependent** | On an agreed build, verify receiver cold startup, active-connection loss, paused handoff/seek/speed, replacement, queue navigation and disconnect/play-here. Add second-receiver switching, secure-v4 and Android Auto/Desktop Head Unit checks only when those environments are available. Record fresh picture/audio/time observations; acknowledgements or mocked peers are insufficient. [Last receiver record](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/media3-migration.md#october-28-physical-receiver-measurements). |
| A6 | **Unprioritized: translations** | Choose target locales and obtain reviewed translations for maintained default-English resources. Test plural forms, app/resource locale selection, wrapping and accessibility. Current French-context fallback checks do not supply French translations. [Current label boundary](error-reporting.md#report-formatting-and-boundaries). |
| A7 | **Conditional maintenance: native dependency evidence** | When a release or native dependency update needs broader attribution evidence, extend the exact-version inventory for FCast native crates/FFmpeg rather than treating the old Java/Android audit as a complete native inventory. Preserve original notices and record evidence limits. [Dated inventory and limitations](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/license-audit.json). |

### Working rules and closure

- A1 is a hypothesis until reproduced. A3 is an implemented limitation, not a
  promise that every interruption can be recovered automatically. Deliberate
  format/platform limits stay in the guides unless separately scheduled here.
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
