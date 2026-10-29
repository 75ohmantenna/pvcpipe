# Error report diagnostics

## Copy and share

The reporting screen offers **Copy report** and **Share report…**, not a fixed
email recipient or issue tracker. Copy places the Markdown report text on the
clipboard; it does not copy or attach the diagnostic log. Share opens Android's
chooser with the JSON report as `text/plain` and, only when selected for this
report, one matching diagnostic log attachment with read access.
Selecting a receiving app gives it that data immediately; it may process the
data without another Send action. This is not confirmation of submission or
delivery to a maintainer, and subsequent handling is outside PVCPipe's control.
Opening or inspecting the report sends nothing.

**Inspect report** previews both exact exports, labeled for Copy and Share.
**Inspect diagnostic log** previews the related local log separately.
The attachment checkbox starts unchecked for each report and after activity
recreation. A selected attachment must be available as a completed, readable
file; an unavailable or unreadable selected log is an explicit failure, not a
silent text-only fallback. Reports and logs remain potentially sensitive.

**View reporting notice** opens a bundled, offline **Reporting notice**. There
is no policy-acceptance gate, inherited privacy-policy link, embedded recipient
or reporting-specific browser route. It describes reporting only, not a
comprehensive privacy policy, legal compliance, retention guarantees or the
policies of an app selected from Android sharing.
Unrelated project links, licenses and third-party policy links are unchanged.

## Local collection is separate from attachment consent

The **Logcat dumper** preference remains enabled by default. Public `ErrorInfo`
construction triggers preference-controlled local log capture; selecting or
clearing an attachment does not change this preference. Captures use internal
cache storage. The configured 30-second offset is a lower-bound request to the
logging toolkit, not a guarantee of an exact 30-second recording. The shared
keep-last-files cleanup configuration uses threshold 10; it is not a fixed
retention period or a guarantee that at most ten files exist at every instant.

**Hide error report prompts** stops new reporting screens, notifications and
snackbars routed through `ErrorUtil`. It does not dismiss already-visible reports
or notifications, disable constructor-triggered capture, or turn off ACRA
initialization/crash collection. The saved `disable_error_reports` key and its
default (`false`) are unchanged; this is a presentation preference, not a
collection switch.
Previously created notification or snackbar actions are not revoked and can
still open a report.

`App.initACRA` retains ACRA's default collection configuration rather than a
stack-trace-only collection allowlist. Depending on its collectors, ACRA's local
stored crash data can include device/build/configuration data, an installation
identifier, preferences and logcat. The custom `AcraReportSender` reads only
`STACK_TRACE` into a new `ErrorInfo` and opens the report screen. Exported report
text includes that trace and the report fields, not the entire stored ACRA record.
Report fields may include requests/URLs, search terms, comments, exception
details, language settings and app/OS metadata. No automatic
network report sender is configured by this application. Neither ACRA collection
nor capture defaults are changed by the copy/share configuration.

## Pagination diagnostics

`SearchFragment.handleNextItems` constructs pagination diagnostics from the
search query, page URL and page IDs. It does not read page cookies for diagnostics
or serialize the `Page`. The original page and its cookies still travel through
`ExtractorHelper.getMoreSearchItems` and `SearchInfo.getMoreItems` to the extractor.
`ErrorInfo.request` is displayed unchanged and included in Markdown and JSON.

This is a narrow removal of cookie-map values from app-created search pagination
diagnostics, not general sanitization. Queries, URLs, page IDs, user comments,
exception messages/traces, attached exception data and captured logs can still
contain sensitive information. Inspect report content before copying or sharing.
No claim is made that arbitrary cookies/secrets appearing in those other inputs
are removed.

## Timestamp provenance

`ErrorInfo` constructors record the wall-clock time returned by
`PvcLogcatDumper.triggerLogCapture`, whether capture is enabled or disabled.
It represents ErrorInfo construction / the capture-trigger point, not necessarily
the instant the underlying error occurred. Parcelable transport preserves it.
The report screen, Markdown and JSON use this timestamp when positive; missing
or invalid nonpositive values fall back to the time the report activity opens.
The capture preference controls local log capture, not timestamp selection or
per-report attachment consent.

`AcraReportSender` creates a new ErrorInfo when converting a stored ACRA report.
It carries the stored stack trace, but does not carry `USER_CRASH_DATE` into
ErrorInfo. Its report timestamp therefore identifies sender-time construction,
not the original crash time. Wall-clock accuracy still depends on the device
clock; this change does not reconstruct missing historical event times.

## Report formatting and boundaries

Markdown includes Service once. JSON retains its single `service` field.
Both formats retain user comments and report metadata. Public ErrorInfo
constructors bound exception text through `PvcErrorInfoHelper`; JSON bounds it
again at serialization. The copy/share configuration preserves these builders
and their formats; it does not sanitize arbitrary report inputs or redesign
ACRA collection. It does not automatically send a report.

See [documentation-alignment.md](documentation-alignment.md) for dated controlled
before/after evidence and its limitations.
