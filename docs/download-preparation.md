# Downloads

Download preparation and execution are separate:

- `app/src/main/java/org/schabi/newpipe/download/` prepares the request.
  `DownloadPreparation` turns the dialog's selection into a plan, resolves
  destination conflicts and submits it to the download service. The dialog
  presents choices and receives folder/document picker results.
- `app/src/main/java/us/shandian/giga/` executes it. `DownloadMission` owns
  one download, `DownloadManager` tracks pending and finished missions, and
  `DownloadManagerService` runs them.

Preparation accepts progressive media URLs and video HLS rendition URLs.
Split progressive video/audio can require postprocessing; arbitrary DASH
manifests and audio-only HLS are not accepted by this preparation path.
The implementation defines format combinations, filenames, destination
handling, resume checks and postprocessing. This guide focuses on selection,
submission and persisted state.

`DownloadPreparation.Selection` captures the selected streams, sizes and
primary media format before destination preparation. The format comes from
the dialog's `StreamInfoWrapper`, including a format inferred by its HTTP
header probe when the immutable extractor stream has none. Filename, MIME
type, postprocessing and recovery use that captured format; later dialog
selection changes do not alter the pending plan.

Size probes use the same service-specific user agent as media downloads,
including BitChute's seed-host workaround. For hosts marked HEAD-unusable,
the dialog uses a GET probe requesting only the first byte and closes the
response after reading its size headers. Other HEAD failures leave the size
unknown; this dialog probe does not automatically retry them with GET.

## What "submitted" means

A download is submitted after `DownloadManagerService.startMission` accepts
the service-start request. Submission does not guarantee that execution has
started or that a checkpoint has been saved. Destination preparation happens
first: an existing file selected for overwrite may already have been changed
if dispatch then fails.

## Checkpoints

A pending mission stores a serialized checkpoint under the app's private
`files/pending_downloads` directory. It contains media URLs and the output
destination, so it must not be writable by another app. Older builds used the
external files directory; that legacy location is no longer read, and its
unfinished downloads are not migrated.

- A checkpoint replaces the previous one atomically (`Utility.writeToFile`
  uses Android's `AtomicFile`). A write that fails leaves the previous
  checkpoint readable.
- A mission that is done attempts a last checkpoint, then tells the service
  (`DownloadMission.notifyFinished`) even if that write fails.
  `DownloadManager.setFinished` writes the row in the finished-downloads
  database first, and only then drops the mission from the pending list and
  deletes its checkpoint.
- If the app stops between those steps and the final checkpoint was saved,
  the next start finds a checkpoint that says "finished" and records it then.
  If the final write failed, only an earlier checkpoint is available; it
  does not necessarily describe the completed file.

The media file, the checkpoint and the finished-downloads database are three
separate things on disk. No transaction spans them.

## Ranged response integrity and Start

An HTTP range response must identify the requested byte interval and resource
length, use identity encoding, and agree with its advertised content length.
When a strong validator is available, `ResourceIdentity` binds it to both the
requested URL and the final effective URL after redirects. Equal ETag text on
different resources does not establish interchangeable bytes
([RFC 9110, section 8.8.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.1)).
Partial responses with changed destinations or validators are rejected before writing.
A missing response validator can still be accepted when the same resource
honors the sent `If-Range` condition
([section 13.1.5](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.5)).

Normally **Start** resumes the saved mission. An initialized mission's
range-integrity failure records a clean-restart requirement that later worker
errors cannot erase. Start waits for its previous initializer and workers to
stop, discards the current resource's progress and identity, and uses one
fallback worker. Before replacing media bytes, it must save the cleared-progress
checkpoint; a failed save stops the restart and leaves the old file untouched.
Completed earlier resources are retained.

This fallback stays full-response-only until the current resource finishes or is reset.
Its GETs send neither `Range` nor `If-Range`, and an unsolicited 206 is rejected.
Each accepted full response truncates the current resource before replacement.
A transport retry or pause/resume fetches the whole current resource again,
rather than trusting a partially written replacement or stale saved offset.
This can lose partial progress on an unstable connection; ordinary direct and
ranged missions keep their existing resume behavior. Transport retries retain
the existing limit, and integrity errors do not create an automatic restart loop.

Controlled API 26 service verification on 2026-10-24 reproduced the original
rejection and repeated Start failure, then verified clean-restart completion
with exact bytes for both identical redirect content and different same-length
content reusing an ETag. It also verified live pause/resume with readable
checkpoints and pause-then-delete cancellation, not Android process-death replay.
These are controlled HTTP peers, not evidence that the failed live YouTube
destinations served equal bytes. The original smoke did not record their
requested/effective URLs or validators.

The controlled peers correctly ignored Range on HEAD
([section 14.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-14.2)).
Those fresh missions selected single-worker fallback; parallel range and
partial-state integrity are separately covered by JVM HTTP fixtures. This does
not establish correct parallel-capability detection for every host.

## File names

A download name is one path component. Titles come from uploaders and the
filename settings can come from an import, so the name is checked in three
places:

- `FilenameUtils.createFilename` checks the stored settings each time it reads
  them. A custom charset that is not a valid pattern falls back to the default
  charset; a valid one is kept. A replacement containing `/`, `\` or NUL falls
  back to `_`. Path separators are replaced even when a custom pattern keeps
  them. The settings screen refuses such a replacement too.
- `DownloadPreparation.save` fails with `FILE_CREATION` before any picker or
  file change when the constructed filename (base name plus suffix) is not a
  plain file name. A missing or blank base name fails earlier as
  `INVALID_SELECTION`.
- `StoredDirectoryHelper` and the path-based `StoredFileHelper` constructor
  refuse empty names, `.`, `..` and names containing a separator or NUL. The
  constructor checks before `Files.deleteIfExists`, so a refused name changes
  nothing outside the folder. Recovery (`DownloadManager.tryRecover`) creates
  files through the same check. Names that only contain dots, such as
  `..video.mp4`, are allowed.

## Removing entries

"Delete" removes an entry and requests deletion of its file. The manager
does not check the file-deletion result, so a removed entry does not prove
the file was erased. "Delete entry" keeps the file and is offered only for
finished downloads. An unfinished mission can still own workers or a
checkpoint; removing only its entry would not stop those workers or prevent
checkpoint replay at the next start. `DownloadManager.deleteMission` refuses
entry-only removal for unfinished missions, as well as the menu hiding it.

## HLS downloads

An HLS media playlist is downloaded segment by segment, then remuxed with
the bundled ffmpeg by `PvcFromHlsRemuxer`. `PvcHlsPreProcessor` rejects master
playlists: the selected download must identify a rendition. The detail screen
hides the download action for livestreams, including BitChute and Kick live
broadcasts.

- `PvcHlsPreProcessor` reads the playlist and replaces the mission's one
  address with the addresses of the segments. It fetches playlists and
  encryption keys as bytes, because a key is binary.
- Each conversion works in a directory of its own under `ffmpeg_temp` and
  attempts to remove it when it ends, also after a failure. Several HLS
  downloads can be converted at the same time. A conversion that starts while
  no other is running first attempts to remove leftovers in `ffmpeg_temp`:
  files left by an older build's shared directory or by an interrupted
  process. Cleanup is best effort (`PvcFromHlsRemuxer.deleteQuietly`).
- A segment that the server refuses fails the mission with the HTTP error,
  and the mission keeps its segments. `DownloadMissionRecover` is not used
  for HLS: it can only find a whole progressive file again. A retry asks for
  the same segment addresses, so it does not help once they have expired.
  Reading the playlist again is not implemented.

WebM Opus-to-Ogg conversion rejects block-laced input. `WebMReader` does not
split laced blocks into packets, so treating a whole block as one packet
would produce an invalid output file.

## Sharing or opening a finished file

Sharing a download that was saved by path, or opening it in another app,
grants read access to that one file.

- `MissionFileProvider` hands out a `content://` address with a random token
  in place of the path. It never exposes the folder the file is in.
- The file stays where it is, on internal or removable storage. No copy is
  made.
- The token-to-path mapping is stored in private preferences and survives
  PVCPipe process death. The recipient still needs Android's read URI grant;
  saving the mapping does not make that grant permanent or extend its
  Android-managed lifetime.
- If the path no longer points to a regular file, the address does not open.
- A download saved through the Storage Access Framework is shared by its
  original document address and does not go through this provider.
- Preview thumbnails for the share sheet come from a separate `FileProvider`
  that covers only the `share_preview` cache folder.

## Tests

`make ci` runs the unit tests: `DownloadPreparationTest`,
`DownloadMissionLifecycleTest`, `DownloadManagerCheckpointTest` and
`UtilityAtomicCheckpointTest` cover preparation, checkpoints and recovery.
`DownloadRangeIntegrityTest` checks real local HTTP responses and file bytes,
including redirect identity, range bounds, clean restarts after stopped writers,
checkpoint-save failure, late errors, replacement offsets, and serialized
full-response-only recovery.
`DownloadManagerDeleteEntryTest` covers removing entries, and
`FilenameUtilsTest` and `StoredFileNameContainmentTest` cover file names.

`DownloadPreparationIntegrationTest` and `MissionFileSharingTest` are
instrumentation tests and need a device. Before running any instrumentation
on a phone that has the app installed, read
[local-playlist-mutations.md](local-playlist-mutations.md).

These tests cover controlled preparation and persistence scenarios, not
every host's media URLs, Android process-death timing or live HLS expiration.

[pvcpipe-device-smoke.md](pvcpipe-device-smoke.md) records dated phone checks
of download pause/resume behavior and media checksums.
