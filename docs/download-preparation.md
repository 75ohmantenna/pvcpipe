# Downloads

Download preparation and execution are separate:

- `app/src/main/java/org/schabi/newpipe/download/` prepares the request.
  `DownloadPreparation` turns the dialog's selection into a plan, resolves
  destination conflicts and submits it to the download service. The dialog
  presents choices and receives folder/document picker results.
- `app/src/main/java/us/shandian/giga/` executes it. `DownloadMission` owns
  one download, `DownloadManager` tracks pending and finished missions, and
  `DownloadManagerService` runs them.

Preparation accepts progressive media URLs and video HLS rendition URLs.
Split progressive video/audio can require postprocessing; arbitrary DASH
manifests and audio-only HLS are not accepted by this preparation path.
The implementation defines format combinations, filenames, destination
handling, resume checks and postprocessing. This guide focuses on selection,
submission and persisted state.

`DownloadPreparation.Selection` captures the selected streams, sizes and
primary media format before destination preparation. The format comes from
the dialog's `StreamInfoWrapper`, including a format inferred by its HTTP
header probe when the immutable extractor stream has none. Filename, MIME
type, postprocessing and recovery use that captured format; later dialog
selection changes do not alter the pending plan.

Size probes use the same service-specific user agent as media downloads,
including BitChute's seed-host workaround. For hosts marked HEAD-unusable,
the dialog uses a GET probe requesting only the first byte and closes the
response after reading its size headers. Other HEAD failures leave the size
unknown; this dialog probe does not automatically retry them with GET.

## What "submitted" means

A download is submitted after `DownloadManagerService.startMission` accepts
the service-start request. Submission does not guarantee that execution has
started or that a checkpoint has been saved. Destination preparation happens
first: an existing file selected for overwrite may already have been changed
if dispatch then fails.

## Checkpoints

A pending mission stores a serialized checkpoint under the app's private
`files/pending_downloads` directory. It contains media URLs and the output
destination, so it must not be writable by another app. Older builds used the
external files directory; that legacy location is no longer read, and its
unfinished downloads are not migrated.

- A checkpoint replaces the previous one atomically (`Utility.writeToFile`
  uses Android's `AtomicFile`). A write that fails leaves the previous
  checkpoint readable.
- A mission that is done attempts a last checkpoint, then tells the service
  (`DownloadMission.notifyFinished`) even if that write fails.
  `DownloadManager.setFinished` writes the row in the finished-downloads
  database first, and only then drops the mission from the pending list and
  deletes its checkpoint.
- If the app stops between those steps and the final checkpoint was saved,
  the next start finds a checkpoint that says "finished" and records it then.
  If the final write failed, only an earlier checkpoint is available; it
  does not necessarily describe the completed file.

The media file, the checkpoint and the finished-downloads database are three
separate things on disk. No transaction spans them.

## Ranged response integrity and Start

An HTTP range response must identify the requested byte interval and resource
length, use identity encoding, and agree with its advertised content length.
When a strong validator is available, `ResourceIdentity` binds it to both the
requested URL and the final effective URL after redirects. Equal ETag text on
different resources does not establish interchangeable bytes
([RFC 9110, section 8.8.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.1)).
Partial responses with changed destinations or validators are rejected before writing.
A missing response validator can still be accepted when the same resource
honors the sent `If-Range` condition
([section 13.1.5](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.5)).

Normally **Start** resumes the saved mission. An initialized mission's
range-integrity failure records a clean-restart requirement that later worker
errors cannot erase. Start waits for its previous initializer and workers to
stop, discards the current resource's progress and identity, and uses one
fallback worker. Before replacing media bytes, it must save the cleared-progress
checkpoint; a failed save stops the restart and leaves the old file untouched.
Completed earlier resources are retained.

This fallback stays full-response-only until the current resource finishes or is reset.
Its GETs send neither `Range` nor `If-Range`, and an unsolicited 206 is rejected.
Each accepted full response truncates the current resource before replacement.
A transport retry or pause/resume fetches the whole current resource again,
rather than trusting a partially written replacement or stale saved offset.
This can lose partial progress on an unstable connection; ordinary direct and
ranged missions keep their existing resume behavior. Transport retries retain
the existing limit, and integrity errors do not create an automatic restart loop.

Controlled API 26 service verification on 2026-10-24 reproduced the original
rejection and repeated Start failure, then verified clean-restart completion
with exact bytes for both identical redirect content and different same-length
content reusing an ETag. It also verified live pause/resume with readable
checkpoints and pause-then-delete cancellation, not Android process-death replay.
These are controlled HTTP peers, not evidence that the failed live YouTube
destinations served equal bytes. The original smoke did not record their
requested/effective URLs or validators.

The controlled peers correctly ignored Range on HEAD
([section 14.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-14.2)).
Those fresh missions selected single-worker fallback; parallel range and
partial-state integrity are separately covered by JVM HTTP fixtures. This does
not establish correct parallel-capability detection for every host.

### Cold process restart verification, 2026-10-24

Follow-up from master `3d573fc7cb538b94273b9d3cc9d95912b7603a56`
(PR #151) used one newly created disposable API 26 x86_64 emulator.
`tools/pr151_cold_restart.py` controls a local HTTP peer and invokes
`DownloadColdRestartIntegrationTest` in separate Android instrumentation
processes. It exercised 25 fresh-process phases and 20 confirmed SIGKILL
terminations. No production change was needed for the exercised integrity paths.

| Scenario | Observed cold-restoration behavior | Integrity result |
| --- | --- | --- |
| Ordinary validated partial, including two repeated partial restarts | Restored initialized block checkpoints; requested saved incomplete ranges with the saved strong `If-Range` validator. | Exact 8 MiB fixture. |
| Start after identity rejection, killed during replacement | Persisted full-only mode survived replacement bytes and cold loading; subsequent GETs omitted both range headers and fetched the whole resource. | Exact different, same-length 8 MiB replacement; no old/new mixture. |
| Cleared checkpoint before replacement response | Killed with response headers held at the server, cleared progress and identity on disk, and zero replacement bytes sent. Cold Start continued full-only. | Old output unchanged at the boundary; exact replacement after completion. |
| Unsolicited 206 after cold-loading full-only mode | Rejected without changing the file or marking the mission finished; a later explicit Start accepted a full 200 response. | Unchanged pre-rejection digest; exact completed replacement. |
| Completed earlier resource | Killed with current resource 1 cleared and the earlier 8 MiB resource's offset retained; restored replacement fetched only the current resource. | Exact 16 MiB concatenation of original first resource and changed second resource. |

The server gates transfer budgets or response headers; it does not choose crash
times using sleeps. Each nonterminal instrumentation phase remains alive until
the host kills its reported PID. The host checks disappearance before starting
another process. Production `DownloadManagerService` creates the real manager
and loads its private checkpoints; new missions use the service's submission API,
and resumed missions use the same binder manager `resumeMission` path as Start.
No checkpoint is edited, no restored mission is injected, and no pause,
force-stop, data-clear or orderly shutdown precedes a crash. Running-mission
checkpoints are observed under the production writer lock; manager-owned initial
checkpoint writes are serialized with the driver by main-thread dispatch.
The HTTP peer deliberately refuses HEAD with 405, exercising GET probes rather
than incorrectly honoring Range on HEAD. A `bytes=0-` probe without `If-Range`
receives a valid one-byte 206;
the final-ten-byte capability probe receives its requested interval. The host
asserts their expected counts and requires zero probes after initialized cold
restoration. Its hostile changed-validator 206 is intentional.

The Android phase checks every completed byte. The host independently pulls and
hashes output, compares request ranges with the loaded checkpoint, rejects
overlapping media requests, and requires one full-only request per phase. Wire
assertions run again after confirmed process exit and joined handlers. Requests
are attributed to the phase captured at connection acceptance; held responses
record a planned status separately from headers actually sent.
Full-mode resumes with saved partial progress require a newly persisted count
below the loaded count, using a smaller server budget, rather than accepting the
checkpoint just loaded. Earlier-resource re-fetches are explicitly forbidden.
Ordinary partial-resume gates also require advancement of durable block offsets
or the fallback resume offset, not just the progress counter.
Rejected and incomplete transfers remain pending. Replaying uncheckpointed
in-flight block work is distinguished from corruption by exact completed bytes.
Full-only mode intentionally discards partial replacement progress on restart.

Possible pre-existing progress-accounting defect: the controlled run observed
saved `done` including in-flight work absent from durable block offsets; replay
can count those bytes again. This is not an intended progress-display contract.
An above-100% UI percentage is an inference from the counter and adapter code,
not an observed UI result. No progress-accounting correction is included here.

There are no manager, storage, checkpoint, worker or HTTP-client mocks in this
integration. The controlled HTTP origin replaces a real media host; instrumentation
drives service/binder APIs, not DownloadActivity UI gestures. These results do not
cover live hosts, SAF providers, other Android versions, an interrupted
AtomicFile commit, power loss, or filesystem durability. API 37 and physical
receiver testing remain blocked/outside scope; no physical device was tested.
The earlier JVM tests still use Android/manager/storage doubles as described by
their fixtures.

The fixture persistently sets maximum transport retries to zero and the
cross-network preference to false on this disposable AVD. Transport-retry paths
are therefore not covered by this integration; those preferences are left set.

Build and explicitly install both APKs on a disposable emulator, then run:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb -s '<owned-emulator>' install -r app/build/outputs/apk/debug/app-debug.apk
adb -s '<owned-emulator>' install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/pr151_cold_restart.py --serial '<owned-emulator>' \
  --disposable-avd-name '<exact-owned-AVD-name>' --allow-disposable
```

The host requires an initially stopped target process and no pending checkpoints,
and checks the AVD name, emulator property and API level before proceeding.
It records Git/source provenance, APK digests matched against installed APKs,
and installed package version/update metadata. It leaves generated mission files
and finished history in that disposable AVD for inspection; it removes only its
ADB reverse mapping and stops its own HTTP server. JSONL wire/process events,
phase checkpoints, raw instrumentation output, pulled bytes and `summary.json`
are retained under the printed `/tmp/pvcpipe-cold-*` directory. Do not use the
uninstalling Gradle connected-test task on a user's installation.

All five scenarios passed in the final API 26 run. The 52 affected JVM checks
also passed, followed by serial `make ci` (static checks, app/extractor tests,
lint and debug/release assembly, reusing unchanged outputs where reported).
Instrumentation remains separate from that repository gate.

Complete-PR review identified a stale-loaded-checkpoint crash gate and a wire
audit that stopped before process exit. The corrected fixture requires fresh
replacement checkpoints and audits joined handlers. Subsequent fixture runs
also exposed adb argument quoting and a lookup-triggered auto-resume race:
`getIterator()` enables connectivity scheduling, so cold pending lookup now uses
the persisted timestamp before explicit Start. Failed-run missions were
completed through the real service against their retained fixtures, not cleared
or edited. These were harness corrections, not production integrity defects.

### Cold-replay progress accounting correction, 2026-10-24

Follow-up from master `5f196510e` (PR #152) confirmed the counter defect above
using the real service/checkpoint loader on the same disposable API 26 emulator.
This time the actual pending Downloads row was captured: **108.35%**, with
`8.00 MB  8.67 MB/00:00  1.79 MB/s` in its accessible size/speed/ETA text.
That is UI evidence, unlike PR #152's inferred display symptom. After tightening
the initial server gate to cap each worker independently, a repeated pre-fix run
displayed **109.33%**, with `8.00 MB  8.75 MB/00:00  1.26 MB/s`.
The deterministic gated reproduction recorded this 8,388,608-byte sequence:

| Boundary | `done` | Saved resumable bytes | Meaning |
| --- | ---: | ---: | --- |
| Live before SIGKILL | 786,432 | 0 | Two in-flight blocks, 393,216 bytes each. |
| Actual saved checkpoint | 786,432 | 0 | All 16 saved block offsets were zero; fallback offset was zero. |
| New process, before Start/body bytes | 786,432 | 0 | The real loader retained credit for work it would replay. |
| Replay held 4,096 bytes before completion | 9,170,944 | — | 8,384,512 newly accepted bytes added to the stale 786,432. |

The server logged all sixteen block requests from `bytes=0-524287` through
`bytes=7864320-8388607`; the final response was held after 520,192 bytes.
The completed output still matched every fixture byte. Evidence, including raw
checkpoint files, process IDs, wire events, output and UI captures:
`/tmp/pvcpipe-accounting-gated-before` (first run:
`/tmp/pvcpipe-accounting-before-fix`).

The accounting contract follows the consumers in `MissionAdapter`: percentage,
completed size, speed samples and ETA derive from `done`. It is logical progress,
not network traffic. Network bytes can exceed file length across probes, retries
and replay. Saved resumable progress is the completed-earlier-resource baseline
plus saved block positions (including partial blocks), or the fallback offset;
it can lag live writes. Checkpoints here do not establish power-loss durability.

`DownloadManager` now calls `DownloadMission.restoreProgress` immediately after
loading a checkpoint, before publishing or scheduling it. The counter is rebuilt
from those resume positions, excluding reserved leading space and counting a
completed short final block at its actual length. Uninitialized resources retain
only completed earlier resources. Finished downloads and post-processing progress
are not rewritten. Raw checkpoint inspection still reports its original counter.
The final fixed gated run saved 736,930 with no resumable blocks, cold-loaded zero,
then counted 8,384,512 at the held replay boundary. Its actual Downloads row showed
**99.95%** and `8.00 MB  8.00 MB/00:01  1.49 MB/s`.
Counter and raw-checkpoint assertions, screenshot/XML and exact output:
`/tmp/pvcpipe-accounting-gated-after`. Both capped runs produced the same fixture
SHA-256, `3b36e67585c5064882afa167dd905c30f474f15eaf828b340b2b365ca3eddcc6`.

This correction deliberately permits progress to decrease when uncheckpointed
work must be replayed. It changes neither live worker accounting nor warm
pause/resume and retry paths. No percentage clamp, new persistence format,
range/validator/URL/encoding relaxation, or full-only recovery change is involved.
PR #151's checkpoint-before-replacement rule remains intact.

The focused `accounting` scenario uses two ranged workers and a total initial
768 KiB budget, capped at 384 KiB per response so neither block can complete
before death. The second process first publishes its loaded/pre-body state, then
receives exactly the saved replay plan minus 4 KiB. Optional UI capture waits for
the unique fixture row's current percentage, not an arbitrary sleep. The third
process completes and checks the exact fixture. To run only this reproduction
after building/installing both APKs as above:

```sh
python3 tools/pr151_cold_restart.py --serial '<owned-emulator>' \
  --disposable-avd-name '<exact-owned-AVD-name>' --allow-disposable \
  --scenario accounting --ui-hold-seconds 20
```

The observer records both buggy and corrected outcomes in `accounting.json`;
its generic PASS means the controlled experiment/integrity checks completed,
not that the logical counter matched. Inspect `counterMatchesReplay` and
`loadedOvercount` separately. The six focused desired-behavior JVM regressions
and the 52 existing affected checks passed. A selected fixed API 26 run covered
accounting, ordinary aligned recovery, full-only replacement and earlier-resource
preservation in 16 process phases, not a repeat of the historical 25-phase run.
All four outputs matched their fixtures (three 8 MiB, one 16 MiB).
Evidence: `/tmp/pvcpipe-accounting-after-fix`. That 16-phase run predates the
per-response cap; the two `accounting-gated-*` runs exercised the final cap.
These runs came from the uncommitted tree, with source hashes and APK identity
recorded separately. The final fixed gated run's production, instrumentation and
host-source hashes match the committed correction. Final serial `make ci` passed (static
checks, full app/extractor JVM suites, lint and debug/release assembly).

Instrumentation drives real service/binder calls and opens the real Downloads
activity for capture; it does not automate user gestures. Transport retries remain
disabled in the controlled integration; retry coverage is JVM-only. No user
installation/data was cleared or uninstalled. API 37, physical receivers/devices,
SAF/live sources and interrupted AtomicFile commits remain outside this evidence.

### Resource-transition checkpoint correction, 2026-10-24

The downloader triage's highest-ranked finding was reproduced on unchanged
master after PR #153 (`9caa8ab03`). A real A worker queued a progress checkpoint;
the writer was held before its production lock while A completed and reached
the next initializer. That initializer had not yet cleared A's resource state.
The queued writer then serialized B's index/output base with A's completed
blocks, length and identity. Actual cold Start saw no remaining blocks and
finished without requesting B. This was not a counter-only defect.

| Transition checkpoint | `current` | `offsets` | `length` | `blocks` | Identity | Cold `done` |
| --- | ---: | --- | ---: | --- | --- | ---: |
| Unchanged production, queued writer after advancement | 1 | `[0,524425]` | 524425 | `[-1,-1]` | A | 1048850 |
| Corrected staged JVM sequence, before B initialization | 1 | `[0,524425]` | 0 | null | null | 524425 |
| Corrected API 26 service checkpoint and cold load | 1 | `[0,8388608]` | 0 | null | null | 8388608 |

The unchanged reproduction left only A's 524,425 bytes, rather than the
528,522-byte fixture. Releasing its original initializer was the positive
control: B was requested and the exact concatenation completed. Evidence:
`/tmp/pvcpipe-downloader-triage-20261024/transition/evidence/run-03`.

`DownloadMission.notifyFinished` now takes `LOCK` while advancing `current`,
deriving the next output offset from the completed resource's length, and
resetting the next resource's length, blocks/acquisition state, fallback offset,
identity, unverified/full-only/range-restart flags, errors, recovery validation
condition and logical progress. Earlier resource offsets and bytes are retained.
The delayed reset in `DownloadInitializer` is removed. The clean next-resource
checkpoint is written before scheduling its initializer. At the final resource,
the last length, offsets and completed state remain available to completion and
postprocessing rather than being reset or indexing past the offsets array.

The snapshot boundary is the actual `writeThisToFile` lock: it holds `LOCK`
through `Utility.writeToFile`, `ObjectOutputStream` serialization and AtomicFile
commit. Thus a queued writer observes the previous phase or the clean next phase,
not their mixture. Locking only `current++` or only the mission monitor would
not provide that guarantee. When both locks are needed the order remains mission
monitor then `LOCK`. Block workers release `LOCK` before progress/completion
callbacks; initialization releases it before mission-monitor callbacks. Writers
do not acquire the mission monitor. No scheduling, worker join or external
callback is added inside the transition's critical region. Restart still joins
previous workers outside the mission monitor.

Seven desired-behavior JVM regressions use actual initializers/workers, loopback
HTTP, file writes and Java serialization at the production Utility boundary.
They stage queued writes before and after transition, cover strong and absent
identity plus fallback, require a B request and exact 724,428-byte A+B output,
retain the uninterrupted positive control, preserve a real worker-produced B
partial checkpoint and its conditional resume range, and check final-resource
and pending-postprocessing boundaries. Android logging/handler/storage adapters
and a Utility-boundary serialization adapter are explicit: these JVM tests do
not exercise Android AtomicFile or run the media postprocessor. All 63 focused
downloader JVM checks passed.
The strong-identity desired-behavior regression also ran against preserved base
production and failed at the clean-B length assertion (`0` expected, `524425`
observed). The schedule reserves the queued writer before letting A finish,
rather than relying on prompt thread execution.

The targeted disposable API 26 `transition` scenario holds B's own initializer
query after A's aggregate sizing query. It checks exact A bytes and the clean
checkpoint, SIGKILLs the reported process, verifies disappearance, and asserts
the real service/manager's cold-loaded state before explicit Start. Cold Start
requests only B: its initialization probes and all sixteen ranged blocks.
The independent host byte check passed for the exact 16,777,216-byte A+B output,
SHA-256 `47525533208735cd48fd9c9f918aabffc8119d45500e68d578d2b1b2bbc863d3`.
Evidence: `/tmp/pvcpipe-transition-api26` (two process phases, source/APK hashes,
raw checkpoint, requests and pulled bytes). Run only this bounded scenario with:

```sh
python3 tools/pr151_cold_restart.py --serial '<owned-emulator>' \
  --disposable-avd-name '<exact-owned-AVD-name>' --allow-disposable \
  --scenario transition
```

This exercises production Android AtomicFile and cold service restoration, not
the exact delayed-writer schedule (covered by JVM gates), an interrupted commit,
power loss or filesystem durability. It used signature-checked `install -r`
and direct targeted instrumentation; no installation/data was cleared or
uninstalled. No full process-kill campaign, physical receiver/device, SAF or
live-host run is claimed.
The old initializer also saved a clean checkpoint before this HTTP gate:
this Android scenario checks integration, not a before/after discriminator.
The delayed-writer regression is established by the staged JVM sequence.

No historical-checkpoint repair or persistence-format change is included.
An identity bound to a different URL is evidence of an invalid association, but
absent identity and repeated URLs offer no unambiguous marker distinguishing
this mixture from a valid completed B checkpoint awaiting its finish callback.
Resetting all completed/current>0 checkpoints would destroy valid resume state.
This correction prevents new mixed transition snapshots; it does not
universally repair checkpoints already written by older builds. PR #151's
full-response-only/checkpoint-before-replacement recovery, PR #153's restored
progress reconciliation and URL/validator/range/encoding checks are unchanged.
The separately confirmed warm retry/block-release defect is not included.

### Warm retry ownership correction, 2026-10-24

The separately triaged warm retry defect was reproduced on unchanged master
after PR #154 (`864905dc8`). Two ranged workers used `maxRetry=1`; block 0
received 187 verified bytes before EOF, and its reconnect returned HTTP 503
after the other worker exited. Retry exhaustion left `retry=true`. The old
per-attempt cleanup therefore skipped publication/release even though its
owner terminated. Actual warm Start retained the logical counter but requested
the block again from zero.

| State | Block positions | Block 0 acquired | Logical `done` | Exact output bytes |
| --- | --- | --- | ---: | ---: |
| Unchanged, after terminal worker exit | `[0,-1,-1]` | true | 524612 | Not complete |
| Unchanged, after actual warm Start completes | `[-1,-1,-1]` | false | 1048900 | 1048713 |
| Corrected, after terminal worker exit | `[187,-1,-1]` | false | 524612 | Not complete |
| Corrected, after actual warm Start completes | `[-1,-1,-1]` | false | 1048713 | 1048713 |

`DownloadRunnable` now has terminal cleanup independent of retry intent.
A continuing retry retains its local block and acquisition; an ordinary
release clears that local ownership, so terminal cleanup cannot overwrite its
completed marker. A terminating owner publishes its verified position and
releases its remaining acquisition before exit, including pause and reconnect
failure. `DownloadMission.releaseBlock` accepts the acquired block, checks the
identity of the acquisition array, and consumes its token on release. Reset or
replacement worker state invalidates old tokens; a duplicate release cannot
release a newly acquired owner or overwrite completed/replacement state.
Reset clears the block/acquisition pair under the same existing `LOCK` used by
release. Source inspection identified a UI recovery path that can request reset
before Start joins an old terminating worker; the paired invalidation prevents
half-invalidated release state. That particular race was not reproduced.
No new allocation per acquisition, serialized field, lock, or callback under
`LOCK` is added. Restart still joins old workers outside the mission monitor;
the mission-monitor-then-`LOCK` ordering is unchanged.

The corrected controlled JVM warm Start resumes with
`Range: bytes=187-524287`, rather than replaying
`Range: bytes=0-524287`. The successful body-retry control also retains its
prefix and completes with exact bytes and logical progress.
Evidence: `/tmp/pvcpipe-warm-fix` (unchanged baseline and corrected smoke).
These are real loopback HTTP/file/worker results with Android adapters, not
new visual UI evidence. The baseline excess was exactly 187 logical bytes;
final file bytes were already correct before this correction.

Seven durable additions in `DownloadRangeIntegrityTest` cover terminal reconnect
failure followed by actual warm Start, successful retry ownership, pause during
gated reconnect with Start held behind the old owner, duplicate release after
reacquisition/completion, reset/replacement invalidation, checkpoint timing,
and pause after a 416 discards a full body whose close failed.
The desired terminal regression fails against preserved unchanged production
(`187` expected, `0` observed) and passes corrected production. All 72 focused
downloader JVM checks passed, including the existing transition, cold-progress,
lifecycle, manager-checkpoint and atomic-checkpoint regressions.
The checkpoint regression observes the failing owner's actual save before
cleanup: block 0 is still `0` while `done=524612`. After exit, an explicit later
save contains block 0 at `187` with the same logical counter. Its real Java
serialization runs inside production `LOCK`; the Android AtomicFile boundary is
adapted, so it is not a filesystem/power-loss durability test.

A later adversarial smoke found an additional terminal cursor boundary: after
a full body's close throws, reconnect can return 416 and discard that prefix.
If pause ends the loop there, the old absolute cursor could incorrectly mark
the discarded block complete with logical progress zero. The 416 discard now
rewinds that cursor with `block.done` before clearing the retained position.
A seventh durable regression uses controlled connection/body-close faults,
real workers and file writes, then actual warm Start; unlike the original warm
HTTP regressions, this focused fault adapter does not exercise a TCP peer.
It fails the earlier correction (`0` expected, completed marker `-1` observed)
and passes with the cursor correction, requiring the discarded bytes to transfer
and count on warm Start.

Publication at owner exit is an in-memory ownership guarantee, not a new
checkpoint barrier. `notifyError`/pause can write a checkpoint before terminal
cleanup; that earlier checkpoint need not contain the released position.
Cold restoration still reconciles progress to saved positions. Only a
successful later checkpoint taken after publication can establish that the
retained position was serialized. This change does not indiscriminately run
cold reconciliation during active or warm work, clamp UI progress, or alter
PRs #151–154's full-response recovery, durable-cleared-checkpoint rule, cold
reconciliation, atomic resource transition, or representation validation.

The bounded `DownloadWarmRetryIntegrationTest` and
`tools/download_warm_retry.py` exercise one real-service/manager warm Start on
an explicitly acknowledged disposable API 26 AVD. Build both APKs separately;
the launcher checks AVD/API identity, existing pending missions and processes,
package IDs and both installed/candidate signing certificates before any
`install -r`. It never uninstalls, clears app data, kills an Android process, or
runs `connectedDebugAndroidTest`.

While reconnect is gated, the test uses the existing queue-toggle API to remove
this mission from automatic scheduling: HTTP 5xx normally stays queued and the
service's error handler can immediately resume it. This leaves its worker
running and its progress/acquisition untouched, so the later explicit manager
Start is observable. The toggle schedules a normal checkpoint; snapshots are
reported as observations, not a terminal-publication durability claim.

Run only this targeted scenario:

```sh
./gradlew --no-daemon --no-continue :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/download_warm_retry.py --serial '<owned-emulator>' \
  --disposable-avd-name '<exact-owned-AVD-name>' --allow-disposable --install \
  --artifacts '/tmp/<fresh-warm-evidence-directory>'
```

This disposable fixture retains its output and finished-history row and changes
the fixture app's preferences to `maxRetry=1` and cross-network downloads off.
It does not restore those preferences or remove test data automatically.

The first validated targeted run passed on disposable `pvcpipe_warm26_20261024`
(`emulator-5556`): `OK (1 test)`. Host observations found PID `3087` alive
and unchanged before actual manager Start, after Start before resumed response
bytes, and during final verification. Both original workers had exited before
Start. The sole warm request was conditional
`bytes=187-524287` with `If-Range: "warm-retry-stable"`, transferring
524,101 worker-body bytes. Final logical progress, mission length and output
length were all 1,048,713. The independent host SHA-256 matched the fixture:
`03243f694e27a87a7f4b1fdab2e60ad09dc9c803f8348829b2996f4bd19d2bfc`.

In that Android run, the pre-503 checkpoint observation had positions
`[0,-1,0]`; the post-owner-exit/pre-Start observation had `[187,-1,-1]`.
The latter does not identify which asynchronous writer saved it or establish
that every error/pause checkpoint includes terminal publication. The staged
JVM regression separately proves the earlier error save can still hold `0`.
Evidence: `/tmp/pvcpipe-warm-fix/api26-final` (wire, PID observations,
instrumentation, raw output, source/APK identities). No new UI result, process
kill/cold-restoration campaign, API 37 or physical-device/receiver run is claimed.

Preparation failures are retained separately: an initial signer-output parser
mismatch stopped before installation/test, then the test observer encountered a
published worker array before its entries were filled. The observer now waits
for both entries. The failed fixture on the earlier disposable AVD was left
untouched; the final run used a newly created empty API 26 AVD, seeded with
verified debug app/test APKs. No existing installation/data was cleared or
uninstalled; that evidence predates the later 416 cursor correction.

The initial serial `make ci` passed: Checkstyle/Ktlint/dependency ordering, the full
configured app/extractor JVM gates, debug lint, and debug/release assembly.
After the 416 cursor correction, all 72 focused checks and both instrumentation
APK builds passed again with Checkstyle. This remains bounded
controlled-protocol/emulator evidence, not real-host or hardware scheduling.

After the cursor correction, the same bounded API 26 scenario passed again on
the same disposable AVD, without deleting prior fixtures or pending data:
`/tmp/pvcpipe-warm-fix/api26-cursor-final`, `OK (1 test)`. PID `3303` was alive
and unchanged across the actual manager Start and final byte verification.
The sole warm range and its conditional validator, worker-body byte count,
final logical progress/output length and fixture hash were unchanged.
All recorded production/instrumentation/host source hashes match this corrected
build; the earlier PID `3087` run is not used to verify the later cursor change.

This repeat also exposed the checkpoint boundary on real Android: after both
owners exited, live blocks were `[187,-1,-1]` with no acquisitions, but the
observed saved checkpoint still had `[0,-1,-1]`, `done=524612`, error 503.
Warm Start used the live `187`, not that checkpoint. The earlier pre-503 saved
snapshot was `[0,0,0]` with `done=196795`. These observations independently
confirm that exit publication is not an automatic terminal checkpoint barrier.
No checkpoint rewrite, app restart, cold normalization or process kill was used
to make this warm run pass.

Final serial `make ci` was repeated after the cursor correction and passed all
five configured gates again. Reports contain 1,037 app tests with no failures
or skips and 403 extractor tests with no failures and one skip; Gradle reused
up-to-date tasks where applicable. The 72 focused downloader checks were also
run separately on the corrected source, not inferred from earlier results.

## File names

A download name is one path component. Titles come from uploaders and the
filename settings can come from an import, so the name is checked in three
places:

- `FilenameUtils.createFilename` checks the stored settings each time it reads
  them. A custom charset that is not a valid pattern falls back to the default
  charset; a valid one is kept. A replacement containing `/`, `\` or NUL falls
  back to `_`. Path separators are replaced even when a custom pattern keeps
  them. The settings screen refuses such a replacement too.
- `DownloadPreparation.save` fails with `FILE_CREATION` before any picker or
  file change when the constructed filename (base name plus suffix) is not a
  plain file name. A missing or blank base name fails earlier as
  `INVALID_SELECTION`.
- `StoredDirectoryHelper` and the path-based `StoredFileHelper` constructor
  refuse empty names, `.`, `..` and names containing a separator or NUL. The
  constructor checks before `Files.deleteIfExists`, so a refused name changes
  nothing outside the folder. Recovery (`DownloadManager.tryRecover`) creates
  files through the same check. Names that only contain dots, such as
  `..video.mp4`, are allowed.

## Removing entries

"Delete" removes an entry and requests deletion of its file. The manager
does not check the file-deletion result, so a removed entry does not prove
the file was erased. "Delete entry" keeps the file and is offered only for
finished downloads. An unfinished mission can still own workers or a
checkpoint; removing only its entry would not stop those workers or prevent
checkpoint replay at the next start. `DownloadManager.deleteMission` refuses
entry-only removal for unfinished missions, as well as the menu hiding it.

## HLS downloads

An HLS media playlist is downloaded segment by segment, then remuxed with
the bundled ffmpeg by `PvcFromHlsRemuxer`. `PvcHlsPreProcessor` rejects master
playlists: the selected download must identify a rendition. The detail screen
hides the download action for livestreams, including BitChute and Kick live
broadcasts.

- `PvcHlsPreProcessor` reads the playlist and replaces the mission's one
  address with the addresses of the segments. It fetches playlists and
  encryption keys as bytes, because a key is binary.
- Each conversion works in a directory of its own under `ffmpeg_temp` and
  attempts to remove it when it ends, also after a failure. Several HLS
  downloads can be converted at the same time. A conversion that starts while
  no other is running first attempts to remove leftovers in `ffmpeg_temp`:
  files left by an older build's shared directory or by an interrupted
  process. Cleanup is best effort (`PvcFromHlsRemuxer.deleteQuietly`).
- A segment that the server refuses fails the mission with the HTTP error,
  and the mission keeps its segments. `DownloadMissionRecover` is not used
  for HLS: it can only find a whole progressive file again. A retry asks for
  the same segment addresses, so it does not help once they have expired.
  Reading the playlist again is not implemented.

WebM Opus-to-Ogg conversion rejects block-laced input. `WebMReader` does not
split laced blocks into packets, so treating a whole block as one packet
would produce an invalid output file.

## Sharing or opening a finished file

Sharing a download that was saved by path, or opening it in another app,
grants read access to that one file.

- `MissionFileProvider` hands out a `content://` address with a random token
  in place of the path. It never exposes the folder the file is in.
- The file stays where it is, on internal or removable storage. No copy is
  made.
- The token-to-path mapping is stored in private preferences and survives
  PVCPipe process death. The recipient still needs Android's read URI grant;
  saving the mapping does not make that grant permanent or extend its
  Android-managed lifetime.
- If the path no longer points to a regular file, the address does not open.
- A download saved through the Storage Access Framework is shared by its
  original document address and does not go through this provider.
- Preview thumbnails for the share sheet come from a separate `FileProvider`
  that covers only the `share_preview` cache folder.

## Tests

`make ci` runs the unit tests: `DownloadPreparationTest`,
`DownloadMissionLifecycleTest`, `DownloadManagerCheckpointTest` and
`UtilityAtomicCheckpointTest` cover preparation, checkpoints and recovery.
`DownloadMissionProgressTest` checks restoration of logical progress, valid
partial positions, earlier resources/reserved space, short final blocks and
unchanged post-processing progress.
`DownloadRangeIntegrityTest` checks real local HTTP responses and file bytes,
including redirect identity, range bounds, clean restarts after stopped writers,
checkpoint-save failure, late errors, replacement offsets, and serialized
full-response-only recovery.
`DownloadManagerDeleteEntryTest` covers removing entries, and
`FilenameUtilsTest` and `StoredFileNameContainmentTest` cover file names.

`DownloadPreparationIntegrationTest` and `MissionFileSharingTest` are
instrumentation tests and need a device. Before running any instrumentation
on a phone that has the app installed, read
[local-playlist-mutations.md](local-playlist-mutations.md).

`DownloadColdRestartIntegrationTest` and `tools/pr151_cold_restart.py` separately
exercise the controlled SIGKILL/cold-restoration boundaries above. Compilation
and execution of this instrumentation are not part of `make ci`. These tests do
not cover every host's media URLs, every Android process-death boundary or live
HLS expiration.

[pvcpipe-device-smoke.md](pvcpipe-device-smoke.md) records dated phone checks
of download pause/resume behavior and media checksums.
