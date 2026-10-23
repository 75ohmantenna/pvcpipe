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

No resource text, key or translation is changed by this pass. These are proposed
corrections, not approved localized copy. Resource keys below refer to
`app/src/main/res/values/strings.xml`.

| Key | Call sites / factual issue | Suggested wording |
| --- | --- | --- |
| `metadata_cache_wipe_summary` | `HistorySettingsFragment` → `InfoCache.clearCache`; clears extracted in-memory metadata, not all cached webpage/WebView data | “Clear cached stream, channel, playlist, and other metadata” |
| `delete_view_history_alert` | `HistorySettingsFragment.openDeleteWatchHistoryDialog`, also opened by `StatisticsPlaylistFragment`; deletes playback positions as well as stream history | “Delete all watch history and playback positions?” |
| `too_many_requests` | `ErrorInfo.getMessage` for a non-solvable `ReCaptchaException`; classification does not prove a network-wide restriction | “Too many requests to %1$s. Try again later.” |
| `parsing_error` | `ErrorInfo.getMessage` for remaining `ExtractionException`s; extraction failure need not be website parsing | “Could not extract the requested information” |
| `error_report_open_github_notice` | `activity_error.xml`, shown by `ErrorActivity` for non-crash failures too | Replace “this crash” with “this error” |
| `start_accept_privacy_policy` | `ErrorActivity.openPrivacyPolicyDialog`; acceptance opens a composer or issue list, not submission; code does not establish GDPR compliance | “Please read and accept PVCPipe’s privacy policy before continuing to report this error.” Legal/policy review required |
| `recaptcha_request_toast` | `ErrorInfo.getMessage` and `RouterActivity`; router opens the challenge directly, error panels offer Solve | “A reCAPTCHA challenge is required to continue” |
| `download_has_started` | `DownloadDialog.DownloadPresentation.submitted`; service dispatch is not confirmed mission execution | “Download request submitted” |
| `cancel` in the `import_settings` dialog | `BackupRestoreSettingsFragment.importDatabase`; the negative action starts database-only restore rather than cancelling | Use a dedicated “Restore without settings” label at this call site; do not rewrite the shared Cancel resource |
| `file_deleted` | `Deleter.next`; feedback precedes delayed commit, and the manager ignores the file-deletion result | “Download marked for deletion: %1$s” while undo is available; later feedback must not claim confirmed erasure without a behavior change |
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
