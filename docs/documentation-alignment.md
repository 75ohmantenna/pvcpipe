# Documentation alignment — 2026-10-23

## Inventory and coverage

This is a dated alignment record, not certification of every statement or every
source comment. The pass inventoried all 17 tracked standalone Markdown files
before adding this record, then checked high-value claims against implementation,
callers, configuration and the scope of relevant tests.

| Material | Treatment |
| --- | --- |
| Root `README.md`, `AGENTS.md`, `GLOSSARY.md` | Maintained project overview, agent policy and terminology |
| `docs/README.md`, `building.md`, `services.md`, `playback.md`, `library-data.md`, `download-preparation.md` | Maintained index and current guides; verify claims against code rather than treating guides as evidence |
| `docs/dependency-audit.md` | Dated decisions and validation, including later dated follow-ups; classified as a record in the index |
| `docs/pvcpipe-device-smoke.md`, `odysee-observations.md`, `local-playlist-mutations.md`, `service-protocol-observations.md` | Historical observations; earlier hardware, emulator and controlled-peer results do not verify newer heads |
| `pvcpipe-extractor/README.md` | Maintained fork build/capability instructions with inherited-source provenance |
| `app/src/test/resources/settings/README.md` | Synthetic fixture reference; the standalone database-named file is a text placeholder, not SQLite-openability evidence |
| `pvcpipe-extractor/timeago-parser/raw/README.md` | Generator input/entry-point reference; generated timeago Java is not hand-maintained prose |
| License files, copyright headers, incorporated third-party source, json2java4nanoJson notices, Protobuf/Wire output | Preserve legal/provenance content; no stylistic normalization of generated or third-party comments |

The only tracked agent-instruction file in the inventory is root `AGENTS.md`.
Unrelated uncommitted workspace changes were excluded.

Build checks used `Makefile`, `app/build.gradle.kts`, both extractor build scripts,
`settings.gradle.kts`, the version catalogs, wrapper properties, daemon criteria
and `PvcVersion`. The documented SDK 37/minimum 26, Java compilation toolchain 21,
daemon/Checkstyle JDK 25, application IDs, composite substitution and five-stage
`make ci` command agree with those declarations. `./gradlew --version` reported
Gradle 9.8.2 and Java 25 daemon criteria; the launcher JVM can differ. The Gradle
embedded Kotlin version is not the project's Kotlin plugin version.

Comment inspection concentrated on playback ownership and lifecycle, queue
transitions, library/download operations, service extraction, error classification
and parcel transport. Valid workarounds, TODOs, upstream attribution and dated
observations were retained. This was not line-by-line verification of all Java,
Kotlin, resources, external sites or historical test artifacts.

## Important unresolved discrepancies

- At this alignment pass, playback audit findings **4–5 were unresolved**:
  recovery could pair the selected occurrence with the previous engine clock,
  and initial cast preparation could survive a failed subsequent selection.
  A later 2026-10-23 correction revalidated and fixed finding **4** with isolated
  baseline/fixed controlled JVM recovery-and-seek evidence; see
  [the correction and its boundaries](playback.md#recovery-during-pending-navigation--corrected-2026-10-23).
  A subsequent 2026-10-23 correction revalidated and fixed finding **5**, including
  failed metadata delivery and late initial-cast completion on same-URL distinct
  occurrences; see [the correction and mocked boundaries](playback.md#initial-cast-preparation-ownership--corrected-2026-10-23).
  A further 2026-10-23 correction addresses the separate equal-index history
  snapshot race; see [snapshot acceptance and write lifetime](playback.md#equal-index-history-snapshot-ownership--corrected-2026-10-23).
  Metadata-before-occurrence-synchronization cast liveness was left separate
  through PR #140. A later 2026-10-23 correction revalidated the unchanged
  post-#140 baseline and added ownership-gated synchronization retry; see
  [cast synchronization liveness and evidence boundaries](playback.md#cast-synchronization-liveness--corrected-2026-10-23).
  PR #141's remaining active-cast clock-attribution concern was subsequently
  reproduced on unchanged post-#141 master: reset zero replaced B's pending
  recovery and was passed to the history adapter. The focused correction adds
  occurrence/clock provenance at acceptance and guards disconnect's position
  override; see [active-cast checkpoints and evidence limits](playback.md#active-cast-checkpoint-attribution--corrected-2026-10-23).
  Fresh review additionally reproduced consumed recovery being lost before
  receiver time; coherent synchronized starts and local handoffs now retain
  that owned resume intent without authorizing reset or outstanding-preview clocks.
  A second fresh review reproduced stale local progress suppressing a newer
  handoff checkpoint; local handoff now carries the already-owned engine clock
  regardless of that stored progress. Next/close regressions exercise the correction.
  The third review's optional same-URL continuation case was also reproduced:
  quality reload now carries only an owned position, preserving the forced
  engine recovery seek instead of promoting an unowned preview.
  Queue accounting, playlist cancellation and paused-cast-startup corrections
  alone did not establish either resolution.
- `ErrorActivity.ERROR_GITHUB_ISSUE_URL` still points to
  `https://github.com/pvcpipe/NewPipeExtractor/issues`, not this project's permitted
  submission destination. The email target is also inherited configuration
  (`crashreport@gmx.com`). Changing report destinations is executable behavior and
  requires a focused product/privacy decision; no report was submitted there.
- `privacy_policy_url` points to `https://newpipe.net/legal/privacy/`, while the
  report dialog calls it PVCPipe's policy. Resolve the policy content and URL
  before adopting the proposed privacy-dialog wording. Neither a wording update
  nor this audit establishes legal compliance.
- Live service availability, receiver decoding, secure-v4 interoperability and
  device-specific behavior cannot be established by mocked JVM tests or assembling
  APKs. Earlier device/receiver observations retain their original date and build.

## User-facing text: separate follow-up

No resource text, key or translation was changed by the original alignment pass.
The table records its proposed corrections; the backup-restore and
[cache/history/download follow-ups](#cache-history-and-download-messages--corrected-2026-10-23)
below identify subsequent fixes. Resource keys refer to
`app/src/main/res/values/strings.xml`.

| Key | Call sites / factual issue | Suggested wording |
| --- | --- | --- |
| `metadata_cache_wipe_summary` | `HistorySettingsFragment` → `InfoCache.clearCache`; clears extracted in-memory metadata, not all cached webpage/WebView data | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Clear cached stream, channel, playlist, and other extracted metadata” |
| `delete_view_history_alert` | `HistorySettingsFragment.openDeleteWatchHistoryDialog`, also opened by `StatisticsPlaylistFragment`; deletes playback positions as well as stream history | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Delete all watch history and saved playback positions?” |
| `too_many_requests` | `ErrorInfo.getMessage` for a non-solvable `ReCaptchaException`; classification does not prove a network-wide restriction | “Too many requests to %1$s. Try again later.” |
| `parsing_error` | `ErrorInfo.getMessage` for remaining `ExtractionException`s; extraction failure need not be website parsing | “Could not extract the requested information” |
| `error_report_open_github_notice` | `activity_error.xml`, shown by `ErrorActivity` for non-crash failures too | Replace “this crash” with “this error” |
| `start_accept_privacy_policy` | `ErrorActivity.openPrivacyPolicyDialog`; acceptance opens a composer or issue list, not submission; code does not establish GDPR compliance | “Please read and accept PVCPipe’s privacy policy before continuing to report this error.” Legal/policy review required |
| `recaptcha_request_toast` | `ErrorInfo.getMessage` and `RouterActivity`; router opens the challenge directly, error panels offer Solve | “A reCAPTCHA challenge is required to continue” |
| `download_has_started` | `DownloadDialog.DownloadPresentation.submitted`; service dispatch is not confirmed mission execution | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Download request submitted” |
| `cancel` in the former `import_settings` dialog | At the alignment pass, `BackupRestoreSettingsFragment.importDatabase` started database-only restore from its negative Cancel action | Corrected in the [backup-restore follow-up](#backup-restore-actions--corrected-2026-10-23); the shared Cancel resource is unchanged |
| `file_deleted` | `Deleter.next`; feedback precedes delayed commit, and the manager ignores the file-deletion result | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Marked for deletion: %1$s” while undo is available; later feedback must not claim confirmed erasure without a behavior change |
| `import_subscriptions_skipped` (plurals) | `SubscriptionImportWorker`; starts with “Imported.” even when no supported source entries were processed | “Import finished. Skipped %d unsupported entry/entries.” Supply proper plural forms; processed entries are not necessarily new subscriptions |

Preserve `%1$s` as a positional service-name placeholder. The existing
`<unknown_service>` fallback in `ErrorInfo.getServiceName` needs a separately
localized fallback decision, not an English-only replacement. Keep the skipped-entry
plural's quantity selection and `%d` placeholder; provide each locale's required
plural forms. The proposed deletion message introduces `%1$s` for the filename
instead of concatenating `":\n"` and the name outside the resource. Other proposals
introduce no numeric quantities. Deletion dialogs must describe consequences
without relying on a settings summary, including for screen-reader users. Update
complete messages and notify translators; do not leave translations silently
making broader claims.

Do not add atomic-deletion or combined-count success wording: watch-history
removal launches separate operations. Do not broaden the cookie action into a
claim that all WebView cookies are cleared. General style/localization polishing
is separate from the factual discrepancies listed here.

Additional localization/accessibility work, separate from factual copy fixes:
`SubscriptionImportWorker` formats progress as raw `"$currentProgress/$maxProgress"`;
use locale-aware numbers and a translatable format. Subscription headers receive
an untranslated `<unknown>` for retired IDs through `ServiceHelper`, and
`subscription_header.xml` / `Header.kt` do not mark them as accessibility headings.
Distinct fallback names and heading navigation need a focused design and TalkBack
check; accessibility impact is inferred, not a device observation from this pass.

## Validation boundaries

Local Markdown targets and heading anchors were checked programmatically; external
websites and historical artifact hashes were not re-certified. Build commands were
checked against the task definitions, and the wrapper command was executed. Exact
head checks, fresh read-only Claude review, adjudication and merge evidence belong
on the PR so they can identify the final commit without a self-referential hash in
this record. No instrumentation, user-device installation, live-service extraction
or real receiver test is claimed for this prose/comment-only pass.

## Backup-restore actions — corrected 2026-10-23

Started from post-PR-#144 master. The settings-choice dialog now uses dedicated
translatable `restore_without_settings` and `restore_with_settings` actions:
**Restore without settings** submits the existing `DATABASE_ONLY` operation;
**Restore with settings** submits `DATABASE_AND_SETTINGS`. The negative
**Cancel** button only dismisses. The title is **Restore backup**, and the
message explains whether current settings are kept or replaced and that either
restore replaces the database and restarts the app. The preceding overwrite
confirmation and legacy-format trust/security warning remain unchanged.

Only dialog presentation/action wiring changed. Archive inspection, restored
contents, settings parsing/normalization, accepted-work ownership, restart and
database activation/migration semantics were not changed. See
[the maintained restore guide](library-data.md#backup-and-restore).

### Localization

The resource inventory contains default `values/strings.xml` and
`values/strings_fcast.xml`, but no non-default locale string files or verified
equivalent translations for these actions. The four new title/message/action
keys remain translatable default-English resources; other locales use Android's
normal default fallback. Non-English translations are an explicit gap, not
unverified translations supplied by this correction. Shared Cancel/OK strings
were not changed. The obsolete, singly used `import_settings` title key was
replaced rather than retained as an alias.

### Controlled behavior evidence

`BackupRestoreDialogTest` exercises the real settings activity/fragment with
synthetic ZIPs, isolated preferences/database files and a counting restart
callback. Restoring the old negative-button callback temporarily made its
Cancel regression fail on changed preferences; the corrected handler passed.
Both restore actions passed for JSON and legacy settings, with the expected
preference contents, staged database and exactly one restart callback. Cancel,
Back, outside touch, view destruction and inspection completing after view
destruction initiated no restore. Overwrite cancellation performed no inspection;
accepting it reached the settings choice. A settings-free archive retained its
direct database-only path. The two existing real-preferences/file integration
checks also passed.

All 16 dialog checks and both existing integration checks passed together.
The UI matrix used a 320dp-wide portrait dialog at font scales 1.0, 1.3 and
2.0 for JSON and legacy settings. Screenshots were inspected at normal size
and at 2.0 scale. AppCompat stacks the three buttons; at larger fonts its
button panel scrolls. Each action was scrolled into view and checked for a
fully visible, unellipsized label, non-overlapping sibling geometry, a visible
touch target of at least 48dp, and an enabled/clickable accessibility node whose
name matches the label. This is rendered UI/accessibility-node evidence, not a
TalkBack speech/focus-order session or proof for unavailable translations.

Initial layout checks incorrectly required all button bounds to be visible
simultaneously and mixed screen/root coordinates; they were corrected to check
the full label and usable touch target after native scrolling. Window-enter
animation completion is awaited before captures. No production layout/security
workaround or shortened restore label was introduced to make those checks pass.

The complete `make ci` gate passed: build-logic/static checks, full app JVM
suite, offline extractor gate, Android lint and debug/release assembly.
Instrumentation assembly and Checkstyle also passed after the final UI-harness
corrections. Gradle reused unchanged outputs where reported. A first focused
attempt caught overlong Java lines, which were wrapped before successful
validation. Exact reviewed-head, final gate and merge evidence are recorded
on the PR rather than embedded as a self-referential commit hash here.

These are controlled API 26 emulator observations, not a restore over a user's
database, real application restart, migration or physical-device evidence.
The emulator was freshly created under a temporary directory and had no PVCPipe
installation before this run. Direct targeted instrumentation avoided Gradle's
install/test/uninstall task. No existing installation or user database was
modified. API 37 remains environment-blocked and was not retried.

## Cache, history and download messages — corrected 2026-10-23

Started from post-PR-#145 master; the backup-restore correction is unchanged.
Source inspection reconfirmed four factual discrepancies:

| Resource | Corrected message | Verified action boundary |
| --- | --- | --- |
| `metadata_cache_wipe_summary` | Clear cached stream, channel, playlist, and other extracted metadata | `InfoCache.clearCache()` evicts the in-memory extracted-info LRU: streams, channels, channel tabs, comments, playlists and kiosks. It does not clear downloads, images, cookies or all WebView data. |
| `delete_view_history_alert` | Delete all watch history and saved playback positions? | The shared confirmation is used by settings and `StatisticsPlaylistFragment`. Confirmation launches separate playback-state deletion, stream-history deletion and orphan-cleanup subscriptions; there is no atomic combined-success guarantee. |
| `download_has_started` | Download request submitted | `DownloadPreparation` calls `submitted()` after dispatch returns; Android dispatch calls `startService`, and mission preparation/execution happens later. Dispatch failure follows the existing error path instead. |
| `file_deleted` | Marked for deletion: %1$s | `Deleter.next()` displays the filename while Undo remains available, before the delayed commit. The manager does not establish successful file erasure for this feedback. |

Only messages, filename formatting and the watch-history question's placement
change. At 320dp and 2× font, the original title layout hid the playback-position
consequence, so this question now uses the wrapping dialog body; other deletion
dialogs retain their titles. The filename is passed as one positional string
argument to the complete translatable resource rather than concatenated with
punctuation. Configuration keys, shared generic strings, callbacks, separate
deletion operations, timers, undo, error handling, storage and service semantics
are unchanged. The entry-only deletion message is outside these four
discrepancies and is not globally rewritten.

### Localization coverage

The source inventory contains only default `values/strings.xml` and
`values/strings_fcast.xml`; none of these four keys has a locale or configuration
override. Existing identifiers are retained, with no duplicate fallback keys
or compatibility aliases. All four messages remain translatable; non-English
translations are an explicit gap and use Android's default-English fallback.
No verified equivalent translation was removed. Translators must preserve the
filename's `%1$s` placeholder and the pending/submitted action boundaries.

### Controlled verification and limits

A freshly created disposable API 26 emulator had no PVCPipe package before
installation. Direct, serial instrumentation targeted only that emulator; no
user installation/data, Gradle connected-test cleanup or API 37 retry was used.
The temporary rendering harness was removed after evidence collection; no
English-literal or resource-wiring-only permanent tests were added.

At 320dp portrait width and font scales 1.0 and 2.0, the actual metadata
preference summary and history-screen confirmation were rendered and their
screenshots inspected. The final history question wraps fully in the body,
including saved playback positions; Cancel and Delete remain visible. The
history-screen Cancel check requested none of the three deletion operations.
The existing `HistoryRecordManagerTest` passed all 12 checks on the emulator.

The real `Deleter` with an isolated download database and owned media file
displayed the formatted filename, hid the staged row, restored it on Undo and
preserved the file and database row beyond the five-second commit deadline.
Both font-scale checks passed. A filename containing literal `100%` and `%1$s`
was preserved intact in the message and accessibility node; it was not
recursively interpreted as a format string. The long filename is visually
ellipsized by the existing two-line snackbar. The shorter **Marked for deletion**
prefix keeps that pending-state meaning visible at 2× font, with an enabled,
clickable, visible Undo node. Full filename visibility is not claimed.

These are rendered screenshots and accessibility-node observations, not a
TalkBack speech/focus-order session or non-English coverage. Download submission's
Toast remains source-only verification: the callback follows successful request
dispatch, not confirmed execution. No live download or file-erasure success is
claimed. Initial throwaway checks incorrectly compared themed uppercase button
nodes to mixed-case resources and initialized UiAutomation from the UI thread;
the harness assertions/setup were corrected without production workarounds.
The initial longer pending-state prefix and history title were visibly clipped;
the final shorter prefix/body placement were rerendered after correction.

All 21 source values XML files parsed successfully; the four message definitions
were unique with no locale overrides, and the filename had exactly one positional
`%1$s` placeholder. The app JVM gate passed 1,008 checks, including 44
`DownloadPreparationTest`, 10 `DownloadDialogPreparationTest` and four
`DownloadManagerDeleteEntryTest` checks. An overlapping instrumentation build
failed with missing compiled symbols while `make ci` succeeded; serial
instrumentation assembly then succeeded. Temporary harness Checkstyle line-length
failures were confined to the subsequently removed evidence files.

After the final shorter message, wrapping-dialog correction and removal of
temporary harnesses, the complete serial `make ci` gate passed again:
build-logic/static checks, app JVM suite, offline extractor gate, Android lint
and debug/release assembly. Gradle reused unchanged outputs where reported.
Fresh read-only Claude Code review used `claude-opus-5-5` at medium effort.
Its sole actionable finding was ambiguity in the original proposal table;
the four rows now explicitly link to this correction and show the shipped copy.
Optional heading/TalkBack and entry-only deletion wording observations remain
separate work. Exact-head review, validation and merge evidence belongs on the PR.
