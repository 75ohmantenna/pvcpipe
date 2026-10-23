# Error report diagnostics

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
Capture preferences control log capture/attachments, not timestamp selection.

`AcraReportSender` creates a new ErrorInfo when converting a stored ACRA report.
It carries the stored stack trace, but does not carry `USER_CRASH_DATE` into
ErrorInfo. Its report timestamp therefore identifies sender-time construction,
not the original crash time. Wall-clock accuracy still depends on the device
clock; this change does not reconstruct missing historical event times.

## Report formatting and boundaries

Markdown includes Service once. JSON retains its single `service` field.
Both formats retain user comments and report metadata. Public ErrorInfo
constructors bound exception text through `PvcErrorInfoHelper`; JSON bounds it
again at serialization. This correction does not redesign parcel reconstruction,
report destinations, privacy-policy content, log-capture defaults or attachment
consent. It does not automatically send a report.

See [documentation-alignment.md](documentation-alignment.md) for dated controlled
before/after evidence and its limitations.
