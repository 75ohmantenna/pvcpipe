# PVCPipe

PVCPipe lets users watch remote streams and keep local viewing data and settings.

## Backup and restore

**Backup archive**:
A saved copy of PVCPipe's local database that may also contain application settings.

**Backup inspection**:
An assessment of a selected backup archive that informs the user's restore choices.
Inspection does not establish that the archive can be restored successfully.

**Restore**:
Replacement of PVCPipe's local database from a backup archive, optionally including
application settings.

**Accepted restore**:
A restore request admitted by the application for completion independently of the
settings screen's lifetime.

**Pending database restore**:
A replacement database prepared for activation when the application next starts.

## Downloads

**Download selection**:
The media and options a user chooses for a local download.

**Download destination**:
The folder or document chosen to hold a downloaded file.

**Download preparation**:
Determining how a download selection will become a local file and resolving
whether its destination can be used.

**Download collision**:
A destination already used by an existing file or download.

**Submitted download**:
A prepared download handed off for background execution.
Submission does not establish that the download has started or completed.

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
A feed refresh admitted for completion independently of its observers while the
application process remains running.

## Subscription transfers

**Subscription transfer**:
Importing subscriptions from a channel source or saved file, or exporting followed
channels to a saved file.

## Local playlists

**Local playlist**:
A saved ordered collection of streams. A stream may appear more than once.

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
its chosen video quality and audio track.

**Playback history**:
The recorded views and progress of played streams, including the saved position
used to resume unfinished playback.

**Playback snapshot**:
Stream metadata, queue identity and player positions captured together for a
playback-history decision.

**Accepted playback recording**:
A view or progress update admitted for ordered database storage independently
of the player's lifetime while the application process remains running.

**Playback audio**:
The internal volume, mute state and audio-focus behavior associated with playback.
Android music-stream volume is a separate device-level control.
