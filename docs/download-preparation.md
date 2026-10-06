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

Submission means that the Android service launch returned successfully. The
service constructs and registers its mission asynchronously, so submission is
not a durable-registration or atomic-replacement guarantee.

This structural change deliberately retains existing behavior for separate
failure correction: selection is read again after picker/confirmation callbacks,
launch planning occurs after truncation, and replaceable missions are forgotten
before reacquiring their output. Configured folders check available space and
remember media type; picked folders and Save As retain their existing branch
behavior. Tests characterize the workflow before these behaviors change.

Run `make ci` for formatting, lint, application and deterministic extractor tests,
and debug/release APK builds. Focused tests are `DownloadPreparationTest`;
neighboring `DownloadMissionLifecycleTest` and `HlsPreparationLifecycleTest`
continue to protect background phases.
