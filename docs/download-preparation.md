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

The media file, the checkpoint and the finished-downloads database are separate
things on disk. Deletion also uses private receipts for removed finished media.
No transaction spans them.

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

"Delete" first hides an entry during its undo window. When deletion commits,
`DownloadManager.deleteMission` checks file/checkpoint deletion before removing
the entry, and catches provider/database runtime failures. A failure restores
the row and reports how many entries were kept for retry. Bulk deletion continues
past failed entries. Leaving the screen commits queued deletions through the same
checked path; it does not turn a failure into a successful removal.

For unfinished missions, deletion cancels queue/start intent and saves a
`deletionPending` marker before removing media. Busy writers must exit before
media deletion can proceed. After media removal, a second checkpoint records
`mediaDeleted` before checkpoint cleanup. Startup retains marked checkpoints without recreating
media or publishing a finished row; Start, queue, recovery and late completion
cannot resume them. Their row says **Deletion incomplete** and offers Delete
again. Checkpoint cleanup removes `.new`/`.bak` companions before the main file,
so a companion-removal failure retains the current marker. If saving the marker
fails, no media deletion is attempted, but a later restart can only use the older
checkpoint: that failed write does not establish durable cancellation.

The media file may already be gone when database/checkpoint cleanup fails. Retrying
is safe for an already absent direct file or a SAF document whose provider returns
an empty query result. A null, denied or failed provider query does not establish
absence. Finished-history loading retains such inaccessible entries and their URI
instead of treating them as missing. The app defers its own URI-grant release
until entry/checkpoint removal succeeds, but a DocumentsProvider can revoke a
grant automatically when it deletes a document. The saved `mediaDeleted` receipt
lets remaining cleanup retry without querying that URI. Finished missions use
private atomic receipts in `finished_download_deletions`, keyed by timestamp and
URI, so this also works after a database failure and manager reload. Receipt
cleanup after a successful database removal is best effort; new mission timestamps
avoid existing receipt keys. Providers that cannot establish absence
can leave an entry requiring a later retry; a generic provider error is not proof
that the file was erased. These operations still run synchronously and do not
form a transaction across media, checkpoints, receipts and the finished database.
If media removal succeeds but saving its receipt fails, the current process keeps
the result in memory for retry; process death in that gap cannot establish the
removed-file state on the next launch. Restoring provider access or durable storage
may still be necessary. The [process-death follow-up](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/download-preparation.md#deletion-process-death-verification-2026-10-30)
reproduces that uncertainty and verifies recovery after provider access is restored;
it does not remove the cross-provider transaction gap. Old app builds do not
understand these deletion receipts.

"Delete entry" keeps the file and is offered only for finished downloads.
`DownloadManager.deleteMission` also refuses entry-only removal for unfinished
missions, since their checkpoint could otherwise replay at startup. Old checkpoints
without the deletion marker default to normal behavior; earlier app builds do not
enforce the new marker. See the [dated deletion result](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/download-preparation.md#deletion-failure-verification-2026-10-30)
for the tested boundaries.

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

## SAF stream cleanup

`StoredFileHelper.getStream` uses `FileStreamSAF` for document URIs and
`FileStream` for direct paths. The SAF adapter's input/output streams share the
descriptor position; its output channel provides seeking and truncation where
the provider supports them. The stream wrappers borrow the descriptor, whose
owner is the `ParcelFileDescriptor` returned by the resolver.

`FileStreamSAF.close` attempts the channel, input, output and owning descriptor
in that order, even if an earlier close fails. It logs an `IOException` rather
than throwing it; later close failures are suppressed on the first exception.
An unchecked primary exception still propagates after the remaining cleanup
attempts. `isClosed()` records that closing was attempted, not that every
resource released successfully. This does not establish provider durability or
universal seek/truncate support.

## Validation

Run `make ci` for the JVM and build gate. Tests under
`app/src/test/java/us/shandian/giga/` cover mission lifecycle, range integrity,
checkpointing, deletion and UI commit policy; preparation tests are under
`app/src/test/java/org/schabi/newpipe/download/`.
Controlled native/HTTP drivers are indexed in [building.md](building.md#controlled-validation-tools).
They require isolated fixtures, not an existing user installation.
The [baseline download records](https://github.com/75ohmantenna/pvcpipe/blob/d9f00de9f26353e0f0d6a4bf68c2a4c2f5a678d2/docs/download-preparation.md#cold-process-restart-verification-2026-10-24)
preserve exact observations, failed attempts and their evidence limits.
