# Error reporting

## Copy and share

**Copy report** puts the Markdown report on the clipboard. **Share report…**
opens Android's chooser with the JSON report as `text/plain`. Select
**Include captured diagnostic log** to grant the receiving app read access to
one matching log as well. The receiving app gets the data as soon as you
select it and controls what happens next. Opening or inspecting the report
sends nothing.

**Inspect report** previews the exact text for Copy and Share. **Inspect
diagnostic log** opens the related local log. Attachment selection starts
unchecked for each report and resets when Android recreates the activity.
If a selected log is incomplete or unreadable, Share reports the failure
and lets you retry or clear the attachment selection.

Reports can contain URLs and requests, search terms, comments, exception text
and traces, language preferences, and app and OS metadata. Logs can contain
additional activity and identifiers. **View reporting notice** opens the
bundled description of collection and sharing.

## Local collection is separate from attachment consent

**Logcat dumper** controls whether constructing a public `ErrorInfo` starts
local capture. It defaults to off, including on upgrades from builds that
used the legacy capture key. Backup imports preserve the current local opt-in,
including activation of a previously queued restore. Capture requests logs
starting about 30 seconds before the `ErrorInfo` timestamp. That is a lower
bound for the log query. Captures live in the internal cache, and cleanup uses
a keep-last-files threshold of ten. Changing the setting leaves existing files
available for ordinary cache cleanup.

**Hide error report prompts** stops new report screens, notifications and
snackbars routed through `ErrorUtil`. Existing actions can still open their
reports. The setting leaves local capture and ACRA crash collection running
according to their existing configuration.

`App.initACRA` selects `STACK_TRACE`, `REPORT_ID`, `IS_SILENT` and
`USER_CRASH_DATE` for new crash records. Existing records keep their original
fields. `AcraReportSender` reads the stored stack trace into a new `ErrorInfo`
and opens the report screen. Exports contain the trace and report fields.
PVCPipe has no automatic network report sender.

## Pagination diagnostics

`SearchFragment.handleNextItems` includes the query, page URL and page IDs in
search pagination diagnostics. The page cookie map remains available to the
extractor for continuation requests. The screen, Markdown and JSON display
`ErrorInfo.request` as supplied. Inspect exports for sensitive text in their
inputs, including URLs, exception messages, comments and logs.

## Timestamp provenance

`ErrorInfo` records wall-clock time when `PvcLogcatDumper.triggerLogCapture`
runs, even with capture disabled. Parcelable transport retains the timestamp.
The screen and both export formats use it when positive, otherwise they use
the time the report activity opened.

ACRA conversion constructs a new `ErrorInfo` from the stored trace. Its
timestamp reflects conversion time because `AcraReportSender` does not import
`USER_CRASH_DATE`.

## Report formatting and boundaries

The UI resolves missing, retired and unsupported service IDs to the
**Unknown service** string in the display context, including after parcel
transport. Android falls back to the default English resources. Markdown
and JSON use the diagnostic identifier `<unknown_service>` for those IDs,
with one service field in each export.

The formatting code marks the display service name separately in request
refusal, account-restriction and sign-in messages. Exception reasons keep their
HTML formatting. Public `ErrorInfo` constructors bound exception text through
`PvcErrorInfoHelper`, and JSON applies a serialization bound as well.

## YouTube API refusal feedback

`YoutubeParsingHelper` classifies an HTTP 403 HTML response as
`RequestBlockedException` when it comes from `youtubei.googleapis.com` or
`www.youtube.com` over HTTPS, on the `visitor_id`, `player`, `next` or
`reel/reel_item_watch` v1 path, and the first 16,384 characters contain
`captcha`, `unusual traffic` or `automated queries`. The classifier requires
at least 50 characters. HTTP 404 indicates unavailable content. Other unsuccessful
responses preserve their HTTP status before body validation. Unexpected HTML and
malformed JSON in successful responses still produce extraction errors.

The app shows a service-refusal message for recognized challenge pages and
status-specific feedback for other HTTP failures. Manual retry remains available
where the caller supplies it. A usable alternate player response takes precedence.
If all clients fail, content restrictions take priority, followed by refusals,
other HTTP failures, and generic transport or parsing errors.
When a usable player response is already available, a refused `next` request
becomes an optional watch-metadata error. The player handles data HTTP failures
through the failed queue-item path. [Local media request recovery](playback.md#errors)
handles eligible media requests separately.

The [recorded November 15 device observations](https://github.com/75ohmantenna/pvcpipe/blob/e7cb3f6b25b55cc47b2b409e9b2f621fe9e311bb/docs/error-reporting.md#youtube-api-refusal-feedback-2026-11-15)
retain the original refusal and later route-dependent recovery in the tested
build. The classification does not identify the cause of a refusal.
