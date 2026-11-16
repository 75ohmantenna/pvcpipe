# PVCPipe action plan and documentation

Consolidated 2026-10-30 against `d9f00de9f`, with subsequent corrections below.
This is the current work queue; dated failures and completed fixes are evidence,
not a second backlog. Establish
behavior from implementation, callers and configuration. Tests support only the
conditions they actually assert.

## Action plan

Work on open items when their prerequisites and scope are agreed. The basic FCast
smoke pass below was explicitly authorized; it does not automatically schedule the
remaining hardware matrix. This plan does not authorize changes to capture
defaults, translations or release publication.

| ID | Status | Next action and completion criteria |
| --- | --- | --- |
| A1 | **Defensive fix verified, 2026-11-11** | The exact synthetic ExoPlaybackException → UnexpectedLoaderException → ExtractionException chain failed with StackOverflowError before the narrow [ErrorInfo](../app/src/main/java/org/schabi/newpipe/error/ErrorInfo.kt) descent fix. `WrappedPlaybackErrorTest` now proves termination, inner message, service/action and unchanged outer retry/report/link metadata, plus unrelated-wrapper controls. Normal extraction uses a different wrapper; production reachability of this exact chain remains unproven. [Original finding](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/documentation-alignment.md#unknown-service-display-label--2026-10-30). |
| A2 | **Implemented; focused and limited native verification (2026-11-15)** | New local diagnostic-log opt-in defaults off, including upgrades; imports preserve current local consent. New ACRA records select only stack trace and required bookkeeping. Existing files are not purged. 59 focused JVM/Robolectric tests pass, including real archive imports, activation/rollback, actual ACRA collectors/sender, report exports, timestamp and unchecked attachment recreation; later selected native reporting checks and an actual upgrade confirmed effective capture off despite the retained legacy enabled flag. Native evidence does not cover import/rollback or every ACRA field; see the dated follow-up below. See [reporting guide](error-reporting.md#local-collection-is-separate-from-attachment-consent). |
| A3 | **Guidance-only improvement; focused and limited native verification (2026-11-15)** | Deletion-pending rows and finished rows with a deletion receipt offer read-only Deletion help: the file may already be gone; check storage/provider availability, access and free internal space, then explicitly retry Delete. 24 focused JVM/Robolectric tests and Checkstyle/Ktlint pass, including real adapter/menu/dialog resources. A later controlled Pixel dialog check preserved its synthetic mission and bytes; this is not new provider/crash-recovery verification. This adds no abandonment, permission grant or deletion-state change. The provider/receipt crash gap remains: unavailable or denied access is not proof of erasure. See [current deletion behavior](download-preparation.md#removing-entries) and the [prior SIGKILL evidence](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/download-preparation.md#deletion-process-death-verification-2026-10-30). |
| A4 | **Synchronization correction covered by controlled JVM tests** | Source preparation now waits for coherent occurrence metadata and retries after synchronization, including return to an already-current occurrence. `CastPlaybackTest` covers metadata/completion ordering, recovery/default seeks and canceled-preparation retries; `CastClockAttributionTest` covers pending recovery and clock ownership. These tests use controlled engine/resolver/session behavior, not a real receiver. See [current behavior](playback.md#cast-synchronization-liveness); the limited later hardware smoke and remaining matrix are A5. |
| A5 | **Basic physical smoke passed (2026-11-15); broader matrix open** | On `11ae4c41`, normal discovery, paused handoff, play/pause, paused seek and Disconnect worked with FCast-Amazon-AFTKM; the owner confirmed moving picture/audio and TV return to its ready screen. Remaining on an agreed build: repeat cold startup, active-connection loss, speed, source replacement, queue navigation and Play on this device. Second-receiver switching, secure-v4 and Android Auto/Desktop Head Unit need those environments. See the dated follow-up below and the [historical October 28 record](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/media3-migration.md#october-28-physical-receiver-measurements). |
| A6 | **Out of scope by owner decision (2026-11-15)** | Translations are not important for this personal fork. The translation pass was canceled and its changes removed; default-English resources remain. Do not treat this as pending locale selection or resume without a new request. [Current label boundary](error-reporting.md#report-formatting-and-boundaries). |
| A7 | **Conditional maintenance: native dependency evidence** | When a release or native dependency update needs broader attribution evidence, extend the exact-version inventory for FCast native crates/FFmpeg rather than treating the old Java/Android audit as a complete native inventory. Preserve original notices and record evidence limits. [Dated inventory and limitations](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/license-audit.json). |

### A2/A3 combined verification (2026-11-15)

After independent adversarial review, the combined changes passed `make ci`:
1,476 app tests, 1,122 extractor tests (three existing skips), three build-logic
tests, 17 Python tests, static checks, Android lint, and debug and optimized
release assembly. Reporting instrumentation sources also compiled separately.
These are host/controlled tests and build results, not new physical-device
verification. No existing reports or logs were purged and no installed app was
updated during that implementation gate. A5 was still deferred at that point;
its later authorized smoke is recorded separately below. A6 is out of scope by owner decision;
A7 remains conditional because this change updates no native dependency.

### Source TODO follow-up (2026-11-15)

Implemented in `3c4f277589` (gestures), `95a33ed00a` (dates),
`38e6beb63c` (channel state) and `e845b3ad1c` (uploader URLs). This pass
did not install an app or change its version. Each correction had independent
design and adversarial review, a failing original-code regression, and focused
tests.

| Item | Correction and evidence |
| --- | --- |
| Internal-volume gestures | Accumulate signed movement instead of dropping every sub-four-pixel callback; preserve sensitivity, discard endpoint overscroll and reset DOWN/UP/CANCEL ownership. Restore a new gesture's volume/overlay independently of an old fade. 29 focused tests pass; full `make ci` passed with 1,486 app tests. |
| YouTube date fallback | Strict cached English/UK formatters accept single-digit days and `Sep`/`Sept`, reject impossible dates, and preserve ISO/relative/null behavior. 32 focused tests pass (16 failed before correction); full gate passed with 1,154 extractor passes and three existing skips. |
| Channel-tab restoration | Explicit lightweight fallback in the Bundle, full handler in existing StateSaver storage, preserved old constructor/serialization UID and specialized extraction strategy. Fresh system-playlist continuation no longer requires an initial fetch. 14 focused tests pass; original controlled Bundle was 1,202,796 bytes. Full gate passed with 1,498 app tests and 1,154 extractor passes. |
| Uploader URL fallback | Six renderer overrides now call the URL getter, not the name getter, when pagination metadata lacks a channel URL. 18 focused tests pass (ten failed before correction), including explicit overrides, null Shorts/reel URLs and missing endpoints. Final combined `make ci` gate passed with 1,498 app tests, 1,172 extractor passes and three existing skips. |

All four sequential `make ci` gates passed. The final tree adds 72 regression
tests; verification also includes three build-logic tests, 17 Python tests,
static checks, lint and debug/optimized release assembly.
Evidence is controlled JVM/Robolectric, recorded/minimal JSON and intercepted
HTTP, not physical-device gestures, process-death testing or live YouTube.
Legacy/unknown Ready handlers retain their strategy and may remain large until
recreated; StateSaver can still serialize payloads to private cache. Unsupported
age-restricted Podcasts and unrelated course-playlist behavior are not fixed.
Uploader fallback evidence concerns incomplete/legacy metadata, not ordinary
fresh Pages that already carry the channel URL.

Other comments are investigation leads, not automatic rewrite requirements.
Do not add queue broadcasts, shortest-track cue selection, broad cookie sharing,
panic/history deletion or UI migrations without evidence/decisions. A5 has a
limited later smoke result, A6 remains out of scope and A7 conditional.

### Later Pixel and receiver verification (2026-11-15)

These observations used `11ae4c41`, before the two later commits below. Six
controlled native fixtures, eight selected reporting checks and two real-engine
mode-reload tests passed on Pixel 6a in a disposable verification installation.
They covered fresh/seeded-legacy capture defaults, native channel Parcel
restoration, date/uploader fallbacks, volume accumulation/fade restart,
read-only deletion help, attachment permissions and playing/paused position
retention across repeated MAIN/POPUP/AUDIO reloads. Temporary packages were
removed; the normal app was then updated in place with a compatible signature.
Its actual upgrade kept the legacy capture flag and showed the new opt-in off,
without toggling the switch. Version remained 0.2.7.

These are not physical swipe/rotation, popup-surface, process-death,
import/rollback or comprehensive native ACRA-collection tests. No pre-existing
app was uninstalled or had its data reset; installation metadata does not prove
complete private-data equivalence.

In a separately authorized physical FCast smoke, a Rumble test video played on
FCast-Amazon-AFTKM. The owner confirmed moving picture and sound. Sender state
recorded a paused handoff near 58.785 seconds, play/pause, and a paused seek from
82.011 to 92.017 seconds. Disconnect returned the phone to local pause at 92.017
seconds; the owner confirmed the TV stopped and returned to its ready screen.
Repeated unchanged Android snapshots show retained sender state, not independent
fresh receiver telemetry. This does not close the remaining A5 matrix.

YouTube initially failed before casting. A separate bounded, cookie-free Pixel
diagnostic observed one HTTP 403 HTML response containing a traffic/security
marker and stopped. After the
owner switched VPN endpoint, the same YouTube video loaded and rendered in the
unchanged installed app, then was paused at 32.722 seconds. This supports a
route-dependent refusal, not a proven VPN/IP mechanism or recovery schedule.
See [the refusal and recovery record](error-reporting.md#youtube-api-refusal-feedback-2026-11-15).

### Subsequent completed code work (2026-11-16 UTC)

- `50b084c1`: narrow YouTube API refusal feedback, preserving successful client
  fallbacks and content-restriction priority without a fabricated CAPTCHA or
  sign-in action. Independent review, 29 focused extractor and 16 focused app
  tests, and `make ci` passed (1,499 app tests, 1,181 extractor passes plus three
  existing skips, static checks, lint and debug/optimized release assembly).
  This improves feedback; it was not installed or required for the earlier
  playback recovery.
- `86c6bc5f`: compact player-counter backings while retaining control geometry
  and touch targets. Rendering regressions cover changing text, clipping,
  font scaling and focus feedback. These tests do not themselves establish
  physical-device appearance or rotation behavior.

### Remaining work: investigation versus verification

- The terminal player-UI ownership defect was reproduced and corrected in
  `2fe2f85d`: old delayed callbacks, control animations and
  owned menus are stopped before a shared binding is reused, and partially faded
  control opacity is settled. Engine-only teardown keeps its surviving UI usable.
  Independent adversarial review and 43 focused JVM/Robolectric player-UI cases
  passed, including 22 ownership cases across API 26 and 36. Original-code
  comparisons establish the tested failures; these are not complete Main↔Popup
  playback or physical-device transition tests. The combined full `make ci`
  gate passed with 1,527 app tests, 1,181 extractor passes and three existing
  skips, static checks, lint, and debug/optimized release assembly. No device
  testing or app installation was performed for this follow-up.
- The fullscreen black-screen surface comment in `VideoPlayerUi` remains an
  investigation lead, not a confirmed surface defect. Popup speed-menu
  consistency is a separate UI improvement.
- The [YouTube n-transform compatibility gap](services.md#javascript-compatibility-regression-coverage--2026-11-13)
  still needs a verified input/output oracle. It is separate from the HTTP
  refusal above and was not established as the cause of that failure.
- [Dependency integrity enforcement](building.md#dependency-integrity-boundary)
  remains staged maintenance requiring reviewed provenance and build/host
  coverage, not evidence of existing dependency drift or compromise.
- Device verification is a separate queue: physical rotation/popup surfaces,
  process death and the remaining A5 matrix are not automatic code TODO fixes.
  Preserve the owner's translation exclusion and conditional A7 scope.

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
