# Downloads

A download goes through two parts of the code:

- `app/src/main/java/org/schabi/newpipe/download/` prepares it.
  `DownloadPreparation` turns what the user picked in the download dialog
  into a plan and hands it to the download service. The dialog shows things
  and receives the answers of the system's folder and document pickers.
- `app/src/main/java/us/shandian/giga/` carries it out. `DownloadMission` is
  one download, `DownloadManager` keeps the pending and finished ones, and
  `DownloadManagerService` runs them.

Which streams can be combined, how files are named and where they go, the
range and validator checks on a resumed download, and the processing after a
download are all in that code. This page covers what the code promises about
state on disk.

## What "submitted" means

A download is submitted once `DownloadManagerService.startMission` has
accepted the request to start the service. It has not started yet, and
nothing about it is saved yet. If a later step of the same submission fails,
an output file it was to overwrite may already have been changed.

## Checkpoints

A pending mission saves its state as a checkpoint file in the app's internal
storage, `files/pending_downloads`. A checkpoint is a serialized object that
names the addresses to download and the file to write, so it must not be in
a place another app can write to. Builds before October 2026 kept
checkpoints in the external files directory. That directory is not read any
more, and unfinished downloads from such a build are not carried over.

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

An HLS rendition is downloaded segment by segment and then joined by
`PvcFromHlsRemuxer`, which runs the bundled ffmpeg.

- `PvcHlsPreProcessor` reads the playlist and replaces the mission's one
  address with the addresses of the segments. It fetches playlists and
  encryption keys as bytes, because a key is binary.
- Each conversion works in a directory of its own under `ffmpeg_temp` and
  removes it when it ends, also after a failure. Several HLS downloads can
  be converted at the same time. A conversion that starts while no other is
  running first removes whatever is left in `ffmpeg_temp`: files of a build
  that had one shared directory, or of a process that was killed half way.
- A segment that the server refuses fails the mission with the HTTP error,
  and the mission keeps its segments. `DownloadMissionRecover` is not used
  for HLS: it can only find a whole progressive file again. A retry asks for
  the same segment addresses, so it does not help once they have expired.
  Reading the playlist again is not implemented.

The Ogg conversion of WebM audio refuses a file that uses block lacing.
`WebMReader` does not take laced blocks apart, and writing one as a single
packet would give a file that does not play.

## Sharing or opening a finished file

Sharing a download that was saved by path, or opening it in another app,
grants read access to that one file.

- `MissionFileProvider` hands out a `content://` address with a random token
  in place of the path. It never exposes the folder the file is in.
- The file stays where it is, on internal or removable storage. No copy is
  made.
- The token and the path are stored in a private preferences file, so the
  other app can still open the file after the chooser or PVCPipe's process
  is gone.
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

[pvcpipe-device-smoke.md](pvcpipe-device-smoke.md) records downloads that
were paused, resumed and checked against their checksums on a phone.
