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
- The inherited reporting destinations and policy gate observed in this pass
  were removed by the [2026-10-24 copy/share follow-up](#copyshare-reporting--follow-up-2026-10-24).
  That change replaces fixed routing with user-selected Android sharing and a
  bundled factual notice; it does not certify legal compliance or third-party
  receiving-app policies. No report was submitted to the inherited destinations.
- Live service availability, receiver decoding, secure-v4 interoperability and
  device-specific behavior cannot be established by mocked JVM tests or assembling
  APKs. Earlier device/receiver observations retain their original date and build.

## User-facing text: separate follow-up

No resource text, key or translation was changed by the original alignment pass.
The table records its proposed corrections; the backup-restore,
[cache/history/download](#cache-history-and-download-messages--corrected-2026-10-23)
and [error-message follow-ups](#error-messages--corrected-2026-10-23)
below identify subsequent fixes. Resource keys refer to
`app/src/main/res/values/strings.xml`.

| Key | Call sites / factual issue | Suggested wording |
| --- | --- | --- |
| `metadata_cache_wipe_summary` | `HistorySettingsFragment` → `InfoCache.clearCache`; clears extracted in-memory metadata, not all cached webpage/WebView data | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Clear cached stream, channel, playlist, and other extracted metadata” |
| `delete_view_history_alert` | `HistorySettingsFragment.openDeleteWatchHistoryDialog`, also opened by `StatisticsPlaylistFragment`; deletes playback positions as well as stream history | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Delete all watch history and saved playback positions?” |
| `too_many_requests` | `ErrorInfo.getMessage` for a non-solvable `ReCaptchaException`; includes HTTP 429 and Kick security blocks, not necessarily a network-wide restriction or rate limit | Corrected in the [error-message follow-up](#error-messages--corrected-2026-10-23): “%1$s is not accepting requests right now. Try again later.” |
| `parsing_error` | `ErrorInfo.getMessage` for remaining `ExtractionException`s; extraction failure need not be website parsing | Corrected in the [error-message follow-up](#error-messages--corrected-2026-10-23): “Could not extract the requested information” |
| `error_report_open_github_notice` | At the alignment pass, shown for non-crash failures too | “this error” replaced “this crash” in the error-message follow-up; the reporting-only key and fixed routing were subsequently removed in the [copy/share follow-up](#copyshare-reporting--follow-up-2026-10-24) |
| `start_accept_privacy_policy` | At the alignment pass, acceptance opened a composer or issue list, not submission; code did not establish GDPR compliance | Removed with the inherited policy gate in the [copy/share follow-up](#copyshare-reporting--follow-up-2026-10-24); replaced with a factual offline reporting notice, not a legal-policy claim |
| `recaptcha_request_toast` | `ErrorInfo.getMessage` and `RouterActivity`; router opens the challenge directly, error panels offer Solve | Corrected in the [error-message follow-up](#error-messages--corrected-2026-10-23): “A reCAPTCHA challenge is required to continue.” |
| `download_has_started` | `DownloadDialog.DownloadPresentation.submitted`; service dispatch is not confirmed mission execution | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Download request submitted” |
| `cancel` in the former `import_settings` dialog | At the alignment pass, `BackupRestoreSettingsFragment.importDatabase` started database-only restore from its negative Cancel action | Corrected in the [backup-restore follow-up](#backup-restore-actions--corrected-2026-10-23); the shared Cancel resource is unchanged |
| `file_deleted` | `Deleter.next`; feedback precedes delayed commit, and the manager ignores the file-deletion result | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Marked for deletion: %1$s” while undo is available; later feedback must not claim confirmed erasure without a behavior change. The [2026-10-30 deletion correction](download-preparation.md#deletion-failure-verification-2026-10-30) now retains and reports failed removals; the separate [process-death follow-up](download-preparation.md#deletion-process-death-verification-2026-10-30) verifies saved-receipt recovery and measures the no-receipt limitation |
| `import_subscriptions_skipped` (plurals) | `SubscriptionImportWorker`; formerly started with “Imported.” even when no supported source entries were processed | Corrected in the [subscription-import follow-up](#subscription-import-presentation--corrected-2026-10-23): “Import finished.” plus the pluralized unsupported-service explanation; processed entries are not necessarily new subscriptions |

Preserve `%1$s` as a positional service-name placeholder. The existing
`<unknown_service>` fallback in `ErrorInfo.getServiceName` needs a separately
localized fallback decision, not an English-only replacement. The subscription-import
follow-up preserves the skipped-entry quantity selection and uses positional `%1$d`;
future translations need each locale's required plural forms. The proposed deletion
message introduces `%1$s` for the filename
instead of concatenating `":\n"` and the name outside the resource. Other proposals
introduce no numeric quantities. Deletion dialogs must describe consequences
without relying on a settings summary, including for screen-reader users. Update
complete messages and notify translators; do not leave translations silently
making broader claims.

Do not add atomic-deletion or combined-count success wording: watch-history
removal launches separate operations. Do not broaden the cookie action into a
claim that all WebView cookies are cleared. General style/localization polishing
is separate from the factual discrepancies listed here.

Additional localization/accessibility work was identified separately from factual
copy fixes. The [subscription-import follow-up](#subscription-import-presentation--corrected-2026-10-23)
addresses its raw progress interpolation with a complete translatable format and
UI-locale numbers. Subscription headers still receive
an untranslated `<unknown>` for retired IDs through `ServiceHelper`, and
`subscription_header.xml` / `Header.kt` do not mark them as accessibility headings.
Distinct fallback names and heading navigation needed a focused design and TalkBack
check; accessibility impact was inferred, not a device observation from this pass.
The separately authorized [subscription-heading follow-up below](#subscription-headings--2026-10-29)
adds distinguishable resource labels and heading metadata; its verification is
reported separately from this older audit.

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

## Error messages — corrected 2026-10-23

Started from post-PR-#146 master (`ddf26a13d`); PRs #145–146 and unrelated
workspace changes are preserved. Three proposed replacements were supported;
the rate-limit proposal required broader wording after fresh review:

- `too_many_requests`: **%1$s is not accepting requests right now. Try again later.**
  `DownloaderImpl.execute` maps HTTP 429 to `ReCaptchaException`, but
  `KickApi.body` also throws it for security-policy blocks and Cloudflare
  challenges independently of status. `ErrorInfo.getMessage` selects this
  message when `solvableCaptchaUrl` is null, including Kick. The proposed
  “Too many requests” would falsely imply a rate limit on that path. The final
  wording establishes neither an IP ban nor a network-wide restriction.
- `parsing_error`: **Could not extract the requested information**.
  The remaining `ExtractionException` branch follows specific content, CAPTCHA
  and network classifications; it does not establish website parsing as the cause.
  Its misleading parsing comment was corrected without changing classification.
- `recaptcha_request_toast`: **A reCAPTCHA challenge is required to continue.**
  `RouterActivity.handleError` opens the challenge directly; `ErrorPanelHelper`
  offers Solve. Neither context requires a separate message.
- `error_report_open_github_notice`: **this error** replaces **this crash**.
  `activity_error.xml` is also inflated for reportable extraction/other non-crash
  failures in `ErrorActivity`; no report destination or consent copy changed.

Shared `ErrorInfo` messages also reach `ErrorUtil` snackbars/notifications and
the report screen. The existing positional `%1$s`, service lookup and
`<unknown_service>` fallback are unchanged, as are retry/report eligibility,
challenge host checks, logging, actions and navigation. Generic labels,
subscription-import plurals, privacy-policy/legal content and report/email
destinations are outside this correction.

### Localization and verification boundaries

All 21 source values XML files parsed successfully. Each affected key has exactly
one default definition and no locale/configuration override; no verified
equivalent translation was removed. These remain translatable English defaults.
Non-English equivalents are an explicit fallback gap for translators, who must
retain `%1$s` and avoid reintroducing network-wide, rate-limit-only or parsing-only claims.

A throwaway JVM smoke formatted the actual request-refusal resource with YouTube,
SoundCloud, Kick, the unknown-service fallback, literal `100% %1$s`, and `A & B`.
Arguments survived positional formatting without recursive interpretation.
This checks the Java formatting boundary, not Android's HTML conversion or
rendered layout. Existing CAPTCHA tests cover allowed hosts, other-service
rate limits, deceptive hosts, invalid URLs and retry/report eligibility.
The existing instrumentation parcel test was inspected, not executed.

No device was attached and no user installation/data was touched. No rendered
UI, TalkBack, live service or challenge completion is claimed; panel, toast,
notification and report-screen coverage above is source inspection. The blocked
API 37 environment was not retried. Exact final gate, fresh read-only Claude
review, adjudication and guarded merge evidence are recorded on the PR.

## Subscription-import presentation — corrected 2026-10-23

Started from post-PR-#147 master (`56360d4a5`). Source inspection reconfirmed
that all-unsupported input and empty input can succeed without storing entries.
Import `Success.count` is the number of supported source entries processed,
including duplicates and updates of existing subscriptions, not newly created
subscriptions. Export counts snapshot entries. The count documentation now
states that distinction; counters and outcome semantics are unchanged.

Successful imports use dedicated **Import finished.** text regardless of the
processed count. With skipped entries they use **Import finished. Skipped 1
channel of a service PVCPipe does not support.** or the plural **… Skipped 2
channels of services PVCPipe does not support.** The former `import_complete_toast`
had no remaining source callers and was removed, not globally rewritten. Failure
copy, cancellation propagation, Toast durations, extraction, batch/transaction
boundaries and work scheduling are unchanged.

Notification subtext uses the complete translatable
`subscription_import_progress` format, `%1$s/%2$s`, with both arguments formatted
by one integer `NumberFormat` for the app UI locale. Active AppCompat locales
take precedence; AndroidX `LocaleManagerCompat` supplies saved/framework locales
when a worker starts without an AppCompat activity, then the system default is
used when no app locale is selected. Numeric progress-bar values and plural
selectors remain raw integers. Loading/importing
determinate/indeterminate conditions, foreground ownership and Cancel actions
are unchanged.

### Localization

All 21 source values XML files parsed successfully. The affected definitions
exist only in default `values/strings.xml`; there are no locale or configuration
overrides and no verified translations to remove. The skipped message retains
English `one`/`other` forms with positional `%1$d`. New completion and progress
resources are translatable. Non-English prose translations remain an explicit
gap using normal Android default fallback. Localized number formatting does
not supply translated prose. Translators must preserve positional placeholders,
unsupported-service meaning and the absence of any newly-added count claim.

### Verification boundaries

Privacy/report destinations, subscription headings and broader accessibility
work remain separate. No user installation/data was modified, and the blocked
API 37 environment was not retried. Exact-head gate, independent review,
adjudication and guarded merge evidence belong on the PR.

On a freshly created disposable API 26 emulator, all five packaged-resource
formatting checks and all four isolated Room transfer checks passed. Completion
coverage includes zero/one/multiple skipped entries and singular/plural selection;
progress covers grouped counts through `Int.MAX_VALUE` in en-US, de-DE and ar-EG,
including Arabic digits and an app UI locale differing from both the process
default and resource locale. The duplicate/upsert regression processes 51
identical entries across the 50-entry boundary, preserves the original UID and
one database/feed row, reports import count 51 and exports count 1.

A subsequently removed runtime harness enqueued the real import worker through
WorkManager using disposable empty, one-unsupported and two-unsupported source
files. All three reached `SUCCEEDED`; logged Android-resource completions were
the zero/singular/plural messages above. This exercises the real successful
worker path, but logs and formatting assertions are not observations of rendered
Toast or notification layout. Notification progress/bar transitions and Cancel
action preservation are source inspection, not rendered-notification evidence.
The existing 26 JVM transfer tests passed, including mixed/empty/supported input,
source/extraction/storage failures and cancellation propagation.

Direct targeted instrumentation was used only on the newly created API 26 AVD,
which had no PVCPipe installation before this run. The runner is AndroidJUnitRunner;
database checks use disposable in-memory Room storage. No connected-test
install/uninstall cleanup was used. Initial Ktlint failures in expression-body
layout and the temporary logging harness were corrected before successful
style checks and APK/instrumentation assembly.

Independent read-only Claude Code review used `claude-opus-5-5` at medium effort.
It found no blocking behavioral issue, but flagged default-English plural
fallback limitations, the now-unused legacy completion key and locale-test
safety documentation. Locale tests temporarily change AppCompat/framework app
language settings and restore them afterward; they are restricted to disposable
environments because process death before teardown could leave a saved change.
The initial complete serial `make ci` gate passed. A further API 26 regression
then reproduced the review's cold-worker concern: with no active AppCompat
locale, the original formatter ignored a saved ar-EG selection and used the
process default. The focused presentation helper now reads that locale through
AndroidX's supported API without changing global locale initialization. The
regression redirects AndroidX's locale-record read to a disposable XML fixture;
it does not overwrite the application's real saved-language file.

The cold-locale regression failed before the correction and passed afterward;
all five formatting and four Room checks then passed together. A removed
Android-resource probe also verified the review's fallback warning: ru-RU
quantity 21 (and 101) selects English `one`, producing “21 channel”, while 22
selects `other`. This is an inherited default-English fallback limitation, not
a Russian translation or a newly changed plural selector. Proper translated
prose/plural forms remain a translator gap; numeric localization does not fix it.

A fresh second complete-diff read-only review found no blocking issues. Its
optional consistency observation was adjudicated separately: existing title
and Toast counts still use application resources and can differ from progress
digits on API 26–32. No translated progress-format override currently exists.
Changing those other messages' locale ownership is outside this focused
progress-number correction; future translations must also verify background
resource-context selection. Review also noted that restoring an initially
empty AppCompat locale can later clear a saved locale when an activity starts.
The test's disposable-environment restriction covers that risk as well as
process death; teardown is not a user-installation safety guarantee. API 33+
locale/default scheduling was not exercised and is not claimed.

After the material cold-locale correction and removal of temporary probes, the
complete final serial `make ci` gate passed again: build-logic/static checks,
full app JVM suite, offline extractor gate, Android lint and debug/release
assembly. Instrumentation assembly also passed, and the final targeted API 26
run repeated all nine retained checks successfully after probe removal. Gradle
reused unchanged outputs where reported. The second review covered the complete
corrected production/test diff; later edits only clarify review disposition and evidence.

## Reporting correctness — follow-up 2026-10-23

This follows the 2026-10-23 read-only reporting/privacy assessment's three
report-content correctness findings. Its separate recipient/destination,
privacy-notice, capture-default and attachment-consent decisions remain open;
email-only routing and unconfirmed toolkit/ACRA concerns are not corrected here.

Started from post-PR-#148 master (`f8612efbe`). A detached disposable worktree
kept production sources unchanged; only synthetic regression fixtures were
added. On a newly created disposable API 26 emulator, nine instrumentation
checks ran with four expected failures: pagination cookie-only data appeared
in the diagnostic request, both capture-enabled and capture-disabled delayed
reports used opening time instead of construction time, and Markdown emitted
Service twice. No API 37 environment or user installation was used.

The correction removes the cookie-map interpolation, not the page or cookies
supplied to extraction. Query, URL and page IDs remain diagnostic inputs.
`ErrorInfo.request` flows unchanged to the screen and both builders; ErrorInfo
has no custom object serialization that expands a Page/cookie map. The inherited
debug snackbar log prints the ErrorInfo object identity; the early log helper
prints exception text and attached exception data, not request/page fields.
This source trace is not a general log-sanitization guarantee.

The report activity now retains the returned positive ErrorInfo construction
timestamp regardless of capture preference, falling back to activity-opening
time for missing/nonpositive timestamps. The timestamp comes from the
wall-clock/capture-trigger point in public ErrorInfo constructors. ACRA's sender
constructs a new ErrorInfo from a stored stack trace and does not carry through
`USER_CRASH_DATE`: its timestamp remains sender-time construction, not original
crash time. Markdown's second Service line is removed; other report content,
exception bounds and JSON structure are unchanged.

See [error-reporting.md](error-reporting.md) for the current contract and
remaining sensitive-input exposure. Destination addresses, repository issue
settings, capture defaults, privacy-policy publication and attachment consent
are intentionally unchanged. Unconfirmed toolkit/ACRA concerns are not bundled.

### Controlled regression and surface evidence

A clarified baseline assertion identified the cookie-only marker in **request,
Markdown and JSON**, but not ErrorInfo's object identity string. The corrected
build passed all nine retained instrumentation checks and a subsequently removed
synthetic surface smoke (10 total) on the disposable API 26 emulator. The screen
was opened and visually inspected with synthetic diagnostics and exception text;
the displayed timestamp was present. No sharing action was accepted or report
sent; report-builder/ACRA tests intercepted activity intents.

The pagination regression calls real `SearchFragment.handleNextItems` with a
synthetic Page and error, captures its real ErrorInfo, and invokes the real
activity and Markdown/JSON builders. Capture enabled/disabled both retain query,
URL and page IDs, exclude the cookie-only marker from request/object string,
both reports and app-process logcat, and preserve the original Page cookie map.
Its unattached fragment substitutes footer/snackbar delivery; inherited
snackbar UI/debug logging is source-inspected, not runtime-covered by that
fixture. A focused JVM regression runs the real app helper and SearchInfo
dispatch with a controlled extractor peer, which receives the identical Page
and cookie map. This is extraction-boundary evidence, not live network/service
or receiver/hardware proof.

Report regressions cover delayed opening with capture enabled/disabled;
nonpositive timestamp fallback; actual ACRA sender-time construction with a
synthetic historical crash date; exactly one Markdown Service; all JSON fields,
user comments, single/multiple exceptions and constructor truncation bounds.
External activity starts are intercepted and fixtures are disposable; restoring
preferences is not a guarantee that these tests are safe on a user installation.
The complete serial gate, fresh official Claude review, independent adjudication
and exact-head merge disposition are recorded on the PR rather than inferred
from these focused checks.

After removal of the temporary surface smoke, Checkstyle, ktlint, the focused
JVM extraction-boundary regression and instrumentation assembly passed. The
final packaged retained suite then passed again on API 26: **9 tests**.

## Copy/share reporting — follow-up 2026-10-24

Started from post-PR-#149 master. The earlier reporting correctness corrections
remain: pagination diagnostics omit cookie-map values, reports use positive
ErrorInfo construction timestamps and Markdown includes Service once.

The selected product configuration removes fixed email and GitHub reporting
routes and the inherited policy-acceptance gate. The screen instead exposes
**Copy report**, **Share report…**, report/log inspection and an offline
**Reporting notice**, opened by **View reporting notice**. Copy exports Markdown
text only. Sharing exports JSON as `text/plain` with an optional single matching
diagnostic log granted read access; the receiving app and destination are chosen
by the user. Inspection previews both exact text formats. Selecting an app hands
it the data immediately, and it may process it without another Send action;
submission or delivery to a maintainer is not confirmed. There is no second
toolbar share action or standalone copy-log action. No report was submitted
and no upstream repository was mutated as part of this resource/documentation
work.

Log attachment selection is initially unchecked and resets on recreation or a
new report. Capture remains a separate setting, enabled by default. A selected
log must be completed and readable; a failure does not silently drop the
attachment. The disclosure names sensitive report inputs and asks users to
inspect content. The notice is bundled as a resource, requires no network, and
makes no GDPR, published-policy, retention or receiving-app privacy promise.
Obsolete reporting-only routing/policy/consent resources and the duplicate share
menu are removed. Project links, license content and SponsorBlock policy links
are not changed.

Source inspection confirms that `Disable error reporting` gates presentation,
not constructor-triggered local capture or ACRA initialization. ACRA retains
its default local collection configuration; the custom sender imports only the
stored stack trace into ErrorInfo. The log toolkit uses internal cache storage,
a requested 30-second lower bound and keep-last-files cleanup threshold 10.
These do not establish an exact recording window, fixed retention period or
hard instantaneous file cap. Changing capture defaults, limiting ACRA collection
and redesigning the inherited debug viewer were expressly outside this choice.
The maintained [reporting guide](error-reporting.md) documents these boundaries.

The new copy, disclosure, inspection and notice resources are translatable
default-English strings; no equivalent non-English translations are claimed.
PR #149's dated evidence alone does not validate this newer copy/share behavior.

### Controlled validation

A newly created disposable API 26 emulator passed 21 targeted instrumentation
checks: the nine PR #149 reporting/pagination regressions, eleven copy/share
behavioral checks, and a separate-UID attachment receiver check. Outgoing report
actions were intercepted; the only actual attachment recipient was a standalone
activity in the test APK, with no network permission. It read the single selected
file and was denied write access. These are controlled Android handoff results,
not delivery evidence from a mail client, service or maintainer.

The copy/share checks exercise clipboard text, JSON share text, unchecked grants,
single-file ClipData/read permission, recreation/new-report reset, missing,
unreadable and unfinished selected logs, report/log inspection and offline notice
viewing. Negative availability assertions wait for asynchronous lookup completion.
Capture preferences are restored by fixtures; these tests remain restricted to
disposable environments, not user installations.

A removed temporary probe also triggered the real log toolkit with a synthetic
error and verified that its completed file was recognized and contained the
synthetic diagnostic marker. Completion checking is therefore exercised against
actual toolkit output, not only hand-written wrappers. The temporary capture and
rendering probes both passed and were removed from the retained suite.

The actual reporting activity was rendered at 320×640 mdpi with font scale 2.0
on that disposable emulator. Disclosure text wrapped, the initially unchecked
attachment choice and all full-width actions remained reachable by scrolling,
and the bundled notice and separate log preview rendered as scrollable dialogs
with reachable dismissal buttons. The real Android chooser was opened and
canceled with Back without selecting a recipient. No TalkBack, physical-device,
third-party receiver behavior or newer-API result is claimed.

Fresh official Claude Code reviews used `--model claude-opus-5-5 --effort medium`
with read/search-only tools and no session persistence. The first review's
confirmed cleanup/style and test-synchronization issues were corrected; a second
complete-change review found no confirmed bugs or merge blockers. Independent
adjudication retained offered log inspection rather than adding an unrequested
mandatory inspection gate. Whole-file preview cost, rapid repeated Share taps
and the standard chooser's grant-propagation coverage gap remain nonblocking
limitations; no third-party processing or delivery guarantee is inferred.

The final serial `make ci` passed all five stages: build-logic/static checks,
app JVM and offline extractor gates, Android lint, debug assembly and release
assembly. Instrumentation assembly passed after temporary probe removal.
Gradle reused unchanged outputs where reported. Existing compiler warnings do
not establish runtime failures, and this gate does not exercise API 37 devices.

## Repository-wide documentation follow-up — 2026-10-28

Started with a clean checkout on `master` at
`6b4d4bf01fc976bcbfae99fda1548f5ab13e8573`. At the end of this documentation
pass, changes remained local; no commit, push, PR, issue, comment or other remote
mutation had been made. The separately authorized implementation follow-up below
also covers committing, merging and pushing the accumulated changes. Root `AGENTS.md`
and `/home/user/.codex/RTK.md` were read before inspection; no nested
`AGENTS.md` was found. Repository and device-safety rules remain unchanged.

### Inventory and implementation coverage

The starting tracked tree contained 2,513 files, including 22 maintained
Markdown/text documentation surfaces and 1,299 Java/Kotlin/Gradle Kotlin files
before generated-source exclusions. A mechanical source-comment inventory
identified 7,792 nonlicense comment blocks in 843 files. Those counts describe
the inventory, not independent verification of every statement. The review used
repository-wide searches and cross-reference checks, then traced substantive
guide claims and discrepancies through implementations and callers.

| Area | Sources and treatment |
| --- | --- |
| Overview, terminology, agents and contributors | Root README, glossary, AGENTS, documentation index and all current guides; retained remote/device restrictions |
| Versions and builds | `version.properties`, `PvcVersion` and tests, app/extractor/settings/buildSrc scripts, version catalogs, wrapper/daemon properties, Makefile, Checkstyle, ktlint, lint and shrinker configuration |
| Services | `ServiceList`, `NewPipe` lookup, all six service registrations and capability factories, migrations, filters, downloader/cookie handling, Odysee retry/count policies and YouTube client/proof-token callers |
| Playback | Source selection/resolution, Media3 session/service and notifications, FCast controller/session/discovery/proxy, queue events and managed-source completions, recovery/history ownership, audio, detail navigation and the assertions/mocked boundaries of related tests |
| Library data | Local playlist manager/fragment, bookmark saves, subscription grouping/transfers, feed selection/loading/storage, backup inspection/preparation/publication and startup activation |
| Downloads and reporting | Selection/dispatch, HLS preprocessing/remuxing, checkpoint/finalization and file sharing; ErrorInfo, log capture, report copy/share builders, ACRA initialization/sender and pagination diagnostics |
| Embedded and test documentation | JavaDoc/KDoc, inline/block/XML/config comments, package docs, test helpers and fixture READMEs, Python tool docstrings/comments, OMP adapter and timeago generator entry points; hand-written `PatternsManager`/`PatternsHolder` inspected separately from generated patterns |
| Dated records | Retained dated observations, failures, hardware results and historical engine versions; old ExoPlayer links in the current playback guide are explicitly identified as the earlier investigation |

Generated output/caches, binaries, recorded response payloads, generated timeago
patterns/PatternMap, json2java4nanoJson models and Protocol Buffers/Wire output
were excluded from prose editing. License/copyright/attribution notices and
incorporated third-party algorithm provenance were preserved. There are no
tracked CI workflow files in this checkout; the local gate is the Makefile.
Historical artifact captures and hashes, every external link, live services,
and device/receiver scheduling were not re-certified. This is a claim-focused
implementation review, not a line-by-line proof of every inherited algorithm
comment or a new hardware campaign.

### Corrections

- Media3 controllers can change active FCast speed when supported. The old
  media-session-connector limitation no longer describes `PvcSessionPlayer`.
  Surface, codec, service-lifetime, queue-reordering and managed-completion
  comments now distinguish implemented behavior from old API names or guarantees.
- Feed loading uses separately enabled stream tabs, with initial-page/one-empty-page
  continuation loading, rather than exhausting a channel or loading one fixed tab.
  Progress validity is strictly above five seconds **or** one quarter of duration;
  completion uses at most 60 seconds remaining **and** at least three quarters.
- “Stored locally” replaces an exclusive-device claim: the manifest permits
  Android backup. App ZIP exports include both settings formats; database-only
  restore still updates the remembered document URI. Inspection/staging limits
  are explicit without endorsing malformed archives as supported inputs.
- Extractor comments now acknowledge HLS and its source-admission rules,
  singleton link factories, other services' comment likes, actual parser group
  behavior, locale-parser limits, cookie scope and valid tagged logger examples.
  Test descriptions identify their actual kiosk subset and downloader-selection
  behavior rather than claiming complete service or universal generator coverage.
  The review correction below removes the inherited null-list wording that this
  pass initially repeated. At that point null lists remained an implementation
  defect, not a contract; the separately authorized implementation follow-up below
  corrects their handling.
- Remaining corrections cover the real Checkstyle tag settings, standalone
  extractor version validation limits, debounce completion API, timestamp-request
  dispatch, duration truncation, blocking image loads, seek-option resource
  fallback, SAF capabilities and width/theme/plural XML comments. Runtime strings,
  resource values, assertions, dependencies and executable configuration are unchanged.

### Suspected defects and remaining ambiguities

These were reported rather than fixed by this documentation task. Unless stated
otherwise, they are source-level findings without a new Android reproduction.

| Finding | Implementation and evidence limit |
| --- | --- |
| Null stream lists were not handled safely at the audited revision | [`StreamInfo.extractStreams`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/stream/StreamInfo.java#L148) stored getter results through setters without normalization. Its source check dereferenced the video list unconditionally and the audio list when the video list was empty, before checking manifest URLs. Null video/audio lists could therefore escape as `NullPointerException`, rather than `StreamExtractException`; a null video-only list reached callers such as [`AndroidPlaybackEnvironment`](../app/src/main/java/org/schabi/newpipe/player/resolver/AndroidPlaybackEnvironment.java#L53). The documentation-only pass changed no signatures, annotations or behavior. The separately authorized [null-list correction below](#null-list-implementation-follow-up--2026-10-28) now normalizes those results. |
| ZIP inspection did not validate format at the audited revision | The [former `ZipHelper.isValidZipFile`](https://github.com/75ohmantenna/pvcpipe/blob/86adf7b59d8e2baef869eeeeffe05dccaf5a1a99/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#L168) only opened/closed a `ZipInputStream`, and `BackupRestore.inspect` used that result. A standalone JDK probe accepted synthetic plain text and found no entry. That was stream-boundary evidence, not the complete Android workflow. The [backup-validation follow-up below](#backup-validation-follow-up--2026-10-28) replaces this predicate with private-copy central-directory inspection. |
| Non-SQLite databases could reach staging at the audited revision | The [former `ImportExportManager.prepareRestore`](https://github.com/75ohmantenna/pvcpipe/blob/86adf7b59d8e2baef869eeeeffe05dccaf5a1a99/app/src/main/java/org/schabi/newpipe/settings/export/ImportExportManager.kt#L102) only checked entry presence/nonzero size before publication. [`PendingDatabaseRestore.install`](https://github.com/75ohmantenna/pvcpipe/blob/86adf7b59d8e2baef869eeeeffe05dccaf5a1a99/app/src/main/java/org/schabi/newpipe/settings/export/PendingDatabaseRestore.java#L25) moved it before database opening. Placeholder/rollback tests did not establish SQLite openability, and malformed-database startup was not exercised. The [backup-validation follow-up below](#backup-validation-follow-up--2026-10-28) adds SQLite/Room checks before new restores are published. |
| Bookmark save could clear a newer draft's dirty flag at the audited revision | The [former `BookmarkFragment.saveImmediate`](https://github.com/75ohmantenna/pvcpipe/blob/6b6d3a786bd514e25f030cf452c033ffd098c1a6/app/src/main/java/org/schabi/newpipe/local/bookmark/BookmarkFragment.java#L374) used unconditional `setNoChangesToSave()` in asynchronous completion. Compare the revision captured by [`LocalPlaylistFragment.saveImmediate`](../app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java#L652). The audit reported a suspected race without a new scheduling regression. The separately authorized [bookmark-save follow-up below](#bookmark-save-ownership-follow-up--2026-10-28) reproduces and corrects it. |
| Orphan cleanup omitted permanent-thumbnail references at the audited revision | The [former `StreamDAO.deleteOrphans`](https://github.com/75ohmantenna/pvcpipe/blob/0c7df4bd8210883f22c0e7d3465808029a55328d/app/src/main/java/org/schabi/newpipe/database/stream/dao/StreamDAO.kt#L110) checked history, playlist contents and feed only. [`PlaylistEntity`](../app/src/main/java/org/schabi/newpipe/database/playlist/model/PlaylistEntity.kt#L27) stores a thumbnail stream ID without a foreign key; [`getPlaylistMetadata`](../app/src/main/java/org/schabi/newpipe/database/playlist/dao/PlaylistStreamDAO.kt#L99) looks the image up in that stream row. The audit's synthetic SQLite probe removed a row referenced only by a permanent thumbnail, making the image lookup null; that was SQL-boundary evidence, not an Android/Room or rendered-image test. The [thumbnail-retention follow-up below](#thumbnail-retention-follow-up--2026-10-28) adds the missing reference check and behavioral regressions. |
| Parser nullability did not match annotations at the audited revision | The [former `Parser.matchGroup`](https://github.com/75ohmantenna/pvcpipe/blob/ce6f17429af09ee9478631e04c3eb3169deb9e4d/pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/utils/Parser.java#L122) delegated to `Matcher.group`. The audit's JVM probe returned null for an unmatched optional capture, accepted group zero and propagated `IndexOutOfBoundsException` for a nonexistent group. That documentation-only pass changed no signatures or callers. The separately authorized [parser nullability follow-up below](#parser-nullability-follow-up--2026-10-29) corrects the return annotations while preserving matching behavior. |
| SAF exception cleanup could stop early at the audited revision | The [former `FileStreamSAF.close`](https://github.com/75ohmantenna/pvcpipe/blob/3115e79a2c71a73db910a4797bd858c10ff1f0d3/app/src/main/java/us/shandian/giga/io/FileStreamSAF.java#L110) grouped all closes in one try and marked disposed first. An early IOException skipped later explicit closes; the audit did not test actual descriptor/provider consequences. The separately authorized [SAF close follow-up below](#saf-close-follow-up--2026-10-29) adds failure-safe cleanup and regression coverage. |
| Seek-option label fallback was missing at the audited revision | The [former `VideoAudioSettingsFragment.updateSeekOptions`](https://github.com/75ohmantenna/pvcpipe/blob/235aa1fba0942e11255790d03972a8c23c6cd24e/app/src/main/java/org/schabi/newpipe/settings/VideoAudioSettingsFragment.java#L133) added a value before plural lookup but appended no label on `NotFoundException`. Bundled default plurals exist; the comment formerly promising an English fallback was inaccurate. A resource failure could leave the arrays misaligned, but was not reproduced on Android during that audit. The separately authorized [seek-label follow-up below](#seek-duration-label-follow-up--2026-10-29) reproduces the error path with controlled resource failures under Robolectric and adds the static-label fallback. |

The separate inherited cast initial-source-start concerns under
[active-cast checkpoint attribution](playback.md#active-cast-checkpoint-attribution--corrected-2026-10-23)
remain open. This pass does not strengthen the older controlled JVM evidence,
resolve source-start ordering or claim real receiver-clock provenance beyond the
implemented protocol policy.

### UI-text proposals left separate

No user-facing text or translation was changed, including in XML files whose
comments were corrected.

| Surface | Proposal and status |
| --- | --- |
| Report-prompt setting wording | The [former title and summary](https://github.com/75ohmantenna/pvcpipe/blob/d3341d61a6acd2b9c5ac272cecc2c06a8d9410fc/app/src/main/res/values/strings.xml#L912) implied a broader collection switch than [`ErrorUtil`](../app/src/main/java/org/schabi/newpipe/error/ErrorUtil.kt#L204) implements. The audit proposed describing hidden screens/notifications, independent local capture/ACRA collection, and removing the generic risk warning in a separately scoped UI change. The authorized [report-prompt wording follow-up below](#report-prompt-setting-wording--2026-10-29) now applies that correction. |
| Subscription service headings | The [former header binding](https://github.com/75ohmantenna/pvcpipe/blob/1f255eaf28243638dc8fd2d7c0b2e81cafb31dfb/app/src/main/java/org/schabi/newpipe/local/subscription/SubscriptionViewModel.kt#L63) used the shared helper's untranslated `<unknown>` fallback for retired IDs. The [subscription-heading follow-up below](#subscription-headings--2026-10-29) adds distinguishable resource labels and heading metadata specifically for these sections. Other callers of `ServiceHelper.getNameOfServiceById` retain their existing fallback. |

### Verification of this follow-up

Local Markdown paths and heading anchors, source references, documented Gradle
tasks, changed diffs and executable-token/resource-value preservation were checked.
Selected external references were read from the official
[Kotlin compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html),
[FCast protocol](https://docs.fcast.org/protocol/),
[FCast Android guidance](https://docs.fcast.org/sdk/tutorials/android/),
[Android APK debugger guide](https://developer.android.com/studio/debug/apk-debugger)
and [Android bound-service lifecycle guide](https://developer.android.com/develop/background-work/services/bound-services).
These verify referenced guidance, not this app's runtime interoperability.

| Check | Result |
| --- | --- |
| `./gradlew --offline --no-daemon --version` | Gradle 9.8.2; daemon criteria Java 25; launcher Java 17. The compilation toolchain remains Java 21 in the build definitions. |
| `make ci` | Passed all five serial Gradle stages: build-logic/static checks, app JVM/offline extractor tests, debug lint, debug assembly and release assembly. Cached/up-to-date outputs were reused where reported. |
| Python fixture checks within `make ci` | 12 sanitizer tests passed; 685 recordings checked, zero replacements required, zero skipped binary payloads or remaining detected identifiers. This is not general secret-scanner certification. |
| JVM reports | Build logic: 3 tests; app: 1,044; offline extractor: 613, including 3 skips. No failures or errors. These are JVM/fixture results, not new Android engine or service observations. |
| Final comment checks | `:app:runCheckstyle`, `:pvcpipe-extractor:extractor:checkstyleMain` and `:pvcpipe-extractor:extractor:javadoc` passed together after the last source-comment adjustments. JavaDoc emitted 100 missing-documentation/tag warnings, including generated patterns. No symbol-by-symbol documentation expansion was attempted. |
| Links, XML and spelling | All 197 local prose links resolved, including 15 code-line references; all 363 tracked XML files parsed; changed-file `codespell` and `git diff --check` passed. |
| Preservation review | All 40 changed source/resource/config files retained executable tokens, resource values and property values. Legal headers were retained. All changed diffs and a second stale-reference/contradiction pass were reviewed. |
| Command and boundary probes | Makefile dry run and both device-harness `--help` commands matched documented flags without device access. Standalone JDK ZIP/compiled-parser and synthetic SQLite cleanup probes produced the results described above. |

Two intermediate gates caught overlong JavaDoc lines introduced during editing;
both were wrapped before the successful gate. An initial standalone parser-probe
launch overlapped rebuilding the extractor class output and could not resolve
`Parser`; the repeated launch after compilation succeeded. No test assertion or
executable code was changed to obtain a pass. Compiler/Gradle deprecation warnings
remain. No instrumentation execution, emulator/device access, installation,
uninstall, app-data clearing, live-service extraction or physical receiver test
was performed in this follow-up.

### Review corrections — 2026-10-28

A user-supplied read-only Claude review identified three inaccurate comment
rewrites and one unchanged stale comment. Implementation/caller inspection
confirmed all four. The stream getters now call for empty lists instead of
presenting null as supported; the unhandled-null defect is recorded in the
table above. No nullability annotation, signature or implementation was changed.

The cookie comment again carries one historical issue reference. The source
before rebrand commit `f2a324bad` identifies it as
`bravenewpipe/NewPipeExtractor#123`, rather than either rebranded repository URL.
That URL could not be fetched during this follow-up; the reference preserves
source provenance and does not certify current issue availability or service
behavior. The DASH test description now distinguishes a real-network suite
from livestream content. The adjacent seek-option comment about initializing
`seek_duration_description` was removed because labels come from `R.plurals.seconds`.

The earlier `make ci` result above belongs to the preceding repository-wide pass.
This comment-only correction does not claim a new full gate or newer JVM test
results. Fresh `:app:runCheckstyle`,
`:pvcpipe-extractor:extractor:checkstyleMain` and
`:pvcpipe-extractor:extractor:javadoc` passed together after these corrections;
JavaDoc again reported 100 missing-documentation/tag warnings. Changed-file
`codespell`, `git diff --check` and all 199 local documentation links passed,
including bounds checks on 17 code-line references. Incremental diff review
confirmed that only these four Java comment edits and this record changed from
the reviewed working tree; Java executable tokens are unchanged. No full
`make ci`, JVM tests, live-network extraction or device checks were rerun.

### Null-list implementation follow-up — 2026-10-28

After the documentation review, the user explicitly authorized a separate code
fix, regression coverage, adversarial review, signed commits, a local merge and
a push to `github.com/75ohmantenna/pvcpipe`. The documentation corrections remain
a separate commit from this implementation change. The starting revision is
still `6b4d4bf01fc976bcbfae99fda1548f5ab13e8573`.

The three `StreamInfo` stream-list setters now convert null inputs to empty
lists, retaining supplied nonnull lists. This also protects results assembled
through `StreamInfo.getInfo`. Source admission still requires audio, combined
video, DASH or HLS; video-only streams alone still fail. With an admitted source,
optional getter failures remain in the returned info's diagnostics. An explicit
audio `ContentNotSupportedException` still aborts extraction. This correction
does not validate individual list elements or migrate previously serialized
objects that bypass the setters.

The new offline `StreamInfoTest` invokes the real assembly path with synthetic
extractor metadata and a downloader that rejects network requests. Its 38 cases
cover all eight null/empty combinations with DASH and HLS, missing sources,
video-only rejection, live manifest-only metadata, retained audio/video sources,
public setter clearing and the existing exception paths. Against the unchanged
implementation, 28 cases failed and ten controls passed; with the setter guards,
all 38 passed. An earlier fixture initialization error (a required video-builder
flag was missing) was corrected before recording this red/green baseline.
Manifest cases establish `StreamInfo` admission, not downstream resolver
selection or successful media playback.

Two adversarial self-review passes examined the actual short-circuit/source
admission path and the API/error-handling boundaries. They added live-manifest
coverage and checked that normalization does not swallow exceptions, copy
nonnull lists or strengthen serialization guarantees. These are self-reviews
and offline JVM results, not independent reviews or live-service, Android-engine,
emulator or receiver observations. The other suspected defects and UI-text
proposals above remain open.

Fresh verification of the combined documentation and implementation tree:

| Check | Result |
| --- | --- |
| `make ci` | Passed all five serial Gradle stages, including debug lint and both debug/release APK assembly; Gradle reused cached/up-to-date outputs where reported. The fixture tool's 12 tests passed, and 685 recordings needed no replacements. |
| JVM reports | Build logic: 3 tests (up-to-date); app: 1,044; offline extractor: 651, including 3 skips and all 38 new regressions. No failures or errors. Existing suites use their own mocks, recordings or controlled peers; these counts do not establish live-service or device behavior. |
| Android lint | Passed; the debug XML report contains 59 warnings and no errors. This was static analysis, not instrumentation. |
| JavaDoc | `:pvcpipe-extractor:extractor:javadoc` passed after the final source changes, reporting 100 missing-documentation/tag warnings. |
| Documentation checks | All 200 local prose links resolved, including bounds checks on 17 source-line references; all 363 tracked XML files parsed. Changed-file `codespell` and staged/unstaged `git diff --check` passed. |
| Preservation and review | All 40 previously changed source/config/resource files retain executable tokens and values. The only production-code changes are the three setter guards; regression coverage is a new test file. Existing test assertions, legal notices, dependencies, configuration values and repository/device safety rules remain unchanged. |

Generated output, caches, binaries and third-party material remain excluded from
documentation editing. No emulator/device access, instrumentation, package
installation/uninstall, data clearing, live extraction or physical receiver
check was performed for this implementation follow-up.

### Backup-validation follow-up — 2026-10-28

Started from clean `master` at `86adf7b59d8e2baef869eeeeffe05dccaf5a1a99`,
on branch `fix-backup-validation`. The user requested an implementation fix
with signed commits carrying `gpt-6.1-sol`, following the preceding documentation
and null-list work. The dated findings and older test/hardware results above
remain evidence of their respective revisions.

`ImportExportManager.inspect` now examines a private ZIP snapshot's central
directory. Preparation takes a fresh snapshot, rejects ambiguous critical
entries (including directory aliases), and verifies the database's size/CRC and
those of selected settings. Both phases use the same central-directory entry
selection. Ignored archive entries and omitted settings payloads are not
validated; database-only restore remains available when settings are malformed.

The new `BackupDatabaseValidator` opens only the private candidate. It checks
the SQLite header, version and integrity, removes the copy's Room identity
metadata to force schema validation, then opens it with the same migrations as
`NewPipeDatabase` and checks integrity/foreign keys after migration. A current
identity hash alone cannot authorize a malformed schema. Room's current schema
and migration paths determine compatibility; no separate maximum-version
constant or destructive fallback was added. The candidate is closed before it
leaves the working directory. Snapshot/sidecar cleanup finishes before
preferences can commit; the resulting database file retains the previous
publication/abandonment ownership rules. Startup still installs the accepted,
app-private pending file rather than revalidating it.

The test-only Robolectric 4.17 dependency exercises Room and native host SQLite
at API 26, with real file streams and Android preference persistence. Restart
and error-reporting callbacks are controlled, and the locator supplies an
isolated path. Existing archive/workflow/dialog suites explicitly inject a
no-op database validator for their text-placeholder payloads; their existing
assertions were retained. No fixture ZIP or placeholder was relabeled as a real
database. Version 2–9 candidates use exported Room schemas; the version-1 fixture
uses the inputs consumed by migration 1 → 2 because schema 1 is not exported.
These fixtures do not prove compatibility with every historical user database.

The initial 12-test baseline failed 11 cases against unchanged production code;
the current-schema round-trip control passed. The first corrected run passed
those 12 tests plus 31 existing backup tests. An expanded adversarial suite of
19 tests then caught a directory-alias gap, with its other 18 tests passing.
The entry validation now rejects that alias. The second adversarial self-review
moved working-directory cleanup ahead of commit/publication to avoid introducing
a post-publication cleanup failure that would suppress restart. An initial
compilation error was fixed by explicitly closing Room in `finally` rather
than treating `RoomDatabase` as `Closeable`.
Compatibility review also replaced the newer host-JDK `readNBytes` call with
`DataInputStream.readFully`, after inspecting the resolved desugaring helpers.
Android documents [readNBytes](https://developer.android.com/reference/java/io/InputStream#readNBytes%28int%29)
at API 33 and [readFully](https://developer.android.com/reference/java/io/DataInputStream#readFully%28byte%5B%5D%29)
at API 1; Robolectric does not establish availability of every Java API on Android 26.

Additional regressions cover central/local CRC disagreement, truncated SQLite
pages, partial migration failure, foreign-key errors, schema forgery, changed
source documents, repeated close, JSON/legacy settings, both restore choices
and private-copy migrations from versions 1–8 to 9. These are self-reviews and
host-JVM observations, not independent reviews, device SQLite evidence, process
kill tests or service/receiver observations. The preference-commit/publication
process-death gap remains; already-published pending files are not retroactively
validated by this change. See the current [restore guide](library-data.md#backup-and-restore)
for the implemented validation boundary.

| Verification | Result |
| --- | --- |
| Complete JVM stage | App: 1,063 tests, including all 19 `BackupValidationTest` cases; offline extractor: 651 with 3 skips (up-to-date); build logic: 3 (up-to-date). No failures or errors. The older-schema test loops over all versions 1–8, so those are eight migration cases within one reported test. |
| Static checks | App/extractor Checkstyle, ktlint and dependency ordering passed. The first gate stopped on three 103-character Java constructor lines; wrapping them resolved the violations without changing assertions. |
| `make ci` | Passed all five serial Gradle stages, including debug lint and debug/release assembly. Gradle reused cached/up-to-date outputs where reported. The fixture sanitizer's 12 tests passed and its scan of 685 recordings needed no replacements. |
| Debug lint | Passed with 59 warnings and no errors. This is static analysis, not device evidence. |
| Android test APK | `:app:assembleDebugAndroidTest` passed, compiling the updated Java/Kotlin test sources. Its dry-run graph contained 81 build tasks and no install/uninstall/instrumentation task. The APK was not installed or run. |
| Release dependency boundary | `:app:dependencyInsight --dependency robolectric --configuration releaseRuntimeClasspath` reported no matching dependency. Robolectric is declared only for JVM tests. The added schema resources belong to that test source set; instrumentation retains its existing schema assets. |
| Documentation and preservation | All 202 local prose links resolved, including bounds checks on 14 local source-line references. Changed-file spelling and `git diff --check` passed. Existing test executable tokens/assertions were unchanged apart from explicit validator injection; existing version-catalog entries were unchanged. |

All 13 changed source/configuration/test files retained their verified hashes
through the final review; the remaining changes are the current guides, fixture
README and this dated record. No device/emulator access, instrumentation,
installation/uninstall, app-data clearing, live-service extraction or physical
receiver check was performed. Compiler deprecation warnings remain. The changes
are scoped to new ZIP restore preparation and its documentation/testing; other
open audit findings and UI-text proposals remain separate.

### Restore crash-recovery follow-up — 2026-10-28

Started from clean `master` at `cd4deb21c43bd3bb2e6c62d92c8b008bef89fd39`,
on branch `fix-restore-crash-recovery`. The user requested durable recovery for
paired database/settings activation, interrupted/fault tests, and validation of
pending databases before replacement or sidecar deletion. The preceding
validation follow-up's process-death gap and lack of startup revalidation describe
that earlier revision; the [current restore guide](library-data.md#backup-and-restore)
describes this follow-up. Earlier failures and hardware results remain historical.

Two new regression tests failed against unchanged production code: queuing a
restore changed preferences while the old database remained live, and a failed
pending-file replacement removed the old WAL. The new protocol publishes the
database and a typed preference intent together without changing live settings.
Main-process attach revalidates a private copy before providers initialize,
snapshots the old database family and settings, then installs the replacement
before removing sidecars. A checked activation record supports repeatable
rollback after interruption; a completion record prevents replay after commit.
Recovery failure stops startup instead of allowing an unresolved pair to open.
Phoenix/ACRA helper processes skip activation and main application initialization.

Self-review traced interruption before publication, during validation/snapshot,
after database replacement and each sidecar deletion, around preference commit,
during rollback and around completion/retirement. It also checked damaged undo
and completion records, post-commit failures, initially absent databases, legacy
pending files and preference numeric/set preservation. Fresh child JVMs use real
host filesystem operations and `Runtime.halt`, with controlled text databases and
disk preferences. Separate Robolectric API 26 tests use native host SQLite,
Android preferences and production `App.attachBaseContext`, suppressing normal
`onCreate` and ACRA initialization. They do not run Android providers, Android
process death, actual power loss or physical-device storage. These are adversarial
self-reviews, not independent reviews.

Successful transactions are retired before garbage collection. Rejected or
rolled-back snapshots remain quarantined; interrupted unpublished/install
resources may remain. There is no cleanup UI. The protocol uses file forcing,
atomic moves, directory syncs and cooperating-process locks, but this host testing
does not certify arbitrary device storage/power-loss durability. Old raw pending
files cannot reconstruct pre-import settings that older releases never saved.

The helper-process review found that extractor settings initialized before the
helper early return and could write preferences during main-process recovery.
That initialization now follows the return, with a regression for the ACRA path.
The expanded native WAL fixture initially failed before activation because
`execSQL` cannot execute its result-producing PRAGMA; using `rawQuery` fixes the
fixture setup. Its main-file-only control checks that the committed extra row
requires the WAL, and recovery checks the saved family's bytes and both rows.
The initial complete JVM run reported 1,094 tests with only that setup failure;
the preceding static gate also caught a 101-character test line, which was wrapped.

| Verification | Result |
| --- | --- |
| JVM stage | App: 1,094 tests, including 19 core recovery tests, 10 native startup tests and 2 workflow regressions. Offline extractor: 651 with 3 skips (up-to-date); build logic: 3 (up-to-date). No failures or errors in the final complete JVM run. |
| Static checks | App/extractor Checkstyle, ktlint and dependency ordering passed. |
| Debug lint | Passed with 59 warnings and no errors. This is static analysis. |
| Host evidence | Fresh child JVMs halt without cleanup on real host files; their database validator and preference store are controlled. Robolectric uses API 26 and native host SQLite, including a committed WAL-only row verified absent from a main-file-only copy. Neither path is Android process-death or physical-storage evidence. |
| `make ci` | Passed all five serial Gradle stages, including debug lint and debug/release assembly. Gradle reused cached/up-to-date outputs where reported. The fixture sanitizer's 12 tests passed and all 685 recordings needed no replacements. |
| Android test APK | `:app:assembleDebugAndroidTest` passed, compiling the adapted instrumentation sources. Its inspected dry-run contained 81 build tasks and no installation, uninstall, device-data or instrumentation execution task. The APK was not installed or run. |
| Documentation | All 205 local Markdown links resolved across 21 tracked Markdown files, including bounds checks on 13 local source-line references. Changed-file spelling and `git diff --check` passed. The historical pending-installer link is pinned to its original revision after replacing that Java helper with the Kotlin adapter. |
| Preservation | All source/test hashes remained unchanged through the final gate and APK build. Changes cover restore preparation/publication, startup activation/rollback, preferences, database sidecars, process roles, their tests and current guides. Dependencies, version/application IDs, signing configuration, license notices and repository/device safety rules are unchanged. |

No device/emulator access, instrumentation execution, package installation or
uninstall, app-data clearing, live-service extraction or physical-receiver check
was performed. Host results do not certify Android process scheduling or arbitrary
power-loss/storage durability. Startup validation/copying is synchronous, and
retained failed snapshots have no cleanup UI. Other open audit findings and
unrelated UI-text proposals remain separate.


### Restore emulator validation — 2026-10-28

Started from clean `master` at `ba1c45014d96e0de52d06184e996e19781a56a1f`,
on branch `test-restore-emulator-recovery`. The user requested Android CLI device
creation and Android checks of both restore choices, interrupted activation and
rollback, and preservation of existing database/WAL data. Earlier host-only
results above remain evidence of their own runs.

Android CLI 1.1.16717390 created its `medium_phone` preset with an API 36 Play
Store image. That new AVD timed out during first boot after 300 seconds, before
PVCPipe installation. The CLI create command exposes presets without an API-level
option. A separate `pvcpipe_restore_api26_20261028` AVD was therefore created with
the SDK's installed API 26 default x86_64 image and software rendering, then
started using Android CLI. It booted as `emulator-5554`, Android 8.0/API 26,
fingerprint `Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys`.
A connected physical Pixel was left untouched. All installs, UI inspection,
instrumentation and process signals targeted the disposable emulator explicitly.

The existing 16 `BackupRestoreDialogTest` cases passed on that emulator. Their
archive/database payloads remain controlled text fixtures. The new host-driven
suite uses real Room/SQLite databases and Android preferences. A main-file-only
control must omit a committed `wal-only` row; a copy with the WAL must contain it.
Both source choices use JSON settings archives, the real settings dialog/system
document picker and production ProcessPhoenix restart, driven by Android CLI
outside target instrumentation. Fault phases use production `RestoreTransaction`
with a test-only checkpoint, then host SIGKILL. Verification uses a fresh normal
`DebugApp`, whose inherited `App.attachBaseContext` consumes the recovery journal.
The fault gate does not intercept `App.attachBaseContext` itself.

The optional `-Ppvcpipe.restoreFixture=true` build changes only the test application
ID/runner, producing a separately installed, emulator-guarded fixture APK. The
default runner and production application IDs/signing remain unchanged. The host
checks all three installed APK hashes, the exact AVD name and per-run nonce;
fixture phases intentionally replace the disposable app's default database and
preferences. Ordinary discovery skips the phase driver. No Gradle-managed device
task, package uninstall or `pm clear` was used.

Initial harness attempts exposed three test assumptions: `adb exec-out` did not
propagate missing-file status for the process-exit probe; the normal debug
application is `DebugApp`, not the exact base `App`; and ProcessPhoenix schedules
its helper asynchronously, so finishing instrumentation immediately after the
restore callback could cancel the restart. The driver now uses checked shell-v2
reads, verifies the production App subclass, keeps nonterminal phases alive, and
requires a new app PID with resumed `MainActivity` for the two choice cases.
A subsequent run observed a new process but Android instrumentation cleanup
force-finished its new MainActivity. The UI restore cases now run outside target
instrumentation, with instrumentation used only for terminal verification.
Those failed attempts were harness failures, not successful matrix runs. Normal
startup then consumed the earlier staged database-only request, and its terminal
data/settings verification passed before the complete rerun.

The final complete run (`/tmp/pvcpipe-restore-device-run5`) passed all 11 scenarios,
with 20 explicit host SIGKILLs. The UI database-only restore changed PID 6336 →
6502; restore with settings changed 6769 → 6896. Both new processes resumed the
real MainActivity before fresh terminal instrumentation verified their data.
Remembered SAF document URIs reopened the selected archive byte-for-byte after
restart. Per-phase JSON, instrumentation/CLI logs, UI layouts, APK hashes and the
summary remain in that evidence directory. A separate real-archive dialog capture
was visually inspected at `/tmp/pvcpipe-restore-api26-real-dialog.png`.

| Android scenario | Observed result |
| --- | --- |
| Both UI restore choices | Passed real document selection, overwrite confirmation, the selected restore action, production restart and fresh-process database/settings assertions. |
| Database-only staging | Passed preservation of preference edits made after publication and before activation. |
| Modified queued DB and invalid legacy pending DB | Both rejected; original main/WAL bytes and tested typed preferences preserved. |
| Interrupted activation | Kills after main-file replacement, WAL removal and preference commit all recovered the original main/WAL bytes, committed WAL-only row and tested preferences. |
| Interrupted rollback | Kills after restoring the old main file and after restoring the WAL both recovered correctly on another startup. The host accounts for whether the fixture had a rollback journal and checks restored WAL hashes at the gate. |
| Completed restore with later edits | Later database rows and preference edits survived completion-record cleanup without replay. |

| Local verification | Result |
| --- | --- |
| `make ci` | Passed all five serial Gradle stages, including debug/release assembly. Sanitizer: 12 tests and 685 recordings, no replacements. JVM reports reused up-to-date results: app 1,094; offline extractor 651 with 3 skips; build logic 3. No failures/errors. |
| Final harness checks | After the UI-harness changes, normal and fixture test APK builds passed. Final app Checkstyle, ktlint and debug lint passed; lint reported 59 warnings and no errors. Python compilation and the physical-target rejection probe passed. |
| Preservation | The four code/configuration hashes remained unchanged through the final matrix/checks. Production source files, application SDK/version/IDs, dependency versions, signing and repository/device safety rules are unchanged. Only the explicit test-runner option adds a separate test ID. |

All 210 local Markdown links and 13 source-line bounds passed across 21 tracked
Markdown files. Changed-file spelling and final diff whitespace checks passed.
These are API 26 x86_64 emulator results with small synthetic current-schema
SQLite archives. Historical-schema migration tests remain host-JVM evidence;
legacy-settings UI tests retain their controlled payloads. No physical-device,
API 36 app-runtime or power-loss result is claimed. Service responses were not
used as validation evidence.
The disposable AVD remains available for reinspection.

### Bookmark-save ownership follow-up — 2026-10-28

Started from clean `master` at `6b6d3a786bd514e25f030cf452c033ffd098c1a6`,
on branch `fix-bookmark-save-ownership`. This separately authorized implementation
follow-up addresses the bookmark dirty-flag finding above and preserves failed
bookmark edits for retry. Earlier audit and restore results remain historical.
Current behavior is described in [Library data](library-data.md#bookmarks).

Three controlled regressions failed against the original save implementation:
an older completion cleared a newer revision, failed deletions were removed
before persistence, and saving mutated adapter display indices before success.
The first fixture attempt also encountered an `ErrorInfo` logcat/App initializer
error; isolating report construction made all three failures assertion failures.
These are method-level reproductions with deferred persistence doubles, not
device or actual database scheduling results.

`BookmarkSaveManager` freezes the complete typed-ID order and deletion batch,
then applies both tables in one Room transaction on the existing shared local
playlist write queue. Its cached operation starts on first subscription and
survives observer disposal. Targeted index updates preserve other metadata and
do not recreate deleted rows. Completion acknowledges only the captured revision
and deletion batch for the same saver owner. Errors retain the draft instead of
resetting the adapter; accepted writes and callbacks survive view disposal.

Adversarial self-review covered revision interleavings, deletion batches with
equal IDs in different tables, replacement saver owners, repeated observation,
cross-manager admission, metadata changes, rollback after either table fails,
failed-save retries, database emissions, and view destruction/recreation.
It is self-review, not an independent reviewer or real-device observation.
Native host SQLite tests use the production generated Room queries and aborting
triggers. Fragment tests call production methods with controlled adapters,
rendering, persistence and error-reporting side effects.

The lifecycle review found another failure in the first correction: destroying
a replacement view before its first database emission replaced the retained
draft with that unloaded adapter's empty list. A new regression failed with
expected order `[2, 1]` versus `[]`; snapshotting only a loaded view corrects it.
The same run's detached-error test initially lacked the base fragment's activity
for preference-listener cleanup. Supplying a Robolectric activity fixes that
fixture setup without bypassing production destruction or error routing.
The next attempt's static verification called Kotlin's unmocked companion instead
of Java's static bridge, causing report-resource and subsequent mock-cleanup
failures. Verification now invokes the same static bridge as production, and
fixture cleanup also handles partial setup. Those attempts are not passing runs.
A subsequent fixture compile rejected an unnecessary type argument on the
non-generic `RuntimeEnvironment.getApplication()` API; removing it corrects the
test compilation without changing production code.

The first full gate passed all 1,118 app JVM tests and debug assembly. Lint
reported 60 warnings, including the intentionally unretained save observer.
That observer must outlive view disposal to acknowledge completion. The run was
stopped during release assembly to add a method-scoped `CheckResult` suppression
with its lifetime rationale, then the complete gate was restarted. This does
not suppress other lint checks or the existing thumbnail observer's warning.

A failed draft is retained only within the live fragment instance, including
view recreation. It is not a persistent outbox for process death or full fragment
destruction. Unrelated remote-playlist metadata operations retain their existing
scheduling; this correction does not establish a global order for those writes.

| Verification | Result |
| --- | --- |
| JVM stage | Fresh app run: 1,118 tests, no failures/errors/skips, including 16 controlled fragment-method tests and 8 Room/native host SQLite bookmark tests. Offline extractor: 651 tests with 3 skips; build logic: 3 tests. The latter two reused up-to-date results. |
| Static checks | App/extractor Checkstyle, ktlint and dependency ordering passed. |
| Documentation | All 212 local Markdown paths/heading anchors and 12 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. The historical bookmark finding is pinned to the pre-correction revision. |
| Debug lint | Passed with 59 warnings and no errors, matching the preceding baseline count. The new save observer's intentional lifetime is documented and annotated locally. |
| `make ci` | Passed all five serial Gradle stages, including debug/release assembly. The sanitizer's 12 tests passed; all 685 recorded fixtures needed no replacements. Cached/up-to-date outputs were reused where Gradle reported them. |
| Android test APK | `:app:assembleDebugAndroidTest` passed, reusing up-to-date test compilation/packaging where reported. Its dry-run contained 81 build tasks and no installation, uninstall, device-data or instrumentation execution task. The APK was not installed or run. |
| Preservation | All seven source/test hashes remained unchanged through the final gate and APK build and matched their staged contents. The diff covers bookmark persistence/ownership, two DAO order queries, access to the existing write scheduler, two new test files and four documentation files. No schema, dependency, version, signing or device/repository safety configuration changed. |

These are host-JVM and build results. This follow-up accessed no device or
emulator and ran no instrumentation or live-service checks. The restore emulator
results above remain evidence of their stated revision and scenarios. Other
open audit findings and UI-text proposals remain separate.

### Bookmark emulator validation — 2026-10-28

Started from clean `master` at `9ceed01c296010f314255c00afa1bfdbe655875c`,
on branch `test-bookmark-emulator-ownership`. This follow-up adds Android
validation for the bookmark correction above; the earlier host results remain
evidence of their own run. Android CLI started the existing disposable
`pvcpipe_restore_api26_20261028` AVD as `emulator-5554`, Android 8.0/API 26 x86_64.
The connected physical Pixel was not accessed.

`BookmarkEmulatorTest` launches the actual `MainActivity` and bookmark fragment,
uses touch drags, long presses and delete dialogs, and observes real
`FragmentManager` callbacks. It temporarily swaps the process's database
singleton for an in-memory Room database with real Android SQLite, restoring the
singleton, three preference overrides and image-quality state afterwards.
Test-only latches hold the existing production write scheduler; SQL triggers
abort either table's update. Metadata fixtures use local PNG files and synthetic
URLs without loading a service. Production sources, runners and build values
are unchanged.

The host refuses physical-device serials, verifies the exact AVD, emulator
properties and both installed APK hashes, then writes a private nonce required
by the fixture. Signers matched before updates: Android CLI installed the app
with replacement enabled, and targeted `adb install -r -t` updated only the test
APK. Direct instrumentation selects only the guarded class. No Gradle-managed
device task, package uninstall or app-data clearing was used.

Development attempts were not passing matrix runs. The first fixture wrote an
integer to the service-name preference and crashed during activity creation.
That unnecessary override was removed; only the integer introduced on the
disposable emulator was repaired. The next probe was covered by the queued crash
report from that failure. A subsequent upward drag stopped at the target's drop
threshold; crossing the row center and allowing the drop animation to finish
corrects the gesture. Both drags then passed, but an accessibility action did not
activate the delete menu; the fixture now taps its screen coordinates. An
attempted accessibility handoff also failed to compile because `destroy` is not
in the public SDK; the fixture uses `FLAG_DONT_USE_ACCESSIBILITY` instead.

The focused drag/delete probe then passed. The first complete seven-case run
passed five cases, including both SQL rollback/retry directions and both
view-recreation paths. It failed two fixture checks: an immediate pop had removed
the fragment but its exit effect had not yet delivered destruction, and the
thumbnail helper attempted to draw a hardware bitmap on a software canvas.
The checks now wait for the actual destruction state and read a software copy
of the bitmap loaded into the real ImageView. The lifecycle expectation and
thumbnail color assertions remain required.

The next run passed six cases, including bitmap/name preservation; its screenshot
was visually inspected. Waiting on the removed object's current lifecycle state
still failed: the APK uses AndroidX Fragment 1.8.9, whose `detach` path calls
`Fragment.initState` and replaces its lifecycle registry after destruction.
Recursive lifecycle callbacks also include child fragments. The final witness
selects only the top-level bookmark owner and requires its destruction callback
and cleared saver before creating the replacement. Android CLI layout requests
returned instrumentation-server errors; screen capture worked and was used as
the visual fallback.

The final complete run at `/tmp/pvcpipe-bookmark-emulator-run4` passed all seven
cases (`OK (7 tests)`, 86.052 seconds including the visual pause). Per-case JSON,
the runner output, APK hashes and the final summary remain there. Both tested
APKs were copied to `/tmp/pvcpipe-bookmark-emulator-final-apks`. The final
`metadata-screen.png` was visually inspected before releasing the test: it shows
`Remote newer`, `Beta`, `Alpha newer` and `Gamma`, with the updated blue thumbnails
and remote count 91. Layout-server failures above are not counted as passing
layout checks.

| Emulator scenario | Observed result |
| --- | --- |
| Rapid drag/delete during pending writes | An older write completed while the newer draft stayed dirty and retained its pending deletion. Releasing the newer write persisted the complete new order and deletion. |
| Accepted save without a view | The write and acknowledgment completed while the actual fragment had no view; reattachment created a different view and loaded the saved order. |
| Pending save and view recreation | The same fragment's replacement view retained the newer draft through an older acknowledgment, then saved it. |
| Replacement fragment owner | Actual destruction cleared the old saver; a new fragment at the same revision stayed dirty after the old owner's completion and then persisted its own order. |
| Remote-table SQL failure | The local deletion and order changes rolled back. The snackbar left the draft intact through view recreation; a new save succeeded after removing the trigger. |
| Local-table SQL failure | A remote deletion and earlier remote index update rolled back. Retry removed only the intended remote row, preserving the local row with the same numeric ID. |
| Concurrent metadata and missing row | A stale order save preserved newer local/remote names, thumbnail metadata and remote count, and did not recreate an externally removed remote bookmark. Reload displayed the newer names and blue image targets. |
| Default database isolation | Main-file and empty journal hashes matched before/after. WAL and SHM files were absent at both boundaries; this run does not verify active-WAL preservation. The fixture's writes used its in-memory database. |

These are API 26 x86_64 emulator observations with controlled queue timing,
injected SQL failures and synthetic metadata. They do not establish physical
device scheduling, API 37 runtime behavior, live-service metadata update policy,
process-death draft recovery or power-loss durability. The prior restore/WAL
results remain separate evidence of their own scenarios.

| Local verification | Result |
| --- | --- |
| `make ci` | Passed all five serial Gradle stages, including debug/release assembly. Sanitizer: 12 tests, 685 recordings, no replacements. JVM stages reused up-to-date results: app 1,118; offline extractor 651 with 3 skips; build logic 3. No failures/errors. |
| Static analysis | App/extractor Checkstyle, ktlint and dependency ordering passed. Debug lint passed with 59 warnings and no errors, matching the preceding baseline count. |
| Instrumentation build | The inspected initial dry-run had 103 formatting/build tasks and no device task. Debug app/test APK builds passed; the final matrix ran direct instrumentation, independently of Gradle. |
| Host tooling | Python compilation and a physical-serial rejection probe passed. The driver required seven successful case records and the complete JUnit success result; earlier partial runs were not accepted. |
| Documentation/review | All 217 local Markdown links and 12 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. Adversarial self-review covered lifecycle ownership, queue gates, both rollback directions, target/nonce guards, result parsing, setup/teardown and the actual Fragment 1.8.9 implementation; this was not an independent review. |
| Preservation | Both fixture/driver source hashes remained unchanged through the passing matrix and final gate. Production source, dependencies, SDK/version/application IDs, signing, runners and repository/device safety rules are unchanged. The disposable emulator was stopped after testing; its AVD remains available. |

### Thumbnail-retention follow-up — 2026-10-28

Started from clean `master` at `0c7df4bd8210883f22c0e7d3465808029a55328d`,
on branch `fix-playlist-thumbnail-retention`. This separately authorized
implementation follow-up addresses the permanent-thumbnail cleanup finding.
Current behavior is described in [Library data](library-data.md#local-playlists).
Earlier SQL probes and emulator observations remain evidence of their own runs.

The new `StreamOrphanRetentionTest` initially ran against unchanged production
code: 10 tests, 7 assertion failures, no fixture errors. Removing a stream from
contents while keeping it as a permanent thumbnail made it disappear during
orphan cleanup. Feed clear/pruning, shared covers, automatic references and
changing a cover reproduced the same missing-reference problem. The transaction
failure case rolled back correctly but its retry then deleted the cover. The
three existing-behavior controls passed: collection after unsetting an empty or
nonempty playlist's cover, and collection despite a matching playlist numeric ID
with the default thumbnail sentinel. The failing XML and log were preserved at
`/tmp/pvcpipe-thumbnail-baseline.xml` and `/tmp/pvcpipe-thumbnail-baseline.log`.

`StreamDAO.deleteOrphans` now excludes the set of non-null stored playlist
thumbnail IDs, in addition to history, playlist contents and feed references.
Both automatic and permanent references count; no permanent-flag condition is
needed to determine whether a row is referenced. The non-null filter avoids
`NOT IN` null semantics, and the independent ID subquery avoids a correlated
playlist scan for every candidate stream. No schema or foreign key changed.
The query prevents future cleanup loss; it cannot recreate already deleted
metadata or validate arbitrary thumbnail assignments.

All 10 focused Room/native host SQLite tests passed after that change. They
exercise actual playlist mutations and both production feed cleanup paths,
unsetting to automatic/default covers, changing/shared references, deletion of
the last playlist, ordinary orphan removal and transaction failure/retry.
The host manager uses a controlled write scheduler; these results are not Android
device or rendered-image evidence.

The guarded bookmark emulator suite gains a separate cleanup case. It pins a
local image, removes its stream from playlist contents and calls the real
`FeedDatabaseManager.clear`. It checks the retained stream/metadata and the
real ImageView's red image. Unsetting to the default makes that old stream
collectible. Appending and pinning a replacement checks retention again; deleting
the playlist through the actual dialog then makes the replacement collectible,
while the other playlist's stream remains. The host requires all eight case
records and the complete eight-test JUnit result; the seven-case results above
remain historical.

The completed run at `/tmp/pvcpipe-thumbnail-emulator-run1` passed all eight
cases (`OK (8 tests)`, 153.555 seconds including the final visual pause).
Android CLI used the disposable `pvcpipe_restore_api26_20261028` AVD as
`emulator-5554`, Android 8.0/API 26 x86_64. Both installed signers were checked
before replacement installs. The new cleanup case passed its stream, metadata,
red-image, unsetting and final-playlist-deletion assertions; the seven prior
bookmark cases also passed. The final metadata-case screenshot was visually
inspected using Android CLI's screen capture. That screenshot shows the separate
blue-thumbnail scenario, not the intermediate red-cover cleanup state.

Per-case JSON, runner output, APK hashes, the screenshot and the default-database
comparison remain in that run directory. Both tested APKs were preserved at
`/tmp/pvcpipe-thumbnail-tested-apks`. The app's default main-file and empty-journal
hashes were unchanged; WAL and SHM files were absent before and after. Fixture
writes used the in-memory database. This does not add active-WAL or power-loss
evidence. The physical Pixel was not accessed, and no package was uninstalled or
app data cleared.

Caller review also traced `HistoryRecordManager.removeOrphanedRecords`, which
passes `streamTable::deleteOrphans` to an I/O `Single`. That entry point shares
the corrected query. The history settings screen still launches state deletion,
history deletion and orphan cleanup as separate subscriptions; this change adds
no atomic combined-success guarantee. This was source inspection, not another
Android history-settings journey.

The first full gate passed 1,128 app JVM tests and debug lint, but lint reported
66 warnings: seven new fixture calls ignored the return value of `blockingGet`.
The fixtures now require one affected row or one inserted entry, alongside the
existing database assertions. No production logic changed for this adjustment.
Those additional acceptance assertions were followed by another build, complete
emulator run and final gate; the earlier eight-case run remains recorded above.

The final fixture build passed all 1,128 app JVM tests (zero failures, errors or
skips), including the 10 retention regressions, and rebuilt the Android test APK.
The second complete emulator run at `/tmp/pvcpipe-thumbnail-emulator-run2` passed
all eight cases in 60.424 seconds without a visual pause. Its app APK hash matches
the first run; the updated test APK passed signer verification before replacement
installation. The driver verified both installed APK hashes and all eight fresh
case records. The default main file and empty journal again had unchanged hashes;
WAL/SHM were absent. No second screenshot or layout inspection was performed.
This is synthetic API 26 emulator evidence, not physical-device, service or
power-loss evidence. The disposable AVD was stopped after the run.

Adversarial self-review checked the complete query diff and its callers, shared
and released thumbnail references, genuine orphans, sentinel/ID confusion, and
feed transaction failure/retry. The existing seven Android cases and their
assertions remain intact. The new cases use real Room queries and managers;
the host tests control scheduling, and the emulator uses synthetic images and
an isolated in-memory database. No schema, dependency, backup/restore behavior,
UI string, safety rule or historical test result changed. No additional defect
was identified within this correction's scope.

| Final verification | Result |
| --- | --- |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-thumbnail-final-ci.log`. All 12 sanitizer tests passed and all 685 recorded fixtures passed inspection. Checkstyle, ktlint, dependency ordering, debug lint and both APK builds passed. |
| JVM evidence | The final fixture build reran all 1,128 app tests successfully; its log is `/tmp/pvcpipe-thumbnail-final-fixtures.log`. The final gate reused those results, the 651-test offline extractor report (3 skipped) and the 3-test build-logic report. None reported failures or errors. |
| Lint | Zero errors and 59 existing warnings, down from the first gate's 66 after the seven result checks were added. No lint issue points to the three changed Kotlin files. |
| Emulator | Both complete eight-case runs passed. The final tested APKs are preserved under `/tmp/pvcpipe-thumbnail-emulator-run2/tested-apks`; the driver recorded their installed hashes. The final run adds no active-WAL, layout or hardware evidence. |
| Documentation and scope | All 218 local Markdown links and 11 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling, Python compilation and diff whitespace checks passed. The four implementation/test/driver source hashes remained unchanged through the final build, emulator run and gate. Review was adversarial self-review, not independent review. |

### SAF close follow-up — 2026-10-29

Started on 2026-10-28 from clean `master` at
`3115e79a2c71a73db910a4797bd858c10ff1f0d3`, on branch
`fix-saf-stream-cleanup`. This separately authorized correction addresses the
remaining close-failure finding. Current behavior is described in
[Downloads](download-preparation.md#saf-stream-cleanup).

`StoredFileHelper.getStream` constructs the adapter for document URIs. Its
callers include download writers, initialization, checksums, truncation and
post-processing. `SharpStream.close` has no checked-exception result, and some
callers use `isClosed` to stop using the stream. The closed flag remains a
logical close-attempt flag; it does not certify successful resource release.

The installed Android 36.1 sources distinguish the borrowed `FileInputStream`
and `FileOutputStream` wrappers from the owning `ParcelFileDescriptor`. The
output channel closes through its parent output stream. This source inspection
alone does not establish behavior on every supported Android version/provider.

Before changing production code, all eight controlled-resource JVM regressions
ran: six failed because later closes were skipped; normal/repeated close and a
failure at the final descriptor passed. These were missing-close verification
failures, not fixture errors. The original log and XML remain at
`/tmp/pvcpipe-saf-baseline.log` and `/tmp/pvcpipe-saf-baseline.xml`.

The correction closes the channel in a try-with-resources body, declaring the
borrowed streams and owner in reverse order so cleanup still attempts channel,
input, output, then owning descriptor.
I/O failures remain logged rather than thrown, with subsequent failures
suppressed on the first. An unchecked primary failure propagates after the
remaining cleanup attempts. Construction, descriptor duplication, file access,
capability reporting, the closed flag and caller control flow are unchanged.

The emulator fixture uses only unique files below the app's `share_preview`
cache path and its local `FileProvider`. It has no database/preference mutation
and does not launch an activity. Ordinary discovery skips it. Host opt-in also
requires emulator hardware, the debug package, and a matching nonce marker.
Build the normal `:app:assembleDebug :app:assembleDebugAndroidTest` APKs.
After verifying the disposable AVD, installed signers and APK hashes as described
in [Building](building.md#disposable-bookmark-validation), its direct invocation
is:

```sh
saf_nonce=$(python3 -c 'import uuid; print(uuid.uuid4().hex)')
adb -s emulator-5554 shell run-as org.seventyfiveohmantenna.pvcpipe.debug mkdir -p files
printf '%s' "$saf_nonce" | adb -s emulator-5554 shell run-as org.seventyfiveohmantenna.pvcpipe.debug tee files/saf-emulator-allow
adb -s emulator-5554 shell am instrument -w -r \
  -e class us.shandian.giga.io.FileStreamSAFEmulatorTest \
  -e safDisposable true -e safNonce "$saf_nonce" \
  org.seventyfiveohmantenna.pvcpipe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The final focused run passed all eight JVM regressions, Checkstyle, debug APK
assembly and Android test APK assembly; log
`/tmp/pvcpipe-saf-focused-build-final.log`. An earlier empty try-with-resources
body passed the tests but failed Checkstyle's `EmptyBlock` rule; putting the
channel close in the body resolved that failure without changing style rules.

Android CLI started the disposable `pvcpipe_restore_api26_20261028` AVD as
`emulator-5554` (Android 8.0/API 26 x86_64). The host checked its AVD, emulator
property and boot state, then compared installed/built APK signers before
replacement installs. The installed baseline app matched the preserved APK from
the preceding thumbnail-retention run. Only the test APK was replaced first.

The same native fixture first ran against that old app: two tests, one failure
at `Owning descriptor must be released`. The injected input-close failure left
the real owning descriptor valid after adapter close; teardown explicitly
closed it. The normal case passed. After updating the app APK, both cases passed
(`OK (2 tests)`, 0.033 seconds of reported test time). Both runs verified
read/write/seek/truncate; the passing run also verified descriptor invalidation,
unchanged file bytes and another descriptor surviving repeated close.

The logs, commands, nonce, selected target and installed APK hashes remain in
`/tmp/pvcpipe-saf-native-baseline` and `/tmp/pvcpipe-saf-native-fixed`. The host
required two successful test status results for the passing run, with no skips
or ignored tests. Default database main-file and empty-journal hashes were
unchanged in both runs; WAL/SHM were absent. No activity was launched, no package
was uninstalled and no app data was cleared. This is a local `FileProvider` with
a controlled input-close fault, not a live SAF provider, physical-device or
provider-durability result. No UI/layout/screenshots were evaluated.

Adversarial self-review traced resource aliasing, close order, logical state,
single/multiple/unchecked failures, repeated close, callers and fixture teardown.
A second pass checked opt-in guards, exact target/APK selection, complete
instrumentation results and contradictory documentation. The source correction
changes only close control flow and explanatory comments; no schema, dependency,
SDK/application ID, signing, runner, UI string or safety rule changed. These are
self-reviews, not independent review or evidence for other providers/platforms.

| Final verification | Result |
| --- | --- |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-saf-ci.log`. All 12 sanitizer tests and all 685 recorded fixtures passed. Checkstyle, ktlint, dependency ordering, debug lint and debug/release APK builds passed. |
| JVM tests | All 1,136 app tests ran successfully, with no failures, errors or skips. The gate reused the unchanged offline extractor report (651 tests, 3 skipped) and build-logic report (3 tests); neither reported failures or errors. |
| Lint | Zero errors and 59 existing warnings. No issue points to the three changed Java files. |
| Emulator | The old app failed the injected-fault descriptor-release assertion; both cases passed with the corrected app and identical test APK. The tested APKs are preserved in each native run directory's `tested-apks` subdirectory. The disposable emulator was stopped; its AVD remains available. |
| Documentation and preservation | All 221 local Markdown links and 10 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. A second search found no contradictory current cleanup description. All three implementation/test source hashes matched through the passing build, native run and release gate. |

### Parser nullability follow-up — 2026-10-29

Started from clean `master` at `ce6f17429af09ee9478631e04c3eb3169deb9e4d`,
on branch `fix-parser-nullability`. The [extractor guide](../pvcpipe-extractor/README.md#regex-helpers)
now describes the group helpers' return contract and fallback differences.

The four `matchGroup`/`matchGroup1` overloads previously advertised non-null
results; `matchGroup1MultiplePatterns` had no return annotation. All five now
use `javax.annotation.Nullable`. Their bodies, matching patterns, exception
handling and callers are unchanged. An unmatched optional capture remains null,
a participating empty capture remains `""`, and group zero returns the whole
matched substring. Missing matches still throw `RegexException`; invalid group
numbers after a match still throw `IndexOutOfBoundsException`. The matching
operation remains `find()`, not whole-input matching.

Caller review covered all 24 external production call sites in nine files,
including multiline calls and a search for static imports/method references.
No app-source or Kotlin caller was found. The protected timestamp helper's
three supplied service patterns were also traced.

| Caller area | Null handling inspected |
| --- | --- |
| `Utils` (4 calls) | Numeric count/group 1 and multiplier/group 2 are required captures. Google's redirect capture is required. Both regex-array overloads already retry a null capture and throw if no non-null result exists; an empty capture is accepted. |
| BitChute (1 call) | The requested media-URL capture is required on a successful match. Parsing errors retain the existing unavailable-content path. |
| YouTube (12 calls) | JavaScript URL/hash, signature, throttling and view-count patterns require the groups they read. Function arguments can be empty but their capture participates. Dynamic function names are quoted when building the relevant patterns. |
| SoundCloud (2 calls) | Client-ID and direct API-URL branch captures participate on every successful match. An empty client-ID capture is possible but is not null; this correction does not change discovery or ID selection. |
| Timestamp helper (5 calls) | YouTube, Odysee and SoundCloud supply required outer captures. The four fixed inner patterns require digit captures. Existing absence/error behavior is unchanged. |

`ParserTest` adds 12 offline regressions using real `Pattern`/`Matcher` calls
and the actual regex-array helper, without a downloader. All 12 passed before
the annotation change and after it: these are behavior-preservation checks,
not evidence of a former runtime implementation failure. They distinguish
optional and empty captures, group zero, substring matching, missing matches,
invalid groups/patterns, empty pattern arrays, first-pattern null results and
the regex-array caller's null retry. The baseline log/XML remain at
`/tmp/pvcpipe-parser-baseline.log` and `/tmp/pvcpipe-parser-baseline.xml`;
the focused correction run is `/tmp/pvcpipe-parser-focused.log`.

A separate Kotlin 2.1.0 consumer probe on JDK 21 used `-Xjsr305=strict`.
Against the baseline classes, five unsafe dereferences compiled and each threw
`NullPointerException` for an unmatched capture. Against the corrected classes,
the same source failed compilation with five nullable-receiver errors. A
null-aware consumer compiled and handled all five null results successfully.
Sources, compiler output and run logs remain in `/tmp/pvcpipe-parser-contract`.
This demonstrates a source-contract change for Kotlin consumers; it is not a
claim that existing service calls were crashing or a compiler-version matrix.

`javap -c -s -p` output for `Parser` and its `RegexException` class was identical
before/after after normalizing constant-pool indices. Executable instructions
and method descriptors are preserved; exported annotation metadata changes.
Javadoc passed on the final source (`/tmp/pvcpipe-parser-final-javadoc.log`),
with 100 reported warnings in untouched sources and none reported for `Parser`.
These were documentation-coverage warnings, not a warning-free Javadoc result.

Separate caller finding at the parser checkpoint: the [former `SoundcloudStreamLinkHandlerFactory.getId`](https://github.com/75ohmantenna/pvcpipe/blob/3b0947d7728bfd2fdbd2e57b9992003ec3d56160/pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/soundcloud/linkHandler/SoundcloudStreamLinkHandlerFactory.java#L47)
used group 1 of its API-URL pattern, which captures the resource kind rather
than group 2's identifier. A standalone local probe returned `tracks` for
`getId("https://api-v2.soundcloud.com/tracks/123/")`. The same probe found
`acceptUrl` false and `fromUrl` rejecting that URL before ID extraction.
This was a suspected direct-call ID-selection defect, not a nullability issue
or evidence of an affected normal app flow. No HTTP request was made. The
branch was unchanged by the parser correction; the probe source/output remain in
`/tmp/pvcpipe-parser-contract/SoundcloudIdProbe.java` and
`/tmp/pvcpipe-parser-contract/soundcloud-id-probe.log`.
The separately authorized [SoundCloud API-ID follow-up below](#soundcloud-api-id-follow-up--2026-10-29)
adds factory/service regressions and corrects the selected capture.

Adversarial self-review checked all overloads, null versus empty captures,
first-match and pattern-order behavior, both regex-array overloads, exception
ordering, consumer type checking and the complete source diff. A second pass
checked the 24-call inventory, required-capture assumptions, source/binary
compatibility distinctions, offline test selection and contradictory comments.
There is no new null-to-empty normalization or caller fallback. These are
self-reviews, not independent review, live-service tests or device evidence.
No dependency, build value, service pattern, caller, UI string, legal notice or
repository/device safety rule changed.

| Final verification | Result |
| --- | --- |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-parser-ci.log`. All 12 sanitizer tests passed and all 685 recorded fixtures passed privacy inspection, along with Checkstyle, ktlint, dependency ordering, lint and debug/release APK builds. |
| JVM results | All 1,136 app tests and 663 offline extractor tests completed with no failures or errors; 3 extractor tests were skipped. The unchanged 3-test build-logic result was reused. The 12 new parser regressions passed both before and after the annotation correction. |
| Consumer/API checks | The Kotlin probe rejected all five unsafe dereferences after the correction and successfully ran the null-aware client. Compiled instructions and method descriptors remained unchanged. Final Javadoc passed with 100 reported warnings in untouched sources; Android lint passed with zero errors and 59 existing warnings. |
| Documentation and scope | All 223 local Markdown links and 10 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. Both parser/test source hashes matched through the passing gate. No APK was installed and no device, emulator or live streaming service was exercised. |

### SoundCloud API-ID follow-up — 2026-10-29

Started from clean `master` at `3b0947d7728bfd2fdbd2e57b9992003ec3d56160`,
on branch `fix-soundcloud-api-id`. Current routing and its limitations are
described in [Services](services.md#soundcloud).

The direct API branch read capture group 1, the resource kind, instead of
group 2, the identifier. `SoundcloudCommentsLinkHandlerFactory` delegates both
ID extraction and its acceptance check to that stream factory. Unlike the
stream factory's ordinary `fromUrl` entry point, it therefore accepts matching
API URLs and builds a comments address using the extracted ID. The service's
`getCommentsExtractor(String)` goes through this factory; the app's explicit
service-ID comments path reaches it through `CommentsInfo.getInfo(service, url)`.
This establishes a source path, not an observed Android UI request.

The new `SoundcloudApiLinkHandlerTest` first ran against unchanged production
code: 25 cases, 9 incorrect-ID assertion failures, no fixture errors and no
skips. Six existing resource-kind cases returned the kind rather than the ID;
HTTP/path-suffix extraction, comments-handler creation and the service comments
extractor reproduced the same error. The 16 controls passed: the six stream/
comments acceptance differences, six rejected URLs, ordinary web stream and
comments resolution, short-link resolution and widget-failure handling.
The original log/XML remain at `/tmp/pvcpipe-soundcloud-id-baseline.log` and
`/tmp/pvcpipe-soundcloud-id-baseline.xml`.

The correction selects group 2 and adds a comment identifying the captures.
No URL pattern, factory acceptance method, client-ID discovery, service
registration or request-builder code changed. All 25 focused cases then passed,
with Checkstyle; log `/tmp/pvcpipe-soundcloud-id-focused.log`.

These tests run the real parser, factories, helper, service and comments
extractor with a strict synthetic downloader. They verify exact request paths
and sequences for client-ID discovery, widget lookup, embed canonicalization,
short-link redirects and a comments page. The downloader returns only declared
fixture responses and fails any unexpected request; it has no network transport.
Setup saves and teardown restores the raw NewPipe downloader/localization/
country fields and the cached SoundCloud client ID. The tests do not use a real
account, website response, Android device or emulator.

The passing service case constructs and requests
`/tracks/123/comments?client_id=<fixture>&threaded=0&filter_replies=1` and
parses an empty collection with no next page. Its payload does not establish
nonempty comment-item extraction, live endpoint availability or behavior for a
real remote ID. The inherited pattern recognizes non-track resource kinds and
path prefixes, while comments URLs are still built as track URLs; this change
does not establish comments support for those other resources. Stream `fromUrl`
still rejects the API URLs tested here. Web comments acceptance still performs
widget resolution and returns false for the controlled widget failure.

The later [resource-kind follow-up](#soundcloud-api-resource-follow-up--2026-10-29)
rejects non-track API resources. The results and limitations above describe the
earlier API-ID checkpoint; its failures and inherited behavior remain historical.

Separate finding at the API-ID checkpoint: the [former API and ordinary web host patterns](https://github.com/75ohmantenna/pvcpipe/blob/b91712f9771774000177c4571f8792b8636e7bce/pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/soundcloud/linkHandler/SoundcloudStreamLinkHandlerFactory.java#L17)
left the separator before `com` unescaped. A local probe parsed an ID from
`https://api-v2.soundcloudXcom/tracks/123/`, found comments acceptance true for
that URL, and found stream acceptance true for
`https://soundcloudXcom/fixture-artist/fixture-track`. This was a suspected
host-validation defect, not intended support for those hosts. The patterns
were unchanged by that correction; there was no network request to either
hostname. Probe source/output remain in `/tmp/pvcpipe-soundcloud-id-probes`.
The separately authorized [SoundCloud host follow-up below](#soundcloud-host-follow-up--2026-10-29)
corrects the host literals and adds factory/service-routing regressions.

Adversarial self-review checked capture participation and group numbering,
direct versus inherited factory entry points, both acceptance paths, the app/
service comments call chain, exact request destinations, failure propagation,
client-ID caching and raw fixture-state restoration. A second pass compared
all changed source and documentation, including host boundaries and the
non-track/prefix limitations above. These are self-reviews, not independent
review. Only the selected capture and its explanatory comment change production
code; no URL pattern, dependency, build value, service ID, UI string, legal
notice or repository/device safety rule changed.

| Final verification | Result |
| --- | --- |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-soundcloud-id-ci.log`. All 12 sanitizer tests passed and all 685 recorded fixtures passed privacy inspection. Checkstyle, ktlint, dependency ordering, lint and debug/release APK builds passed. |
| JVM results | All 1,136 app tests and 688 offline extractor tests completed with no failures or errors; 3 extractor cases were skipped. The unchanged 3-test build-logic result was reused. All 25 new cases passed after the correction; the 9 baseline failures remain recorded separately. |
| Lint | Zero errors and 59 existing warnings. No reported issue points to either changed Java file. |
| Documentation and scope | All 225 local Markdown links and 10 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. Both implementation/test source hashes matched through the passing gate. No APK was installed and no device, emulator or live streaming service was exercised. The lookalike-host finding above remains unresolved. |

### SoundCloud host follow-up — 2026-10-29

Started from clean `master` at `b91712f9771774000177c4571f8792b8636e7bce`,
on branch `fix-soundcloud-host-validation`. Current host behavior and its
boundaries are described in [Services](services.md#soundcloud).

The stream factory's ordinary/API patterns used wildcard dots inside hostnames.
The channel, playlist and charts patterns had the same ordinary-host issue;
leaving them unchanged would still let SoundCloud service detection select a
route for some lookalikes. The widget resolver's short-link classifier also
used wildcard dots. Its three production callers validate through the stream,
channel or playlist factories first. Channel-tab routing delegates to the
channel factory. These call paths were traced from implementation.

Six patterns in five production files now use literal dots. No captures,
identifier rules, path expressions, schemes, case-handling flags, client-ID
discovery, request builders or service IDs changed. The stream factory's
short-link predicate already had literal dots; the resolver's classifier now
agrees on the hostname spelling. This does not add general URL normalization
or new support for ports, trailing host dots or additional subdomains.

The offline factory suite was extended from 25 to 116 cases. Before production
edits, 37 failed and 79 passed, with no errors or skips. The failing cases exposed
lookalike/reserved-separator acceptance, decoded wrapper targets reaching
resolution and a raw lookalike short hostname triggering HEAD classification.
The baseline log/XML remain at `/tmp/pvcpipe-soundcloud-host-baseline.log` and
`/tmp/pvcpipe-soundcloud-host-baseline.xml`. After the literal-dot changes all
116 cases passed, with Checkstyle; log `/tmp/pvcpipe-soundcloud-host-focused.log`.
The original 25 ID/routing cases and their assertions are retained.

The added matrix includes 76 untrusted web/API/short-shaped inputs. It checks
stream, comments, channel, playlist and charts acceptance, the SoundCloud
service's link-type selection, direct ID extraction where it validates input,
and each factory's inherited `fromUrl` path. Rejected cases leave the strict
synthetic downloader's request list empty. Coverage includes wildcard
substitutions, path/query/fragment separators in authority-shaped text,
userinfo, misleading prefixes/suffixes, explicit ports, trailing dots and
encoded/Unicode separators. Framework wrapper cases also check the decoded
target before resolution.

Positive controls cover HTTP/HTTPS, bare/www/mobile web hosts, ordinary mixed
case, channel/playlist/charts acceptance, legitimate framework wrappers,
foreign text in a valid host's query/fragment, and the existing API-ID and
short-link resolution paths. The fixture adds an explicit HTTP widget response;
every other original response and request-sequence assertion is retained.
Downloader, localization/country and client-ID cache state are saved/restored
around each test, including the controlled transport-stop case.

The raw widget resolver remains a resolution utility, not a factory acceptance
gate. Its added classifier test supplies a transport that records and throws
before I/O: it checks that a lookalike does not trigger HEAD, not that the raw
utility rejects all foreign input before service lookup. The no-request
rejection claims above apply to factory entry points and service routing.
HTTP redirect destinations and downloader redirect policy are unchanged; no
redirect allowlist or live network/HTTP transport was tested. No account,
Android device, emulator or UI flow was exercised.

Adversarial self-review compared all hostname literals, capture counts and path
expressions, then checked each validator and delegated route against its
callers. A second pass reviewed wrapper unwrapping, user-info and separator
tricks, valid-host controls, fixture request accounting and exact global-state
restoration. It also checked the distinction between factory rejection and raw
resolver/transport behavior. These are self-reviews, not independent review.
Only host separators change in production; no interface, ID rule, dependency,
build value, UI string, legal notice or repository/device safety rule changed.

| Final verification | Result |
| --- | --- |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-soundcloud-host-ci.log`. All 12 sanitizer tests passed and all 685 recorded fixtures passed privacy inspection. Checkstyle, ktlint, dependency ordering, lint and debug/release APK builds passed. |
| JVM results | All 1,136 app cases passed. The offline extractor suite reported 779 cases: 776 passed and 3 were skipped, with no failures or errors. This includes all 116 focused routing cases. The unchanged 3-test build-logic result was reused. The 37 failing baseline cases remain recorded separately above. |
| Lint | Zero errors and 59 existing warnings. No reported issue points to a changed Java source file. |
| Documentation and scope | All 226 local Markdown links and 9 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. All six implementation/test source hashes matched their staged blobs and working files through the passing gate. No APK was installed; no external URL availability, live service, HTTP transport, device, emulator or receiver was tested. |

### SoundCloud API resource follow-up — 2026-10-29

Started from clean `master` at `db41fa8aa3e515065fbf4bf538bb4bbd5b0246fc`,
on branch `fix-soundcloud-api-resources`. Current behavior is described in
[Services](services.md#soundcloud).

The [former API-ID branch](https://github.com/75ohmantenna/pvcpipe/blob/db41fa8aa3e515065fbf4bf538bb4bbd5b0246fc/pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/soundcloud/linkHandler/SoundcloudStreamLinkHandlerFactory.java#L24)
accepted `albums`, `sets`, `reposts`, `followers` and `following`, as well as
`tracks`. The [comments factory](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/soundcloud/linkHandler/SoundcloudCommentsLinkHandlerFactory.java#L35)
delegates ID extraction to that branch but always constructs a track-comments
address. An album input could therefore select a different resource's track ID.
The [service's URL overload](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/StreamingService.java#L305)
passes through `fromUrl`, including Google-wrapper unwrapping, before creating
the comments extractor. The app's `ExtractorHelper.getCommentsInfo` calls
`CommentsInfo.getInfo(service, url)`, which uses that same service overload.
These callers were traced from implementation; no UI flow was run.

With the new negative cases and additional track-URL controls, the unchanged
implementation reported 146 cases: 121 passed and 25 failed, with no errors or
skips. Twenty failures covered direct, suffix, nested-path and Google-wrapped
non-track inputs across the five inherited resource kinds. The other five ran
the service's extractor through `getInitialPage`: each reached the synthetic
mobile client-ID response and then `/tracks/123/comments`, although its input
was a non-track resource. The fixture returned an empty comments collection and
no exception. This demonstrates offline routing to a track address, not that a
real album or track exists or that the live endpoint would return comments.
The baseline log/XML remain at `/tmp/pvcpipe-soundcloud-resource-baseline.log`
and `/tmp/pvcpipe-soundcloud-resource-baseline.xml`.

The [API-ID branch](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/soundcloud/linkHandler/SoundcloudStreamLinkHandlerFactory.java#L24)
now requires literal `tracks`. Removing the resource-kind
capture leaves one required capture containing the identifier; extraction now
uses `Parser.matchGroup1`. The comments factory's existing delegation then
rejects the non-track API inputs before client-ID discovery or other downloader
access. No comments request builder, stream acceptance method, web/short-link
resolver, hostname rule or transport redirect policy changed. Raw `fromId` and
prebuilt link handlers still trust their supplied IDs; this correction validates
the URL entry points rather than adding resource provenance to raw identifiers.

The final focused suite has 147 cases, all passing with Checkstyle; log/XML
`/tmp/pvcpipe-soundcloud-resource-focused.log` and
`/tmp/pvcpipe-soundcloud-resource-focused.xml`. The earlier tests expecting
non-track IDs and comments acceptance were replaced with track-only controls;
all six original ID spellings still have positive coverage. The 25 new negative
cases assert rejection before any downloader request, through direct getters,
acceptance, `fromUrl` and the service where applicable. An additional positive
Google-wrapped track-API case verifies canonical comments routing. Six valid
track forms exercise the service through an empty initial page, preserving
HTTP/HTTPS, comments suffixes, query strings and fragments. Existing host,
web/short-link, controlled-failure and global-state-restoration coverage remains.
The downloader fixture and responses are unchanged.

The API pattern still requires a slash after the identifier and accepts a
prefix before additional path components; this is not complete suffix
validation or general URL normalization. Existing identifier grammar and case
handling remain. The tests use the real parser, factories, service and comments
extractor with a strict synthetic downloader, not a live HTTP transport.
No device, emulator, receiver, account or live-service observation is claimed.

Adversarial self-review checked the removed capture against the selected group,
all callers of the shared getter, factory acceptance versus direct getters,
wrapper unwrapping, nested paths and the early no-request rejection boundary.
A second pass compared the production diff and test expectations, checked valid
track and existing web/short-link controls, and separated the current guide from
the preserved historical evidence. These are self-reviews, not independent review.
No dependency, build value, service ID, UI string, legal notice or repository/
device safety rule changed.

| Final verification | Result |
| --- | --- |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-soundcloud-resource-ci.log`. All 12 sanitizer tests passed and all 685 recorded fixtures passed privacy inspection. Checkstyle, ktlint, dependency ordering, lint and debug/release APK builds passed. |
| JVM results | All 1,136 app cases passed. The offline extractor suite reported 810 cases: 807 passed and 3 were skipped, with no failures or errors. This includes all 147 focused routing cases. The unchanged 3-test build-logic result was reused. The 25 failing baseline cases remain recorded separately above. |
| Lint | Zero errors and 59 existing warnings. No reported issue points to either changed Java file. |
| Documentation and scope | All 231 local Markdown links and 12 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. Both implementation/test source hashes matched their staged blobs and working files through the passing gate. No APK was installed; no external URL availability, live service, HTTP transport, device, emulator or receiver was tested. |

### Seek-duration label follow-up — 2026-10-29

Started from clean `master` at `235aa1fba0942e11255790d03972a8c23c6cd24e`,
on branch `fix-seek-duration-label-fallback`. Current settings behavior is
covered in [Playback](playback.md#seek-interval-settings).

`VideoAudioSettingsFragment.updateSeekOptions` appended a duration value before
resolving its plural label, then swallowed `Resources.NotFoundException` without
appending a label. A missing label therefore shifted later labels relative to
their values, or left an empty label array while the value array stayed populated.
The settings XML initially supplies the static descriptions and values; the
update runs during preference creation and after inexact-seek changes. The
player reads the saved duration through `PlayerHelper`; its seek routing and
decoder policy were inspected but are unchanged by this correction.

The new `SeekDurationOptionsTest` invokes the actual private update method with
controlled `Resources` results under Robolectric on API 26. AndroidX
`ListPreference`, `PreferenceManager` and an isolated Android preference store
are real within that environment. The fragment is a spy with resource, context,
manager and preference lookup supplied by the fixture. This is a method-level
regression, not full settings-screen inflation or real resource/locale failure.

Before production edits, 10 cases ran: 3 passed and 7 failed, with no errors or
skips. Failures showed missing/shifted labels for first, middle, last and all
plural failures, including filtered choices and selection replacement. The
baseline log/XML remain at `/tmp/pvcpipe-seek-label-baseline.log` and
`/tmp/pvcpipe-seek-label-baseline.xml`. An initial harness compile error and two
later Checkstyle line-length errors were corrected separately; those logs remain
at `/tmp/pvcpipe-seek-label-harness-compile.log` and
`/tmp/pvcpipe-seek-label-style-first.log`. They are harness/style failures, not
additional runtime defect reproductions.

The update now uses the existing `seek_duration_description` entry at the
original source index when plural lookup throws `NotFoundException`. It keeps
that source index across filtering, so retained 10/20/30-second values receive
their corresponding labels. The static array is read only on the error path;
normal plural lookup and formatting are unchanged. The resource array comment
now describes its fallback role. Its values, all other resource text and XML
attributes remain unchanged, verified by comparing parsed XML with the base.
All six maintained labels pair with the six values. This relies on that paired
configuration; it does not introduce recovery for a missing or malformed
static array or for unrelated formatting exceptions.

All 10 focused cases then passed with Checkstyle; log/XML remain at
`/tmp/pvcpipe-seek-label-focused.log` and `/tmp/pvcpipe-seek-label-focused.xml`.

Coverage includes successful plural lookup, individual and total label failures,
inexact filtering with localized and fallback labels, skipped label lookups for
filtered values, preservation of valid saved selections, the existing 15-to-20
and 25-to-30-second persistence changes, and rebuilding both arrays when toggling
modes. The actual `ListPreference.getEntry` result is checked against the stored
value. Toast text is observed through Robolectric's shadow; the fixture supplies
its text and the labels, so these cases do not validate packaged translations
or rendered UI. No emulator, device, receiver or live service was exercised.

Adversarial self-review checked source versus filtered indices, exception scope,
partial and total failure, real preference persistence, repeated updates and
successful-label controls. A second pass compared the full production/resource
diff, confirmed that static labels are loaded only on the failure path, and
checked the current guide against callers and defaults. These are self-reviews,
not independent review. No dependency, build value, service ID, preference key,
resource text, legal notice or repository/device safety rule changed.


| Final verification | Result |
| --- | --- |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-seek-label-ci.log`. All 12 sanitizer tests passed and all 685 recorded fixtures passed privacy inspection. Checkstyle, ktlint, dependency ordering, lint and debug/release APK builds passed. |
| JVM results | All 1,146 app cases passed, including all 10 focused regressions. Gradle reused the unchanged offline extractor result: 810 cases, 807 passed and 3 skipped, with no failures or errors. The unchanged 3-test build-logic result was also reused. The 7 failing baseline cases remain recorded separately above. |
| Lint | Zero errors and 59 existing warnings. No reported issue points to a changed implementation, test or resource file. |
| Documentation and scope | All 233 local Markdown links and 11 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. Parsed resource XML retained all values and attributes. All three implementation/test/resource source hashes matched their staged blobs and working files through the passing gate. No APK was installed; no device, emulator, receiver or live service was exercised. |


### Report-prompt setting wording — 2026-10-29

Started from clean `master` at `d3341d61a6acd2b9c5ac272cecc2c06a8d9410fc`,
on branch `fix-error-report-prompts-wording`. The separately reported FCast
source-start concern remains deferred. Current reporting behavior is described
in [Error report diagnostics](error-reporting.md#local-collection-is-separate-from-attachment-consent).

The old setting title, **Disable error reporting**, implied a broader switch
than the implementation provides. Its summary mentioned screens only and added
an unsupported generic warning about unexpected behavior. The title is now
**Hide error report prompts**. The summary is **Stop showing new error report
screens, notifications and snackbars. Does not disable local log capture or
crash collection.** The in-app reporting notice uses the same setting name and
names those presentation surfaces. Every other sentence in that notice remains
unchanged, including its scope, collection, sharing and retention qualifications.

Source review traced `ErrorUtil.openActivity`, `createNotification` and
`showSnackbar` to the same preference check. Its sole production read is there;
`extra_settings.xml` retains the same switch, `disable_error_reports` saved key
and false default. `ErrorInfo` constructors trigger capture before presentation;
`PvcLogcatDumper` reads the independent logcat preference, defaulting to true.
`App.attachBaseContext` initializes ACRA without consulting the presentation
switch, and `AcraReportSender` constructs ErrorInfo before calling `ErrorUtil`.
No initialization, capture, display or sender implementation changed.

The switch checks new presentation requests. It does not close an existing
report or withdraw an existing notification/snackbar. Their previously created
actions still open the report without rechecking the switch. These are observed
source boundaries, not newly implemented revocation behavior or a broader
privacy/collection guarantee. Existing dated references to the old title remain
historical, linked to this current guide and follow-up.

Only three default-English resource contents change: the setting title, summary
and its setting-reference sentence in `reporting_notice_body`. No localized
overrides for those three names were found in the checkout, and no new
translations are claimed. Parsed XML comparison with the base verified that all
resource names/attributes and every other value are unchanged. The preference
XML and saved key/default are unchanged. No test assertion, dependency, build
value, service ID, attribution or repository/device safety rule changed.

Adversarial self-review checked presentation versus collection, the false
default and existing stored key, already-created actions, the other reporting
notice qualifications, and all locale/resource references. A second pass
reviewed the complete resource/documentation diff for stale duplicate names,
unsupported warnings and stronger privacy claims. These are self-reviews, not
independent review. This text-only correction adds no tests that repeat the
resource literals; existing checks and packaged-resource inspection provide the
validation. No reporting/share flow was invoked and no report or captured log
was sent. No emulator/device rendering or collection behavior was exercised.

The temporary AAPT2 inspection initially failed to find the source XML path in
the optimized release APK. Resolving `xml/extra_settings` through each APK's
resource table corrected the probe; no application change was needed. Both
debug and release inspections then passed. The probe remains at
`/tmp/pvcpipe-report-prompts-check-apk.py`; resource/XML dumps remain under
`/tmp/pvcpipe-report-prompts-{debug,release}-{resources,settings}.txt`.

| Final verification | Result |
| --- | --- |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-report-prompts-ci.log`. All 12 sanitizer tests passed and all 685 recorded fixtures passed privacy inspection. Checkstyle, ktlint, dependency ordering, lint and debug/release APK builds passed. |
| JVM results | Gradle reused all unchanged JVM results: 1,146 app cases passed; the offline extractor suite reported 810 cases, 807 passed and 3 skipped, with no failures or errors; all 3 build-logic cases passed. No new tests or assertions were added for this text-only correction. |
| Lint | Zero errors and 59 existing warnings. No reported issue points to the three edited strings. |
| Packaged resources | AAPT2 verified both APKs' title, summary, updated notice clause and unchanged stored key. The switch binds to those resources and retains a false default. All inspected string values have only the default configuration. This is compiled-resource inspection, not UI rendering or collection/presentation-flow execution. |
| Documentation and scope | All 234 local Markdown links and 10 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. Parsed XML verified exactly three changed string contents, with every resource name/attribute and all other values preserved. A separate comparison verified that only the setting-reference clause changed within the reporting notice. The resource source hash matched its staged blob and working file through the passing gate and APK inspections. |

### Subscription headings — 2026-10-29

Started from clean `master` at `1f255eaf28243638dc8fd2d7c0b2e81cafb31dfb`,
on branch `fix-unavailable-subscription-headings`. The FCast source-start concern
remains deferred. Current subscription presentation is described in
[Library data](library-data.md#subscriptions-tab).

The subscription view model grouped records by their persistent service ID but
passed the shared helper's `<unknown>` text into each unavailable-service header.
Different retired IDs therefore produced separate sections with identical raw,
untranslated labels. The header view was bold but had no heading metadata, and
its two-line ellipsis limit could hide the identifying suffix in constrained
layouts. These observations came from implementation, not the old audit alone.

Service sections now pass the original ID into `Header`. At binding time it
retains registered names for supported services, or uses the complete
`subscription_unavailable_service` resource with that ID. Resource formatting
uses the bound view's UI locale. The new format has a default-English resource;
no new prose translations are claimed. It says unavailable rather than assigning
invented service names to arbitrary unknown IDs. `Header` also keeps its string
constructor for the existing general Subscriptions section title. Both forms
set heading metadata with `ViewCompat`, and the layout wraps without the former
two-line/ellipsis limit. The shared `ServiceHelper` and its other callers are
unchanged. Service registration, retired IDs, grouping/sorting and database
entities, schemas and writes are unchanged.

`SubscriptionHeadingTest` runs the real view model's reactive grouping and the
real header binding under Robolectric API 26. Manager construction and resource
labels are controlled doubles; it does not open a subscription database or load
the packaged header XML. Ten baseline cases ran before production edits: four
passed and six failed, with no errors or skips. Failures covered retired and
arbitrary unknown labels, bound-view locale/rebinding, recycled labels and
heading metadata. The baseline log/XML remain at
`/tmp/pvcpipe-subscription-heading-baseline.log` and
`/tmp/pvcpipe-subscription-heading-baseline.xml`. An initial harness compile error
was corrected separately; its log is
`/tmp/pvcpipe-subscription-heading-harness-compile.log`.

The final focused suite has eleven passing cases, including the additional
existing string-constructor regression. It checks all registered names, retired
IDs 2/3/4, unknown/negative/maximum IDs, sorted sections and original channel UIDs,
unchanged entity snapshots, last-channel removal, the empty case, resource locale
on rebind, view recycling and API 26 compatibility-node heading metadata. The
focused log/XML remain at `/tmp/pvcpipe-subscription-heading-focused-final.log`
and `/tmp/pvcpipe-subscription-heading-focused.xml`. The focused command also
passed Checkstyle/ktlint and built the debug and instrumentation APKs. Initial
line-length issues in the new rendering fixture were corrected separately;
`/tmp/pvcpipe-subscription-heading-style-first.log` preserves that failed check.

Emulator checks use the standard `AndroidJUnitRunner` through direct, selected
instrumentation. No Gradle connected-test installation/cleanup task is used.
`SubscriptionHeaderDeviceTest` requires emulator hardware, the debug package and
an explicit host marker. It inflates production resources and checks native
nodes, UI-locale formatting and constrained large-font layout without database
writes. Its optional focus assertion requires an expected heading and keeps
TalkBack enabled rather than suppressing accessibility services. The native-node
check waits for the rendered package and label, excluding the launcher's YouTube
icon. The optional focus probe emits `WAITING_FOR_TALKBACK_HEADING` and waits for
host-driven navigation; starting instrumentation can close the test-APK host, so
the host launches that fixture after the readiness message.

`SubscriptionHeaderRenderingActivity` lives only in the test APK and rejects
physical hardware. Because that APK runs in a separate process, the fixture
loads the production header and binding through the installed debug APK's class
loader. It renders the actual header/layout, with synthetic channel rows, rather
than copying the implementation. It is a rendering fixture, not the full
Subscriptions screen, a database migration test or a live service observation.

The Android CLI created a fresh `small_phone` AVD from its official API 36.1
Google Play profile. Its initial cold boot did not become ready within the CLI
wait while Gradle builds were active; it was stopped. The second boot followed
the first release gate, with that newly created AVD configured for 2 GiB RAM and
two virtual CPUs. The attached physical Pixel is excluded from all installation and
instrumentation commands. No cause of the initial emulator readiness failure
is claimed as an application defect.

Adversarial self-review traced every header constructor caller, preserved the
general section title, kept the global helper outside this change, and checked
persistent IDs, ordering, locale rebinding and compatibility-node metadata. A
second pass checked resource formatting, recycling, long-label truncation and
the emulator/runner/data isolation boundaries. These are self-reviews, not
independent review. No dependency, build value, preference key, service ID,
license/attribution or repository/device safety rule changed.

The second cold boot also exceeded the CLI's readiness wait, but the emulator
subsequently reached `sys.boot_completed=1`. First-boot OS/startup ANRs included
an initial target-process bind-application ANR before any tests ran. The failed
instrumentation log remains at
`/tmp/pvcpipe-subscription-heading-device-tests.log`; it is not a test pass.
Later harness iterations exposed detached-node inspection, a launcher-icon
text collision, package filtering and fixture-start timing. They also exposed
an active CLI layout-inspector automation connection blocking another
`UiAutomation` registration. The corrected checks inspect window-backed nodes,
wait for the fixture, and stop only the emulator's CLI inspector before direct
instrumentation. These harness failures remain in the
`/tmp/pvcpipe-subscription-heading-*.log` files. No application fix was made to
hide them. Two attempted ktlint task names were absent; the configured
`runKtlint` task was then used. A subsequent wrapping failure was corrected
before the instrumentation APK was rebuilt.

Screenshot inspection verified default labels, Arabic-locale numeric IDs and
the complete maximum integer ID wrapping onto three lines at 200% font size
and 220 dp width. The Arabic case verifies numeric formatting with an English
fallback, not an Arabic prose translation or full RTL screen behavior. The
artifacts include `/tmp/pvcpipe-subscription-heading-arabic.png` and
`/tmp/pvcpipe-subscription-heading-large-id.png`. Earlier screenshots captured
first-boot OS ANR dialogs; those remain observations of that emulator session.
The installed debug APK's SHA-256 matched the built debug APK. The test host
was excluded from both main APK manifests.

On this API 36.1 emulator, preinstalled TalkBack 16.0.0.777931756 ran with its
Headings reading control selected. Hardware-level touchscreen gestures through
the emulator console moved from the general title through YouTube to ID 2 in
two gestures, and to ID 3 in three gestures in a separate run. These moves
skipped the intervening channel rows.
Injected ADB input and keyboard attempts did not establish that navigation;
the gesture sequence and focus assertions did. Separate synchronized probe
runs passed for `Unavailable service (ID 2)` and `Unavailable service (ID 3)`.
Each required enabled TalkBack, the fixture package, matching accessibility
focus text and native-node heading metadata. Logs are
`/tmp/pvcpipe-subscription-heading-talkback-id2-synchronized.log`,
`/tmp/pvcpipe-subscription-heading-talkback-id3-synchronized.log` and
`/tmp/pvcpipe-subscription-heading-talkback-focus-evidence.txt`. An earlier
snapshot probe read TalkBack settings after instrumentation closed the fixture;
its failure remains at `/tmp/pvcpipe-subscription-heading-talkback-id2-test.log`.

The emulator's interactive accessibility timeout was temporarily increased to
keep the reading-control overlay visible during screenshot inspection. That
setting, the original disabled TalkBack state and the empty per-test-app locale
list are restored after validation. The unrelated screenshot-description consent
dialog was canceled. The owned AVD is stopped after testing. The connected physical Pixel was not targeted. This evidence covers
the production header and XML in a synthetic test host, not a full Subscriptions
screen, actual subscription persistence, speech quality, another Android engine
or physical hardware/service behavior.


| Final verification | Result |
| --- | --- |
| Release gate | Final `make ci` passed; log `/tmp/pvcpipe-subscription-heading-final-ci.log`. All 12 sanitizer tests passed and all 685 fixtures passed privacy inspection. Checkstyle, ktlint, dependency ordering, lint and debug/release APK builds passed. |
| JVM results | Final gate reused unchanged reports: all 1,157 app cases passed, including the 11 focused heading cases; the offline extractor suite reported 810 cases, 807 passed and 3 skipped; all 3 build-logic cases passed. No failures or errors. These counts are JVM evidence, not device results. |
| Lint | Zero errors and 59 existing warnings. No issue points to the new string or edited code; existing warnings elsewhere in `strings.xml` remain. |
| Emulator assertions | The final instrumentation APK passed all four selected packaged-label, UI-locale, window-backed-node and constrained-font tests. Log `/tmp/pvcpipe-subscription-heading-device-tests-final.log`. Two separately selected synchronized TalkBack focus probes also passed, one each for IDs 2 and 3. The final fixture build passed Checkstyle/ktlint and `assembleDebugAndroidTest`; log `/tmp/pvcpipe-subscription-heading-synchronized-focus-build.log`. No connected-test Gradle task, package uninstall or app-data clear was used. |
| Packaged resources | AAPT2 verified the new format and absence of the former header `maxLines`/`ellipsize` attributes in both main APKs, resolving the optimized release layout through its resource table. The rendering activity is absent from both main manifests. The final gate reused those unchanged APK outputs. These compiled-resource checks complement the emulator fixture; they do not establish full-screen/device coverage. |
| Documentation and scope | All 235 local Markdown links and 9 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. XML comparison verified exactly one appended string and only the two intended layout-attribute removals, plus the final newline. All eight source/resource/test hashes match their working files and staged blobs through the final gate; the two documentation files record the scope and evidence. |

The final review found no additional product defect within this change. The
shared service helper's fallback elsewhere remains outside this presentation
change, and the FCast source-start concern remains explicitly deferred.

### Production Subscriptions screen — 2026-10-29

Started from clean `master` at `8fa576faed8e16bdd45f76a216e225c78c8ea5c5`,
on branch `test-subscriptions-screen`. This follow-up checks the actual
`MainActivity`/`SubscriptionFragment` and default on-disk Room database. The
previous heading fixture results above remain evidence of their own synthetic
host. The FCast source-start concern remains deferred.

The new `SubscriptionScreenEmulatorTest` and
`tools/subscriptions_screen_validation.py` require an explicitly acknowledged
disposable emulator, debug package, matching installed APK hashes and nonce
marker. Seeding refuses existing subscriptions and other library records. It
uses real DAO inserts without replacing the database singleton or supplying a
fragment/view-model double. The normal saved-tab preference opens Subscriptions
through the production activity. The fixture includes two YouTube channels,
one SoundCloud channel, retired IDs 2/3/4, unknown ID 99 and maximum integer ID.
No service extraction or playback is requested.

The host starts each phase in a fresh target process. Screen checks inspect
actual adapter rows, stored UIDs and every fixture subscription field, service
ordering, list/grid item versions, heading spans/native node metadata and
untruncated heading text. The large-font case uses the real 220 dp-wide emulator window
and 200% font setting. Visual checkpoints allow Android CLI layout and screenshot
inspection. The TalkBack stage waits for host touchscreen gestures while native
accessibility focus is observed. The deletion stage opens the real channel
long-press dialog, identifies its title and Unsubscribe label in the focused
application window, and touches that label. Later phases reread the same default
database after process restart. These are
controlled local records, not imported real subscriptions or live-service data.

Cleanup deletes only records with this run's nonce URL prefix and restores the
three overridden app preferences. The host restores font scale, window-size
override and accessibility settings even if fixture cleanup fails. It does not
install/uninstall packages or clear app data; APK installation is a separate
Android CLI step on the selected AVD. Direct selected instrumentation is used,
never the Gradle connected-test task. No production code, schema, dependency,
runner, signing or safety-rule change is required for this validation.

The first run failed before test execution with a bind-application ANR after
emulator boot. `/tmp/pvcpipe-subscriptions-screen-run-01/summary.json` records
`passed: false` with no completed cases; the instrumentation log reports
`Process crashed`. It is not passing evidence and was not rewritten. Initial
fixture-formatting failures were corrected before rebuilding; their build logs
remain under `/tmp/pvcpipe-subscriptions-screen-build*.log`.

The second run seeded its records but timed out while the production screen was
interrupted by a System UI ANR and first notification-permission prompt. Its
cleanup process also hit a bind-application ANR. The notification prompt was
denied on the owned emulator. Run 02's failed summary and cleanup failures are
preserved at `/tmp/pvcpipe-subscriptions-screen-run-02`; they were not converted
into passes. The saved ANR trace showed the main thread runnable with about
28 seconds of scheduling wait and guest CPU pressure near 100%. This is an
observation of that emulator, not proof of an application deadlock.

After stopping the idle Gradle daemon, Android's `cmd package compile -m speed -f`
precompiled the installed debug and test APKs. A separate cleanup retry then
passed, deleting the eight nonce-tagged records and restoring the three app
preferences. Its result is `cleanup-aot.json` in run 02. The final matrix was
restarted with a fresh nonce; application source and APK contents were unchanged
by precompilation. No startup timeout was disabled and no application bypass
was added. These environment/startup failures remain separate from screen
assertions.

Run 03 passed list, two-column grid, constrained-font and TalkBack checks, but
the deletion fixture timed out looking for clickable Unsubscribe text in the
active accessibility window. Its failed summary remains in
`/tmp/pvcpipe-subscriptions-screen-run-03`; cleanup passed with zero remaining
fixture subscriptions and restored preferences. The fixture was changed to
inspect the actual dialog visually, find its title/label in the focused
application window, and use touch input on the identified text. It does not
call the manager's deletion method directly. No product change was made to
satisfy the fixture. The complete matrix is rerun after this harness correction.

The final run at `/tmp/pvcpipe-subscriptions-screen-run-04` passed all eight
selected instrumentation stages: seed, list, grid, large, TalkBack, delete,
relaunch and cleanup. Each stage ran as a separate one-test invocation in a
fresh target process; the host verified distinct PIDs. The summary records
`passed: true`. Installed/built APK hashes and emulator identity are in
`target.json`; exact tested APK copies and the final fixture/driver source
hashes are preserved under `tested-apks/` and `source-hashes.json`. Each phase
has its native runner output and JSON result; visual phases also retain the
CLI hierarchy, inspected screenshots and host checkpoint acknowledgement.

- List and grid checks retained all eight channel records and seven service
  groups, in service-ID order with the database's channel-name order. Supported
  IDs 0/1, retired IDs 2/3/4, unknown 99 and maximum integer ID were exercised.
  All header nodes advertised heading metadata and spanned the full row;
  channel rows did not advertise headings. The grid used two spans, with all
  service headings spanning both. List/grid channel item versions, titles and
  original UIDs were checked against the stored records.
- At 200% font size in the real 220 dp-wide window, unavailable labels wrapped
  without ellipsis; the maximum-ID label occupied three complete lines.
  The screenshot still shows existing toolbar/channel-title ellipses. This
  result is about service headings, not a claim that every narrow-screen label
  is untruncated.
- Preinstalled TalkBack 16.0.0.777931756 ran with Headings selected, confirmed
  in `talkback-control.png`. Host console touchscreen input focused ID 2 and
  moved to IDs 3 and 4. Native assertions checked the actual app package,
  accessibility-focused text and heading metadata at both destinations,
  skipping the intervening channel rows. Speech quality was not assessed.
- The real long-press dialog for `Fixture 6 service 99` was inspected in
  `delete-dialog.png` and `delete-layout.json`. The CLI hierarchy exposes
  Unsubscribe as a TextView without a clickable interaction, explaining why
  the former text-node predicate was insufficient. The corrected fixture
  identified the focused dialog's title/label and injected touch at the label's
  measured center. The resulting production callback deleted only ID 99's
  channel and removed its now-empty section.
- After a host force-stop and new application process, the real screen still
  displayed the other seven subscriptions and six service groups. Every
  surviving UID, service ID, URL, name, avatar URL, subscriber count, description
  and notification mode exactly matched the seeded snapshot. Other library
  tables remained empty. This is real default-database/process-restart evidence
  for these controlled records, not general filesystem durability certification.
- Cleanup deleted only this nonce's remaining records, verified zero
  subscriptions, restored the three app preference overrides, and confirmed
  the other table counts were unchanged. The host restored and checked its
  saved font/accessibility settings and original window-size override.

Adversarial self-review checked data ownership and refusal guards, native versus
synthetic views, adapter recycling/order, list/grid spans, focused versus active
accessibility windows, non-clickable dialog text, touch dispatch, fresh-process
storage reads and failure cleanup. It found and corrected fixture assumptions;
these are self-reviews, not independent review. The negative host-guard probes
refused physical serials and missing disposable acknowledgement before any ADB
call. Python compilation/help, source hashes, documentation references and the
final staged scope are checked separately from emulator assertions.

This run used the existing owned `small_phone` API 36.1 AVD; the physical Pixel
was never targeted. No package uninstall, app-data clear, in-memory database,
production singleton override or test runner substitution was used. The tests
did not request extraction/playback or use live service responses; background
network traffic was not audited. The installed debug APK contained the unchanged
application code.
No additional product defect was found in these scenarios. The startup/OS ANRs
and earlier failed runs remain recorded above. The emulator is stopped after
validation. FCast source-start remains explicitly deferred.

Fresh-process phases were launched through the standard AndroidJUnitRunner with
production `App`/`MainActivity`, not through a substitute application or activity.
They establish instrumented restart/persistence behavior; this matrix does not
cover a separate uninstrumented launcher journey, physical hardware, live service
behavior, speech quality, power loss or arbitrary preexisting user databases.

Final host-tool review added an early refusal of optimized Python execution,
because `-O` would disable the driver's assertions. Normal physical-target and
optimized-mode probes both failed before any ADB call; missing disposable
acknowledgement was also checked separately. The native fixture/APKs are
unchanged from the passing matrix. Run 04 preserves its original `driver.py`
and source hashes. An AST comparison verified that the only later driver
behavior change is this early refusal; the rest matches the passing version.
Python compilation/help and negative guard checks were rerun for that final
host-only change; the emulator matrix was not repeated for it.


| Final verification | Result |
| --- | --- |
| Emulator matrix | All eight selected one-test stages passed on the owned API 36.1 emulator. Run 04's `summary.json`, native outputs, screenshots, CLI hierarchies and preserved APKs record the evidence. The three earlier failed runs remain separate. |
| Native fixture build | Checkstyle, ktlint and `assembleDebugAndroidTest` passed; log `/tmp/pvcpipe-subscriptions-screen-dialog-build-final.log`. The native fixture source and both tested APK hashes match the final matrix artifacts. |
| Release gate | `make ci` passed; log `/tmp/pvcpipe-subscriptions-screen-ci.log`. All 12 sanitizer cases passed and all 685 recorded fixtures passed privacy inspection. Checkstyle, ktlint, dependency ordering, extractor Checkstyle, lint and debug/release builds passed. |
| JVM results | The gate reused unchanged reports: 1,157 app cases passed; 810 offline extractor cases, 807 passed and 3 skipped; all 3 build-logic cases passed. No failures or errors. These are JVM results, separate from the eight Android stages. |
| Lint | Zero errors and 59 existing warnings; none points to the new screen-test source. |
| Host tooling | Final Python compilation/help and physical-target, missing-acknowledgement and optimized-mode refusal probes passed. The final post-matrix driver change is limited to rejecting optimized execution; AST comparison and the original matrix driver are retained as described above. |
| Documentation and scope | All 238 local Markdown links and 9 source-line bounds passed across 21 tracked Markdown files. Changed-file spelling and diff whitespace checks passed. Review covers the new native fixture/host driver, library test guide, dated evidence and documentation-index date. Production source, schemas, resource values, build definitions, existing test assertions, legal notices and repository/device safety rules are unchanged. |

## Uninstrumented release subscriptions — 2026-10-29

This follow-up exercises the unchanged application at
`1aad6c132dd432c8cea444b5743a6fb4fff7694b` through a normal launcher journey,
without a test runner attached to the app. The signed release was built with
`./gradlew --no-daemon -DpackageSuffix=.smoke :app:assembleRelease`; the build
passed in 10m 30s. The existing release configuration enabled R8 and resource
shrinking. The tested package was `org.seventyfiveohmantenna.pvcpipe.smoke`,
version 0.2.4/code 1000204, non-debuggable, with no instrumentation declaration.
Its release certificate SHA-256 was
`14a4e83570c8d32855ad271c8eec1f5822ba225673c11111c4f653dc0bf996bb`.
The tested APK SHA-256 was
`ab5bdb9f3272cb5d9b595e31b8ec0a31c4186bf10726eadaf05eca0898e5b4b6`.

Evidence is under `/tmp/pvcpipe-release-subscriptions-01`: preserved APK,
R8 mapping/configuration/resource reports, manifest, certificate, build metadata,
CLI hierarchies, visually inspected screenshots, public backup archives,
comparison reports and before/after device settings. The owned `small_phone`
Google Play API 36.1 AVD reported Android 16/SDK 36, x86_64, `ranchu` and
`ro.kernel.qemu=1`. Its `.smoke` package was absent before Android CLI installation.
No app test APK was installed or invoked in this journey. The CLI inspector used
its own external instrumentation server; ActivityManager recorded only that
instrumentation, targeting the inspector's package. It was stopped before
TalkBack gestures. This is an uninstrumented **application** journey, not a claim
that the inspection tooling uses no instrumentation.

The host-only `tools/subscriptions_release_fixture.py` prepared a schema-9 backup
from the checked-in Room schema. It contained eight controlled subscriptions
with UIDs 101–108 and service IDs 0, 0, 1, 2, 3, 4, 99 and 2147483647, plus
settings selecting a subscriptions-only tab and list mode. The seed ZIP hash
was `4b65b4353fce2433b63939e2effde6fa7801470a73e3adb487f3e43c8045c028`.
The backup was copied to public Downloads, selected through the system document
picker, and restored using the production Restore with settings dialog and
normal restart. No private-file injection, `run-as`, root access, singleton
override or target instrumentation seeded/read the app's database.

The completed journey observed:

- The normal Subscriptions list grouped the eight records into seven sections:
  YouTube, SoundCloud and distinct unavailable-service headings for IDs 2, 3,
  4, 99 and 2147483647. The normal Export database action and system save picker
  produced `baseline-export.zip`. `baseline-check.json` passed, comparing every
  stored subscription field and UID with the seed, SQLite `quick_check`, foreign
  keys, schema/Room metadata, subscriptions-only tabs and empty other library
  tables. The checker reads JSON preferences, never legacy Java serialization.
- After selecting Grid in Appearance and recreating the app, the real channel
  screen showed two columns with full-width service headings. At 200% font in
  the 220 dp-wide window, the maximum-ID heading wrapped into three complete
  lines without ellipsis (`large-narrow-max.png`). Existing toolbar and channel
  title truncation remained visible; this does not establish that all labels fit.
- TalkBack 16.0.0.777931756 ran with Headings selected. Host emulator-console
  touchscreen gestures moved visible green accessibility focus from SoundCloud
  to ID 2, then ID 3 and ID 4, skipping intervening channel rows. The selector
  and destination screenshots are preserved. These are visual focus observations,
  separate from the previous debug suite's native heading assertions. Speech
  quality was not assessed.
- A real long press on `Release fixture 6 service 99` opened its production
  dialog. Touching Unsubscribe removed the channel and now-empty ID-99 section.
  A host force-stop followed by a normal launcher start created a different
  process (PID 8555 → 9020); Android reported `LaunchState: COLD`, MainActivity,
  and 3460 ms launch time. This was done before exporting again.
  `post-restart-export.zip` then passed `post-restart-check.json`: exactly seven
  records, every surviving UID and field unchanged, no ID 99, and no records in
  the other library tables. No private WAL was inspected and no WAL-only or
  power-loss guarantee is inferred from this check.

Environment failures are retained rather than counted as passes. During the
initial boot/install/launch interval Android services were not ready, Downloads
was initially inaccessible, and system_server aborted. The activity command
reported Broken pipe; the inspector disconnected. The crash buffer also contains
a smoke-app WorkManager `SQLiteCantOpenDatabaseException` for its missing
`no_backup` directory and subsequent `DeadSystemException` entries in the app
and multiple system processes. Their relationship is not isolated; this is not
evidence that a release/R8 defect was fixed or that the storage exception is
harmless. The initial logs and launch attempts remain in this evidence directory.

After Android reached `RUNNING_UNLOCKED`, System UI repeatedly timed out in its
edge-swipe handler and blocked injected touches. The app, System UI and permission
controller were precompiled using Android's `cmd package compile -m speed -f`.
The selected emulator's display was temporarily reduced, animation settings
disabled and System UI restarted. The completed journey used the same APK after
that preparation, without uninstall, data clearing or a changed startup timeout.
Normal display size was restored before the main list/grid journey; the later
220 dp/large-font phase was deliberate. App notification prompts (including after
restore) and TalkBack's own notification prompt were declined. Wi-Fi was briefly
disabled, but cellular data remained enabled: the first Live tab displayed
network content. No playback or channel extraction was requested, and background
traffic was not audited. This run is not offline or general live-service evidence.

One UI finding remains separate from the passing rendering checks: changing
Appearance → List view mode to Grid and returning left the existing Subscriptions
view in list layout (`channel-grid-top.json`); a cold restart applied Grid
(`channel-grid-cold.json`).
[`SubscriptionFragment.initViews`](../app/src/main/java/org/schabi/newpipe/local/subscription/SubscriptionFragment.kt)
configures the adapter/span count. Review whether settings return should rebuild
that view; do not treat delayed application as an intentional guarantee.
The nearby channel-group toggle also advertises the generic List view mode
description in
[`subscription_groups_header.xml`](../app/src/main/res/layout/subscription_groups_header.xml#L28)
while changing groups only; a proposed UI-text correction is a group-specific
description. Neither behavior nor runtime text was changed here.

Subsequent 2026-10-30 follow-ups address the [layout refresh](#subscriptions-layout-settings-return--2026-10-30)
and [toggle labels](#channel-group-toggle-accessibility-label--2026-10-30).

All saved font, window-size, animation, accessibility and network setting values
were restored and compared. The smoke app was stopped with its seven controlled
records retained for reinspection; existing debug/test packages remained
installed. The physical Pixel was never targeted. The owned emulator was stopped.
This evidence does not cover physical hardware, other Android versions,
arbitrary user libraries, filesystem/power-loss durability or speech quality.
FCast source-start remains explicitly deferred.

Adversarial self-review checked the selected package/certificate and emulator,
restore/export provenance, all-field comparisons, fresh-process order,
instrumentation ownership, actual channel versus group grid, visual TalkBack
limits and restored settings. A host negative-probe mistake was found: a mutated
WAL database was archived without checkpointing, so the supposed changed ZIP
still held the original data. That failed probe is preserved separately as
`failed-negative-probe-01.json` and its original report. The corrected probe
checkpoints/closes the host mutation and rereads the archive to establish the
changed value before testing rejection. This did not change the application,
tested APK, real UI exports or checker. These are self-reviews, not independent
reviews.

The second pass reproduced an absent-target gap in the new host checker:
`--removed-service 100` could accept the unchanged baseline because the seed had
no such service. An explicit seed-membership guard now refuses that request.
Baseline and post-restart exports still pass under optimized Python, and the
absent target is rejected. Earlier probes also reject changed metadata, wrong
Room identity, a falsely claimed ID-99 deletion and deletion of the wrong service.
The final helper change only adds this host-side refusal; it does not alter the
fixture, application or APK, and the emulator journey was not repeated for it.

| Final verification | Result |
| --- | --- |
| Release journey | Normal launcher startup, restore with settings, list/grid and large-font headings, visual TalkBack navigation, real unsubscribe and seven-record comparison after a cold restart passed on the owned emulator, after the environment preparation above. Initial failures remain separate. |
| Host checker | Final baseline/post-restart exports accepted; changed metadata, wrong Room identity, false deletion, wrong-service deletion and absent removal target rejected under optimized Python. Reusing the existing fixture directory was refused without changing its seed. Python compilation/help passed. Results and the final helper hash are in `final-host-checker-probes.json`. |
| Repository gate | `make ci` passed; log `/tmp/pvcpipe-release-subscriptions-ci.log`. All 12 sanitizer cases and inspection of 685 recorded fixtures passed. App/extractor style checks, dependency ordering, lint and debug/release builds passed. |
| JVM and lint limits | Gradle reused the unchanged JVM reports: 1,157 app cases passed; 810 offline extractor cases, 807 passed and 3 skipped; all 3 build-logic cases passed. Lint reports zero errors and 59 warnings. These reports are separate from the normal release UI journey. |
| Documentation and scope | All 244 local Markdown links and 11 source-line bounds passed across 21 tracked Markdown files; changed-file spelling and diff whitespace checks passed. Changes are limited to the host fixture/checker, build/library guides and this dated follow-up. No application source, schema, resource value, build definition, existing test assertion, legal notice or safety rule changed. |

## Subscriptions layout settings return — 2026-10-30

This follow-up corrects the delayed channel-layout application observed in the
earlier uninstrumented release run above. Work started on 2026-10-29 from clean
`master` at
`e76b9389601da10cbfefbcc7170991da5574c5b3`, on branch
`fix-subscriptions-layout-refresh`. The prior observation remains historical.
Its source link now omits an obsolete line anchor after the lifecycle code moved.

`SubscriptionFragment.onResume` resolves the channel mode and grid width again.
If either the channel item version or span count changes, it updates the existing
Groupie adapter and GridLayoutManager together, changes current channel row
types, notifies only the nested subscriptions section and restores the manager's
saved scroll state.
Headers and channel order/IDs are retained. Future database emissions use the
applied item version, avoiding grid rows in a still-list-shaped manager if a
preference changes while paused. An unchanged resume does not invalidate rows.
The independently selected channel-group layout is not reset by this refresh.
The nearby KDoc also now describes Auto's configuration-based resolution and
allows one-column grids; its former Auto/`> 1` table was inaccurate.

The initial `SubscriptionLayoutRefreshTest` version failed five of six cases
against the unchanged implementation; unchanged resume passed. The correction
then passed all six, and ktlint passed. The final fixture also checks adapter/
manager identity, delivery of the saved scroll state and no save/invalidation
on unchanged resume. It covers List ↔ Grid, a one-column grid, changed grid
width, Card mapping, data arriving before/after resume and initially empty data.
These are JVM/Robolectric API-26 checks of real fragment resume/rendering and
RecyclerView/Groupie objects, with a minimal binding and controlled theme
readings. They do not launch Settings, inflate the production row layouts or
assert actual viewport pixels. Initial red and subsequent green reports are
retained under `/tmp/pvcpipe-layout-refresh-01`; build logs use
`/tmp/pvcpipe-layout-refresh-*.log`.

Self-review found that the initial whole-adapter notification produced a new
`NotifyDataSetChanged` lint warning and needlessly included the group controls.
The first gate was interrupted before completion; its log is retained as
`/tmp/pvcpipe-layout-refresh-ci-initial.log`. The implementation now uses Groupie's
nested section notification, whose observers translate it into a range change
for the subscription rows only. The strengthened fixture verifies that range,
asserts no whole-adapter invalidation and no observer calls on unchanged
resume. All six cases and ktlint passed again before the final gate/builds.

The final `make ci` passed: 12 sanitizer cases, inspection of 685 recorded
fixtures, app/extractor style checks, dependency ordering, all 1,163 app JVM
cases, lint and debug/release builds. The unchanged offline extractor reports
were reused (810 cases: 807 passed, 3 skipped), as were the three passing
build-logic cases. Final lint reports zero errors and the previous 59 warnings;
there is no finding in the changed fragment. The signed production release
build took 10m 13s. The separate
`./gradlew --no-daemon -DpackageSuffix=.smoke :app:assembleRelease` passed in
10m 1s, with R8 and resource shrinking enabled. Its APK SHA-256 is
`2e478e0099429156723e0b4923f5dc15f9dcc970baef74784fc8bc947a9ad809`.
It is the non-debuggable `org.seventyfiveohmantenna.pvcpipe.smoke`, version
0.2.4/code 1000204, with no instrumentation declaration and the same release
certificate recorded above. Both APKs, shrinker outputs, build-input hashes,
manifest and certificate results are retained under `/tmp/pvcpipe-layout-refresh-01`.

The first device attempt used the existing API-36.1 `small_phone` AVD. Its
installed old APK hash/certificate were checked, the user reached
`RUNNING_UNLOCKED`, and Android CLI updated only `.smoke` with `-r`. The installed
APK hash then matched the new artifact. However, the following manual package
precompilation failed with PackageManager's Broken pipe/status 224 before the
launcher command was reached. Android restarted system_server from PID 747 to
3416, then 4752; its watchdog reported a main-thread handler blocked for 66s.
A later window-service preparation command also failed with Broken pipe. These
are retained setup failures, not passing UI evidence or an isolated diagnosis
of their cause. Original display, animation, accessibility and network settings
were restored and compared, and the AVD was stopped without uninstall or data
clearing. Its library could not be read back through the unavailable UI, so this
run does not certify the existing API-36.1 installation's library or rendering.

A fresh owned AOSP API-26 x86_64 AVD, `pvcpipe_layout_api26_20261030`, supplied the
successful journey. SDK `avdmanager` selected the installed image because Android
CLI creation exposes predefined profiles; Android CLI started and inspected the
device. An optional hardware edit was attempted before creation finished and
failed without writing a file. The completed AVD then used CLI defaults; its
actual `hardware-qemu.ini` was inspected and preserved: Android 8.0.0, 480×800,
240 dpi, one core, 1,536 MB RAM and Lavapipe. The smoke package was absent before
installation. The same verified APK reached the normal MainActivity in 2356 ms,
without manual precompilation or animation changes on this device.

The prior run's public `post-restart-export.zip`, containing seven controlled
records, was copied to public Downloads and selected through the real system
document picker. Production Restore with settings validated it and restarted
the app into its subscriptions-only tab. This uses the normal backup workflow,
not private-file injection, root access, an in-memory database or a substitute
application/activity. The already-removed ID-99 record was not reintroduced and
no unsubscribe was performed in this follow-up. Initial default Live content
was fetched before seeding; the layout journey then used local controlled
records. Background traffic was not audited and this is not offline or service-
availability evidence.

Five normal Settings → Appearance → List view mode → Back round-trips passed:
List → Grid, Grid → List after scrolling, List → Card, Card → Auto and Auto →
explicit List. Every phase retained PID 3471 and the same printed RecyclerView
identity `5a24636`, before and after Settings. The real grid had two channel
columns while group controls retained list presentation; List/portrait Auto
used mini rows, and Card used grid rows. CLI title bounds were 204 pixels wide
in Grid/Card and 353 in List/Auto, corroborating the inspected screenshots.
Scrolled returns did not jump back to the group controls at the top; row-height
changes naturally changed visible pixel positions. Each phase preserves its
UI actions, native hierarchy and before/after Activity dumps under `phases/`.
`ui-matrix-summary.json` checks process/view identities and those bounds.

The normal Export database action and system save picker produced
`api26-post-layout-export.zip`. The host checker accepted exactly seven records,
every original UID and stored subscription field unchanged, no records in other
library tables, schema/Room metadata intact, and successful SQLite `quick_check`
and foreign-key checks. Saved tabs remained subscriptions-only and the exported
mode was `list`. The check used the earlier eight-record seed snapshot with
`--removed-service 99` to describe the already-removed fixture record, not a new
deletion. Its full result is `api26-export-check.json`.

This is an uninstrumented application journey. Android CLI installed its own
external inspector. A post-run guard incorrectly required an active inspector
entry after it had become inactive; that guard failure is retained separately.
The inspector APK's manifest independently confirms that its instrumentation
targets `com.android.cli.interact.instrumentation` itself, and the installed
smoke APK hash still matches the release artifact. No app-targeted runner was
used. This ownership check does not turn the CLI's instrumentation into native
assertions inside the smoke app.

The two self-review passes covered mode/spans changing together, same-span
view-type changes, nested notification offsets, late/empty data, no-op resumes,
group independence, scroll restoration, APK/device ownership, real UI versus
JVM evidence and export provenance. These are self-reviews, not independent
reviews. Data interleavings and changed grid widths remain controlled JVM
evidence; the native Auto check is for this portrait phone. Physical hardware,
other screen configurations, arbitrary user libraries, live services, speech
quality and power-loss durability are not established here. The generic
channel-group toggle description remains the separate UI-text follow-up above.
FCast source-start remains explicitly deferred.

The fresh AVD's original display, accessibility, animation and network settings
were compared and restored, its smoke/inspector processes were stopped, and the
seven controlled records were retained for reinspection. Both owned AVDs are
stopped. The physical Pixel was never targeted. No package was uninstalled and
no app data was cleared.

Final review covers the production fragment and its nearby KDoc, the new six-case
JVM fixture, the build/library guides, documentation index and dated evidence.
The frozen application/test source hashes still match the tested build inputs.
All 247 local Markdown links and 10 source-line bounds passed across 21 tracked
Markdown files; changed-file spelling and diff whitespace checks passed.
Schemas, dependencies, version/build values, existing test assertions, runtime
UI text/translations, existing legal notices and repository/device safety rules
are unchanged.

## Channel-group toggle accessibility label — 2026-10-30

Follow-up to the generic toggle label observed in the
[release subscriptions record](#uninstrumented-release-subscriptions--2026-10-29)
and left separate by the [layout refresh](#subscriptions-layout-settings-return--2026-10-30).
Work began on clean `master` at `64016f4ec0bbd4ffbf6f486fbfa15ee753258fcf`.
The previously installed release's native hierarchy still exposed **List view
mode** on `header_toggle_view_mode`, the same wording as the Appearance setting.

`GroupsHeader.updateIcons` now resolves the next-action description from the
bound root's UI context alongside the icon. When groups use a list it exposes
**Show channel groups as grid**; when they use a grid it exposes **Show channel
groups as list**. Full binds, recycled binds and `PAYLOAD_UPDATE_ICONS` all use
this path. The XML default describes the default list mode's action. The two
new resources currently have default-English text; the Appearance setting's
label is unchanged. No click handler, group state, channel layout,
persistence, database schema, dependency or build value changed.

The four-case `GroupsHeaderTest` uses real binding and click methods with
controlled label resources and suppressed drawable loading. It checks both full
bind states, partial updates in both directions, recycled labels/callbacks and
separation from the sort callback. An initial fixture compilation failed because
this Robolectric version's `getApplication()` takes no type argument; that call
was corrected. All four tests then failed on label assertions before the
production binding change and all four passed afterward. This JVM fixture does
not inflate the packaged layout, load real drawables, navigate the Settings UI or
exercise a screen reader. Local evidence is in `/tmp/pvcpipe-group-label-20261030/`.

After correcting two ktlint wrapping errors in the new fixture, `make ci` passed:
12 sanitizer tests; 685 recorded fixtures needing no replacements; style and
dependency-order checks; 1,167 passing app JVM cases; lint with zero errors and
59 warnings (none on the changed header); and debug plus signed, minified release
assembly. The unchanged extractor results were accepted as up to date: 807 passes
and three skips. The three build-logic tests were also up to date. The initial
format failure is retained as `ci-format-failure.log`; the final gate is `ci.log`.

The production APK is `org.seventyfiveohmantenna.pvcpipe`, version 0.2.4/code
1000204, min SDK 26/target 37, without debuggability or an app instrumentation
declaration. Its SHA-256 is
`3edb65a5963256070903d5852c95f4143d3373053b4a485528abd7982b341687`;
the signing certificate SHA-256 is
`14a4e83570c8d32855ad271c8eec1f5822ba225673c11111c4f653dc0bf996bb`.
The pulled installed APK matches this artifact. Host setup guards initially used
the wrong AAPT SDK field name and treated `pm path`'s absent-package exit status
as an error. Both stopped before installation and were corrected using the
actual badging output and an exact package-list check.

The owned AOSP API 26 AVD `pvcpipe_layout_api26_20261030` was reused at 480×800,
240 dpi. The production package was absent and installed separately from the
existing smoke package. The previous seven-record export was verified on the
host, copied to public Downloads and imported through the normal system picker
and Restore with settings action. No private-file injection or app-targeted
runner was used. Initial Live content was fetched before seeding; background
traffic was not audited, so this is not offline or general service evidence.

Seven native hierarchy states verified four group toggles, first with channel
rows in List and then in Grid, plus normal Appearance round-trips to Grid and
back to List. Both next-action labels matched the actual group geometry; the
built-in NEW/All controls changed between vertical rows and adjacent grid cells.
Channel title widths remained 353 pixels in List and 204 in Grid across group
toggles. Inspected screenshots corroborate both icons and layouts. PID 4729
remained unchanged throughout. The final post-Back capture contained only system
bars and stopped the Settings helper's active-window guard; a fresh capture
without another UI action found the expected List screen, the same PID and
RecyclerView identity `b408937`. The early capture remains in `phases/07-final-list/`;
`ui-matrix-summary.json` includes the successful re-query separately.

Normal Export database produced `post-toggle-export.zip`. The host checker
accepted all seven records with every UID and stored subscription field intact,
no records in other library tables, matching schema/Room metadata, and successful
SQLite `quick_check` and foreign-key checks. Exported channel mode was `list`
and saved tabs remained subscriptions-only. As in the earlier fixture run,
`--removed-service 99` describes the already-removed seed record; no unsubscribe
was performed here.

Two adversarial self-reviews checked reversed labels, full/partial/recycled
binding paths, callback replacement, sort independence, the unchanged Appearance
label, resource retention, fixture limitations and native capture timing. These
are self-reviews. Native hierarchy values and screenshots establish the labels
and controlled layouts, not speech quality, hardware results, other Android
versions or arbitrary user libraries. FCast source-start remains deferred.

Cleanup found `secure/accessibility_enabled` at 1 versus its saved value of 0.
The first cleanup command stopped the AVD before restoring that value; the AVD
was restarted, the app/smoke/CLI-inspector processes were force-stopped and the
saved value was restored. All nine saved display/accessibility/animation/network
values then matched, and process checks found all three packages stopped.
`dumpsys activity instrumentation` is unsupported on this API 26 image and is
not evidence of active-runner state; the package list identifies the CLI runner
as targeting its own inspector package. The owned emulator is stopped with both
controlled installations retained. No package was uninstalled, no app data was
cleared and the physical Pixel was never targeted.

Final spelling, XML parsing, diff whitespace and local-link checks passed (251
links and 10 source-line bounds across 21 tracked Markdown files). The frozen
application/test inputs still match the tested build. Existing test assertions,
legal notices and repository/device safety rules are unchanged.
