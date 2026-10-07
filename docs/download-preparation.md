# Download preparation ownership

`DownloadPreparation` owns the transition from a download selection to a
submitted download. The dialog gathers user choices and presents location
requests, collision confirmations, and results. It does not choose processing
algorithms, assemble recovery records, or prepare output files.

The module owns filename extensions and MIME types, audio/video pairing,
postprocessing and subtitle conversion, effective thread counts, recovery
metadata, destination checks, file creation and overwriting, and service launch.
Platform effects use an internal adapter; production uses the existing storage
helpers and download manager, while workflow tests use controlled effects and
real temporary file contents.

Location and collision requests are opaque continuations. They retain the
workflow data needed to continue; callers present them and pass a chosen URI or
confirmation back. They cannot independently reconstruct the mission launch.
A running download allows unique naming only when a parent folder is available.
Picker descriptors can be saved independently of callbacks; a recreated dialog
queues the chosen URI until a new service-bound module can resume it. It never
retains the old fragment or its selection supplier.

Submission means `DownloadManagerService.startMission` returned from its
`Context.startService` call. The service later handles the intent on its handler;
for HLS it first prepares the media before registering the mission. Submission
is not a durable-registration or atomic-replacement guarantee, nor proof that
downloading has begun.

HLS preparation can still fail after submission; the service reports that failure
through its error notification, not through the dialog's dispatch-failure callback.

Preparation captures the selection and builds its complete launch plan before
creating, replacing, or truncating a file. Unsupported stream combinations fail
without changing the destination or mission metadata. Picker descriptors retain
that plan through recreation; changing the dialog selection does not alter it.
Mutable launch arrays are copied.

Each decision is consumed once. New save attempts invalidate earlier decisions,
and closing the module prevents old callbacks from submitting. Saved picker
state can be resumed by a fresh module, with repeated descriptors ignored within
that module. The dialog admits one outstanding picker, matches results to their launcher,
clears consumed, cancelled, or failed-launch picker state, and dismisses collision presentation
when its view is destroyed. Calls and decisions run on the UI thread.

Confirmation checks the current mission state again. A mission that starts while
an overwrite prompt is open receives a fresh running-mission decision; the old
confirmation cannot overwrite it. Folder capacity checks sum known, nonnegative
audio and video sizes (saturating on overflow), and also apply after folder
picking and before confirmation. Save-as document destinations do not receive
this folder-space check. These checks are best effort; they do not reserve space
or provide atomic admission against other writers.

Replacement mission metadata is forgotten only after preparation and dispatch
succeed. Dispatch failure reports an error rather than a started download.
Failure to clean up old metadata after accepted dispatch is logged without
reporting a false submission failure. Existing file bytes may already have been
truncated when dispatch fails; the workflow does not promise rollback of that
file or durable mission registration.

Media requests ask for identity encoding; a compressed response is rejected
because its body cannot safely be written at the requested byte offsets. Ranged
responses are checked against the requested offset, interval, declared size, and
known total before any body is written. Workers stop at the validated interval
and reject truncated bodies; invalid ranges fail the mission rather than
restarting a file shared with other workers. A single-worker resume may restart
from zero on an ignored range (HTTP 200) or unsatisfiable range (HTTP 416),
truncating stale destination bytes. An unknown-length restart reads to EOF
instead of treating earlier progress as the resource length. Local-server
coverage lives in `DownloadRangeIntegrityTest`; successful submission remains
distinct from successful transfer.

For progressive HTTP ranges, a strong ETag (or a Last-Modified date qualified
by a sufficiently later server Date when no ETag exists) binds partial bytes
to one resource URI. Workers and resumed requests send `If-Range` and check
response validators before writing. A changed parallel response fails the
mission without truncating its shared destination; the single-worker fallback
can replace a complete response only when no other workers write to the file.
Re-extraction to a different URL discards partial progress even if its ETag
text is identical: ETags are not globally unique. A saved mission with old,
unbound partial progress refuses to adopt a newly encountered strong validator.
Weak ETags and unqualified timestamps are not used for conditional ranges.
Without a usable validator, range/body checks still apply, but equal-sized
versions can be mixed if the server changes them between requests. The server
must also honor the validator's semantics; a falsely reused strong ETag is
not detectable from headers. Mission metadata writes are not transactional
with destination writes, so power-loss durability is not guaranteed.

Run `make ci` for formatting checks, lint, application unit tests, the extractor's
deterministic offline tests, and debug/release APK builds. Focused unit tests
include `DownloadPreparationTest` and `DownloadDialogPreparationTest`; neighboring
`DownloadMissionLifecycleTest` and `HlsPreparationLifecycleTest` protect background
phases. `DownloadPreparationIntegrationTest` is an Android instrumentation test
using real Android file helpers, isolated files, serialized picker state, and
controlled manager/dispatch effects; it is not run by `make ci`.
