# PVCPipe

PVCPipe plays remote streams and stores viewing data and settings locally.

## Backup and restore

**Backup archive**:
A saved copy of PVCPipe's local database that may also contain application settings.

**Backup inspection**:
An assessment of a selected backup archive that helps the user choose what to
restore. Inspection does not guarantee that restoration will succeed.

**Restore**:
Replacement of PVCPipe's local database from a backup archive, optionally including
application settings.

**Accepted restore**:
A restore request accepted by the application for completion even if the
settings screen closes.

**Pending database restore**:
A replacement database prepared for activation when the application next starts.

## Downloads

**Download selection**:
The media and options a user chooses for a local download.

**Download destination**:
The folder or document chosen to hold a downloaded file.

**Download preparation**:
Determining how to save a selected download as a local file and checking
whether its destination is available.

**Download collision**:
A destination already used by an existing file or download.

**Submitted download**:
A prepared download handed off for background execution. Submission does not
mean the download has started or finished.

## Subscription feeds

**Subscription**:
A channel the user follows for stream updates.

**Feed refresh**:
An attempt to load and store stream updates for selected subscriptions.
A refresh may complete with failures for individual subscriptions.

**Feed update**:
The stream and channel information collected for one subscription during a
feed refresh, including any extraction errors.

**Refresh result**:
The collected feed updates and subscription failures after the refresh has
finished storing batches and cleaning up the feed.

**Accepted feed refresh**:
A feed refresh accepted for completion even if observers detach, while the
application process remains running.

## Subscription transfers

**Subscription transfer**:
Importing subscriptions from a channel source or saved file, or exporting followed
channels to a saved file.

## Local playlists

**Local playlist**:
An ordered collection of saved streams. A stream may appear more than once.

**Automatic playlist thumbnail**:
A playlist image chosen from its current streams, or the default image when empty.

**Permanent playlist thumbnail**:
A fixed playlist image retained independently of playlist contents.

## Proof tokens

**Proof-token session**:
The shared visitor data, streaming proof token, and generator used to produce
player proof tokens for individual video requests.

## Playback

**Playback source**:
The selected remote media and stream metadata prepared for the player, including
video quality or audio-track selection where applicable.

**Playback history**:
The recorded views and progress of played streams, including the saved position
used to resume unfinished playback.

**Playback snapshot**:
Stream metadata, queue identity, and player positions captured together for a
playback-history decision.

**Accepted playback recording**:
A view or progress update accepted for ordered database storage even if the
player closes, while the application process remains running.

**Playback audio**:
The internal volume, mute state, and audio-focus behavior of playback. Android
music-stream volume is a separate device-level control.
