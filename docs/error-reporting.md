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

The **Logcat dumper** preference is off by default, including on upgrades from
versions that enabled capture. A new opt-in key deliberately ignores the old
setting; restarting does not reset a later explicit choice. Settings imports
preserve the current local opt-in, including when activating a previously queued
restore. The policy change does not delete existing logs or stored crash reports. Public `ErrorInfo`
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

`App.initACRA` now selects only `STACK_TRACE` and ACRA 5.13.1's required
`REPORT_ID`, `IS_SILENT` and `USER_CRASH_DATE` bookkeeping for new crash records.
It does not select preferences, logcat, installation identifiers or broader
device/build/configuration collectors. This is reduced collection, not
anonymization: exception text and stack traces may still contain sensitive data.
Existing stored ACRA records are not rewritten or deleted by this policy change.
The custom `AcraReportSender` still reads only `STACK_TRACE` into a new
`ErrorInfo` and opens the report screen. Exported report text includes that trace
and the report fields, not the entire stored ACRA record. Report fields can still
include requests/URLs, search terms, comments, exception details, language
settings and app/OS metadata. No automatic network report sender is configured.
Copy/share format, previews, timestamps and unchecked per-report log attachment
consent are unchanged.

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

User-facing error messages and the report screen's Service field use the
translatable `unknown_service` resource when the service ID is missing, retired
or unsupported. Its default text is **Unknown service**. There are no bundled
non-English overrides, so other locales currently use Android's English fallback.
Supported service names are unchanged. [`ErrorInfo`](../app/src/main/java/org/schabi/newpipe/error/ErrorInfo.kt)
resolves the fallback from the display context when rendering, including after
parcel transport; constructing an error does not freeze a translated label.

The no-argument `ErrorInfo.getServiceName()` remains the diagnostic name used by
JSON and Markdown: unknown services still export `<unknown_service>`. The context
overload is for display. The service argument in request-refusal, account-reason
and sign-in messages is marked separately from other formatting arguments;
exception reasons and unrelated arguments retain their existing HTML formatting.
This is not general escaping or sanitization of exception text.

Markdown includes Service once. JSON retains its single `service` field.
Both formats retain user comments and report metadata. Public ErrorInfo
constructors bound exception text through `PvcErrorInfoHelper`; JSON bounds it
again at serialization. The copy/share configuration preserves these builders
and their formats; it does not sanitize arbitrary report inputs or redesign
ACRA collection. It does not automatically send a report.

See [documentation-alignment.md](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/documentation-alignment.md) for dated controlled
before/after evidence and its limitations.

## YouTube API refusal feedback (2026-11-15)

A forbidden (HTTP 403) HTML response from the exact HTTPS YouTube API hosts
`youtubei.googleapis.com` or `www.youtube.com`, on the `visitor_id`, `player`, or
`reel/reel_item_watch` v1 paths, is classified as `RequestBlockedException` only
when its first 16,384 characters contain a traffic/security marker (`captcha`,
`unusual traffic`, or `automated queries`). Existing short-response validation
still applies. Other HTML failures remain parsing failures; JSON is not scanned
for these words.

The app shows the existing neutral service-refusal message, without a sign-in
claim or a CAPTCHA action. This exception is retryable by the user but is not
presented as an extractor bug report. It carries a fixed message, not the response
body or request URL. Other failures retained as suppressed exceptions retain their
existing diagnostic content; this is not general report sanitization.

A usable alternate player-client response still takes precedence over a refusal.
When all clients fail, an explicit content restriction has priority over this
refusal, which has priority over generic transport/parsing errors. This change
improves feedback only: it does not unblock the request, switch endpoints, change
VPN/network settings, send additional cookies, or solve challenges.

A bounded disposable-package Pixel probe observed one visitor API response with
HTTP 403, HTML content, and one of the above markers, then stopped. The original
app's saved report recorded HTML responses but not their HTTP status. The laptop
returned JSON and playable Android-client metadata for the same public test video.
Different application UIDs and network routes can behave differently; these
observations do not establish an IP/VPN cause, a solvable CAPTCHA, or that the
original app's failure is identical. Offline regression tests cover classification
and player-fallback behavior, not recovery of live playback on the phone.

Follow-up on 2026-11-15: after the owner manually switched the phone's VPN
endpoint, the same public video (`aqz-KE-bpKQ`) played in the unchanged installed
app at revision `11ae4c41`. Device inspection confirmed a rendered video frame
and playback advancing from 2.701 to 29.156 seconds, then paused at 32.722 seconds.
The feedback fix above was not installed and was not needed for that recovery.
This supports a route-dependent refusal, but does not establish the exact VPN/IP
cause. No app installation or VPN/settings change was performed by the assistant
during this retest.
