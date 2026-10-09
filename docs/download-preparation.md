# Downloads

`DownloadPreparation` turns a dialog selection into a submission plan; the
dialog owns presentation and document-picker callbacks. See
`app/src/main/java/org/schabi/newpipe/download/` for preparation and
`app/src/main/java/us/shandian/giga/` for mission execution and recovery.
Those implementations, not this guide, define stream compatibility, filename
and storage policy, HTTP range/validator checks, and postprocessing.

Submission means that the service start request was accepted, not that a
download has started or its metadata is durable. Output files can already be
modified when a later dispatch fails. Pending missions use atomic checkpoint
replacement so a failed write retains the previous readable snapshot. Before
notifying the service of completion, a mission saves a final checkpoint; the
manager persists the finished-history row before removing it. Startup can
replay a completed checkpoint after interruption. Destination media bytes,
checkpoint files, and the finished-history database are not one transaction.

For verification, `make ci` includes the application unit tests and APK builds.
`DownloadPreparationTest`, `DownloadMissionLifecycleTest`,
`DownloadManagerCheckpointTest`, and `UtilityAtomicCheckpointTest` cover the
preparation and recovery seams. `DownloadPreparationIntegrationTest` needs
Android instrumentation and is not included in `make ci`. On an existing
installation, read the [device-test safety record](local-playlist-mutations.md)
before selecting any instrumentation task.
