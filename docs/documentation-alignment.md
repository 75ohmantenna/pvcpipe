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
| `file_deleted` | `Deleter.next`; feedback precedes delayed commit, and the manager ignores the file-deletion result | Corrected in the [cache/history/download follow-up](#cache-history-and-download-messages--corrected-2026-10-23): “Marked for deletion: %1$s” while undo is available; later feedback must not claim confirmed erasure without a behavior change |
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
| Non-SQLite databases could reach staging at the audited revision | The [former `ImportExportManager.prepareRestore`](https://github.com/75ohmantenna/pvcpipe/blob/86adf7b59d8e2baef869eeeeffe05dccaf5a1a99/app/src/main/java/org/schabi/newpipe/settings/export/ImportExportManager.kt#L102) only checked entry presence/nonzero size before publication. [`PendingDatabaseRestore.install`](../app/src/main/java/org/schabi/newpipe/settings/export/PendingDatabaseRestore.java#L25) moved it before database opening. Placeholder/rollback tests did not establish SQLite openability, and malformed-database startup was not exercised. The [backup-validation follow-up below](#backup-validation-follow-up--2026-10-28) adds SQLite/Room checks before new restores are published. |
| Bookmark save may clear a newer draft's dirty flag | [`BookmarkFragment.saveImmediate`](../app/src/main/java/org/schabi/newpipe/local/bookmark/BookmarkFragment.java#L374) uses unconditional `setNoChangesToSave()` in asynchronous completion. Compare the revision captured by [`LocalPlaylistFragment.saveImmediate`](../app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java#L652). Intervening edits are a suspected race; no new scheduling regression was added. |
| Orphan cleanup omits permanent-thumbnail references | [`StreamDAO.deleteOrphans`](../app/src/main/java/org/schabi/newpipe/database/stream/dao/StreamDAO.kt#L110) checks history, playlist contents and feed only. [`PlaylistEntity`](../app/src/main/java/org/schabi/newpipe/database/playlist/model/PlaylistEntity.kt#L27) stores a thumbnail stream ID without a foreign key; [`getPlaylistMetadata`](../app/src/main/java/org/schabi/newpipe/database/playlist/dao/PlaylistStreamDAO.kt#L99) looks the image up in that stream row. A synthetic SQLite probe of the actual deletion query removes a row referenced only by a permanent thumbnail, making the image lookup null. This is SQL-boundary evidence, not an Android/Room or rendered-image test. |
| Parser nullability does not match annotations | [`Parser.matchGroup`](../pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/utils/Parser.java#L122) delegates to `Matcher.group`. A JVM probe of the compiled helper returns null for an unmatched optional capture, accepts group zero and propagates `IndexOutOfBoundsException` for a nonexistent group. Documentation is corrected; signatures and callers are unchanged. |
| SAF exception cleanup may stop early | [`FileStreamSAF.close`](../app/src/main/java/us/shandian/giga/io/FileStreamSAF.java#L110) groups all closes in one try and marks disposed first. An early IOException skips later explicit closes; actual descriptor/provider consequences were not tested. |
| Seek-option error path has no label fallback | [`VideoAudioSettingsFragment.updateSeekOptions`](../app/src/main/java/org/schabi/newpipe/settings/VideoAudioSettingsFragment.java#L135) adds a value before plural lookup but appends no label on `NotFoundException`. Bundled default plurals exist; the comment formerly promising an English fallback was inaccurate. A resource failure could leave the arrays misaligned, but was not reproduced on Android. |

The separate inherited cast initial-source-start concerns under
[active-cast checkpoint attribution](playback.md#active-cast-checkpoint-attribution--corrected-2026-10-23)
remain open. This pass does not strengthen the older controlled JVM evidence,
resolve source-start ordering or claim real receiver-clock provenance beyond the
implemented protocol policy.

### UI-text proposals left separate

No user-facing text or translation was changed, including in XML files whose
comments were corrected.

| Surface | Proposed follow-up |
| --- | --- |
| [`disable_error_reports_title` / `disable_error_reports_summary`](../app/src/main/res/values/strings.xml#L912) | Describe hiding report screens **and notifications**, and state that local capture/ACRA collection remain enabled separately. The title “Disable error reporting” can imply a broader collection switch than [`ErrorUtil`](../app/src/main/java/org/schabi/newpipe/error/ErrorUtil.kt#L204) implements. Remove unsupported generic risk language only in a separately scoped UI change. |
| [`ServiceHelper.getNameOfServiceById`](../app/src/main/java/org/schabi/newpipe/util/ServiceHelper.kt#L102) and [`subscription_header.xml`](../app/src/main/res/layout/subscription_header.xml) | The existing untranslated `<unknown>` heading for retired services remains. Consider localized, distinguishable retired-service labels and accessibility heading semantics, with a dedicated rendering/TalkBack check. |

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
