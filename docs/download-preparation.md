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
including BitChute's seed-host workaround. When HEAD is unavailable, a GET
probe requests only the first byte and closes the response after reading its
size headers.

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
- A mission that is done saves a last checkpoint and then tells the service
  (`DownloadMission.notifyFinished`). `DownloadManager.setFinished` writes
  the row in the finished-downloads database first, and only then drops the
  mission from the pending list and deletes its checkpoint.
- If the app stops between those steps, the next start finds a checkpoint
  that says "finished" and records it then.

The media file, the checkpoint and the finished-downloads database are three
separate things on disk. No transaction spans them.

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

`DownloadPreparationIntegrationTest` and `MissionFileSharingTest` are
instrumentation tests and need a device. Before running any instrumentation
on a phone that has the app installed, read
[local-playlist-mutations.md](local-playlist-mutations.md).

[pvcpipe-device-smoke.md](pvcpipe-device-smoke.md) records dated phone checks
of download pause/resume behavior and media checksums.
