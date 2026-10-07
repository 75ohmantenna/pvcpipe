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

Run `make ci` for formatting checks, lint, application unit tests, the extractor's
deterministic offline tests, and debug/release APK builds. Focused unit tests
include `DownloadPreparationTest` and `DownloadDialogPreparationTest`; neighboring
`DownloadMissionLifecycleTest` and `HlsPreparationLifecycleTest` protect background
phases. `DownloadPreparationIntegrationTest` is an Android instrumentation test
using real Android file helpers, isolated files, serialized picker state, and
controlled manager/dispatch effects; it is not run by `make ci`.
