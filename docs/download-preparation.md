# Downloads

Download preparation and execution are separate:

- `app/src/main/java/org/schabi/newpipe/download/` prepares the request.
  `DownloadPreparation` turns the dialog's selection into a plan, resolves
  destination conflicts and submits it to the download service. The dialog
  presents choices and receives folder/document picker results.
- `app/src/main/java/us/shandian/giga/` executes it. `DownloadMission` owns
  one download, `DownloadManager` tracks pending and finished missions, and
  `DownloadManagerService` runs them.

Preparation accepts progressive media URLs, combined video HLS renditions and
AAC audio HLS renditions labeled M4A. Split progressive video/audio can require
postprocessing; arbitrary DASH manifests and other HLS audio codecs are not
accepted by this preparation path.
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

Completed combined HLS video and M4A-labeled AAC HLS audio are prepared with Media3's `HlsPlaylistParser` and
remuxed by `PvcFromHlsRemuxer` with the bundled FFmpeg. Master playlists remain
selection errors. Streams identified as live/ongoing and video-only HLS are rejected before
destination/picker effects; MP3/Opus/unknown-codec HLS audio and HLS subtitles
are not offered. Audio media playlists must also have an explicit ENDLIST tag;
a live snapshot is rejected during mission preparation.

- `PvcHlsPreProcessor` parses the streamed successful response against its
  effective URL after redirects. It retains Media3-resolved byte ranges,
  initialization maps, key URLs and explicit/sequence-derived AES IVs.
  Unencrypted and AES-128 media are supported; unsupported DRM/SAMPLE-AES fails
  before mission mutation. Encryption keys remain binary bytes.
- `HlsDownloadPlan` persists ordered logical segment/map windows over
  insertion-ordered, URL-deduplicated **physical** resources. Workers download
  each physical object in full using the existing resume/integrity architecture.
  Reused objects are not downloaded once per window. This can transfer unused
  edge bytes; it is not a minimal-range network downloader.
- Remuxing slices non-owning windows before decryption, prepends each segment's
  initialization map, and finishes all input reads before in-place output copying.
  Invalid/truncated windows and nonzero FFmpeg exits fail rather than produce
  successful partial output. Concat timing uses parsed HLS segment durations:
  absolute fMP4 decode times must not become extra gaps.
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
- Existing serialized dependency-segment lists migrate once to whole-object
  windows with identity resource mapping. Their URLs, offsets, recovery entries
  and worker checkpoints are not deduplicated or discarded. The remuxer's old
  serialization UID remains pinned so those missions can still deserialize.
  Old encrypted checkpoints with **implicit IVs** did not retain media-sequence
  identity; they fail with an explicit-IV error rather than invent a sequence.
  Explicit-IV encrypted and unencrypted legacy checkpoints remain supported.

### Audio HLS — 2026-11-13

The audio dropdown admits URL-based HLS AAC streams declared M4A, including
SoundCloud's AAC 96/160 kbps variants. Existing progressive choices and defaults
are preserved, including unknown-language groups. Only progressive audio can
be paired with progressive video-only downloads; an unpairable HLS language displays a warning and requires an explicit
"Download video without audio" confirmation before destination preparation. It
does not silently substitute another language. Existing intentional video-only
downloads without a new HLS audio track remain available. Playlist headers
are never used as media sizes. Audio HLS uses a checked bitrate/duration
estimate in bytes, or retains unknown size.

Audio HLS uses the same durable playlist/plan checkpoints, whole-resource resume,
byte windows, initialization maps and identity AES-128 handling as video HLS.
The persisted mission kind selects mandatory audio mapping, no video, AAC
bitstream validation and MP4 container output, saved with `.m4a`/`audio/mp4`.
Missing audio or non-AAC content fails conversion before output copying. The
bundled native build adds the AAC demuxer for raw ADTS segments; MPEG-TS and
fragmented MP4 inputs remain supported without re-encoding. See `app/libs/README.md`.

Controlled host tests exercise generated ADTS, MPEG-TS and fMP4 audio, explicit
no-video output, duration/container/codec checks, restored plans and rejection
without overwriting output. These are not live SoundCloud or physical Android
converter results. Fixtures are generated sine waves, not third-party recordings.

WebM Opus-to-Ogg conversion rejects block-laced input. `WebMReader` does not
split laced blocks into packets, so treating a whole block as one packet
would produce an invalid output file.

### Controlled HLS conversion follow-up — 2026-10-30

The pinned dependency consumed whole resources where the three logical windows
required `ABCD`, `XYZ` and `12`. The new production-path consumer regressions
verify those exact bytes, shared-stream ownership, restored plans, legacy
checkpoints, sequence-300 AES, encrypted maps, truncation and failure atomicity.

Generated eight-second TS and fragmented-MP4/map fixtures were parsed through the
real preprocessor and remuxed through the same converter with a host FFmpeg
executor. `ffprobe` found both video and audio: TS output was **8.000000s** and
fragmented MP4 **8.000313s**, within the 0.25s acceptance window. The earlier
untimed fMP4 concat produced 14.112917s; that failing regression was retained and
corrected, not relaxed. Nonzero real FFmpeg conversion also failed without
overwriting the output.

`HlsDownloadIntegrationTest` additionally covers real mission workers, Android
`RunFFmpeg`, restored missions and decoded reopening; the native player harness
covers rendition/caption behavior. Both compile, but were **not device-executed**
in this follow-up: no device was attached and no emulator binary/KVM was available.
Host conversion is not Android decoder or receiver evidence. Generated fixture
commands and timestamp origins are in `app/src/androidTest/assets/media3-engine/README.txt`.

### Android converter integration review — 2026-10-30

A later disposable API 26 emulator run reached the real mission workers and
bundled binary. The fMP4/map case failed with FFmpeg status 183: the bundled
minimal build had no MOV/MP4 demuxer. Enabling it exposed the additional H.264
framing filter required by concat, which had also been disabled (status 8).
The [vendored converter and source-pinned recipe](../app/libs/README.md) now
enable both components for all four ABIs and verify the artifact in `make ci`.
Its Java wrapper and metadata remain unchanged. A controlled run of the rebuilt
Android executable produced an 8.000313s MP4 containing both video and audio.
The packaged app then passed all four `HlsDownloadIntegrationTest` cases on the
disposable emulator, including restored fMP4 missions and decoded reopening;
the combined native-player/download selection passed 21 tests. This is controlled
Android evidence, separate from the earlier host-only run and real hardware.


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

### URL share previews (2026-11-13)

URL sharing keeps its text/title and chooser permissions. Cached image lookup,
decode, bounded rendering, JPEG creation, URI canonicalization and provider MIME
lookup run on one background worker. No network image request is made. Preview
bitmaps are at most 1024 pixels per edge and encoded files at most 2 MiB; cache
owned images are not recycled or modified. Small hardware bitmaps get a bounded
software copy; oversized hardware or non-cloneable drawable images use the disk
cache when available, otherwise sharing falls back to text.

Only one preview is prepared at a time, with at most one pending request. A newer
share, source pause/destruction or changed detail/player content prevents an old
request from launching a chooser. Service/popup contexts remain supported. A
published preview is not removed by the chooser's own lifecycle transition.

Each preview has a unique file, so a later share cannot overwrite an earlier
recipient's image. Recent files are retained instead of evicted to admit new
ones. Admission is limited to 64 cache entries and 32 MiB, with a full new-file
allowance reserved; when full, sharing uses text only. Generated files older
than seven days are eligible for cleanup on a later share. This is cache data:
Android may remove it earlier, and URI access still depends on Android grants.
Canceled unpublished output is removed on the worker.

Controlled JVM/Robolectric regressions cover request ownership, real host bitmap
rendering/files, retained URIs and chooser grants. They are not a physical-device
latency measurement or verification of a particular OEM share sheet.

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

## Data-sync foreground timeout (2026-11-13)

The download service handles Android's data-sync timeout without waiting for a
bound Downloads screen to disconnect. It closes download admission, stops its
foreground/started lifetime promptly, and pauses network missions and writes their
checkpoints off the main thread. Queue, connectivity and binder calls cannot
restart work while the timeout gate is closed. Explicit user resume is accepted
only after the app is foreground again and the pause has completed; it restores
the service's started lifetime as well as its foreground notification.

A manager interrupted by timeout remains the sole in-process owner of its files
across immediate service recreation. Its handler is replaced with a manager-owned
completion sink while detached, then rebound to the new service. This prevents a
second deserialized mission from writing alongside a worker still stopping.

New HLS requests are checkpointed with their original playlist before preparation.
Preparation is an initializer owned by the mission, and cancellation closes the
active HTTP call. Late results cannot publish into a paused/replaced initializer.
The prepared segment plan must be checkpointed before downloads begin. Older,
already-prepared checkpoints retain their existing behavior and are not reparsed.

Existing same-file post-processing is deliberately not forcibly interrupted:
that would damage the source it is rewriting. It may finish in-process after the
foreground service has stopped, and its completion is still recorded. This does
not guarantee conversion survives process death or an indefinite I/O stall; those
remain existing recovery/error boundaries, not resumable conversion guarantees.

Focused JVM regressions cover admission, detached completion, started-lifetime
call ordering, checkpoint serialization and stalled playlist/key HTTP cancellation
with fresh retries. These checks alone do not establish Android scheduling.
The native direct-callback follow-up below covers bound timeout/resume/unbind;
the platform deadline itself has not been forced.

### Audio HLS native device follow-up (2026-11-13)

A physical Pixel 6a running Android 17/API 37 executed the rebuilt ARM64
converter pinned in `app/libs/README.md`. Synthetic manifest byte windows,
including the fMP4 initialization map, were staged with their playlist durations.
Production-equivalent concat/copy arguments, mandatory audio mapping, `-vn`,
`aac_adtstoasc` and MP4 output produced one AAC audio track and no video for
ADTS (8.021333 seconds), fMP4 (8.021333 seconds) and MPEG-TS (8.000000 seconds).
No-audio and Opus inputs were rejected with exit code 234.

The isolated temporary device files were removed and their absence verified;
app data, settings and UI were unchanged. This verifies the shipped ARM64 native
conversion path, not the full Java mission/service integration, Android decoding
or live SoundCloud downloads. Those remain separately unverified for this change.

### Native timeout lifecycle follow-up (2026-11-13)

`DownloadTimeoutDeviceTest` passed on a physical Pixel 6a running Android 17/API
37 in an explicitly opted-in, newly installed verification package. Two cycles
invoked the service timeout callback twice, waited for a readable checkpoint,
resumed from a real foreground About activity, and removed the final test binding.
Native ActivityManager state confirmed the started/foreground lifetime remained
active after each unbind; the warm service and manager identity were retained.
The controlled loopback download finished with exactly 2,097,289 expected bytes
and retired its checkpoint files. The non-skipped result markers reported two
cycles and exact-byte verification; the run took 5.194 seconds and the maximum
paired callback invocation took 7 ms on that run, not a timing guarantee.

The first attempt found a test assertion error: checkpoint storage must pass
through `StoredFileHelper.deserialize` before its transient URI representation is
used. The corrected test passed; no production defect was established by that
failure. This is a direct callback test, not evidence of Android delivering its
six-hour budget timeout, process-death recovery, or conversion completion after
service timeout. Existing JVM tests cover additional failure/admission paths.

The disposable debug target and instrumentation packages were removed after the
run and their absence verified. No existing package was updated or removed, no
permission was granted, and no global timeout/device setting was changed.
