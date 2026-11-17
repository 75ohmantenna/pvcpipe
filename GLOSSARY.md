# Glossary

PVCPipe plays remote streams and stores viewing data and settings locally.

## Backup and restore

**Backup archive**

A ZIP archive of PVCPipe's library database. The export also includes settings
in JSON and legacy serialized formats. Restoring settings is optional.
Downloads and download-manager state are outside the archive.

**Backup inspection**

An assessment of a selected backup archive that helps the user choose what to
restore. Inspection does not guarantee that restoration will succeed.

**Restore**

Staging a replacement library database from a backup archive. The app attempts
to activate it at the next startup, along with the selected settings.

**Accepted restore**

A restore request accepted by the application for completion even if the
settings screen closes, while the application process remains running.

**Pending database restore**

A published replacement database and settings intent awaiting startup recovery.
Startup either activates the replacement database and settings intent or
recovers the previous pair before database consumers can run.

## Downloads

**Download selection**

The media and options a user chooses for a local download.

**Download destination**

The folder or document chosen to hold a downloaded file.

**Download preparation**

Determining how to save a selected download as a local file and checking
whether its destination is available.

**Download collision**

A destination already used by an existing file or download.

**Submitted download**

A prepared download whose service-start request has been accepted.

## Subscription feeds

**Subscription**

A channel the user follows for stream updates.

**Service heading**

A section label grouping subscribed channels by their stored service ID.
These sections do not change membership in custom feed groups.

**Feed refresh**

An attempt to load and store stream updates for selected subscriptions.
A refresh may complete with failures for individual subscriptions.

**Feed update**

The stream and channel information collected for one subscription during a
feed refresh, including any extraction errors.

**Refresh result**

The collected feed updates and subscription failures after the refresh has
finished storing batches and cleaning up the feed.

**Accepted feed refresh**

A feed refresh accepted for completion even if observers detach, while the
application process remains running.

## Subscription transfers

**Subscription transfer**

Importing subscriptions from a channel source or saved file, or exporting followed
channels to a saved file.

## Playlists

**Remote playlist**

A playlist fetched from a supported service. A bookmark saves a reference to
the playlist.

**Local playlist**

An ordered collection of saved streams. A stream may appear more than once.

**Automatic playlist thumbnail**

An image associated with a stream still in the playlist. When that stream is
removed, the first remaining stream supplies the image. An empty playlist
uses the default image.

**Permanent playlist thumbnail**

A stream reference chosen to supply the thumbnail across playlist edits.
Displaying it still requires the stream metadata and image.

## Proof tokens

**Proof-token session**

The shared visitor data, streaming proof token, and generator used to produce
player proof tokens for individual video requests.

## Playback

**Playback source**

The selected remote media and stream metadata prepared for the player, including
video quality or audio-track selection where applicable.

**Playback history**

The recorded views and progress of played streams, including the saved position
used to resume unfinished playback.

**Playback snapshot**

Stream metadata, queue identity, and player positions captured together for a
playback-history decision.

**Accepted playback recording**

A view or progress update accepted for ordered database storage even if the
player closes, while the application process remains running.

**Playback audio**

The internal volume, mute state, and audio-focus behavior of local playback.
Android music-stream volume is device-wide. During an active cast, the
player's volume controls operate the receiver when supported.

**FCast receiver**

A device running FCast receiver software and accepting playback commands over
the local network.

**Cast session**

The app-owned receiver connection and playback handoff. The regular player
controls operate the receiver while casting. The connection persists after
the device picker closes.

**Cast media proxy**

The phone's per-load HTTP server that serves extracted media and rewritten
manifests to the receiver through opaque, allowlisted routes. FCast v4
encrypts control traffic. The media proxy uses unencrypted HTTP.
