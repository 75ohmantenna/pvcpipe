# Playback sources

`PlaybackSources` owns playback source selection and the policy shared by player
modes. `Player` supplies its current mode, quality and audio choices, delegates
resolution, and asks whether its current metadata needs reloading when video is
toggled. Queue preloading, expiration, timeline management and recovery remain
with their existing owners.

The module combines the audio and video selection implementations, live source
precedence, Rumble quality selection, audio fallback, subtitle merging and reload
policy. `PlaybackResolver` retains delivery and manifest construction.

## Internal seam

`Environment` substitutes Android preference-dependent selection and ExoPlayer
construction. `AndroidPlaybackEnvironment` binds the existing ListHelper and
PlayerDataSource operations; scripted tests record actual selected streams and
construction failures. Both tests and Player use resolve/requiresReload and the
same quality/audio setters. Device tests construct real progressive, merged and
HLS sources and queue wrappers without preparing or fetching media.

## Source-local decisions

Each successful source carries immutable selection-path classification in its
`StreamInfoTag`. This reflects live construction or its ordinary fallback; a
separated-audio path can also have no selected video stream. `withExtras`
preserves the classification when `LoadedMediaSource` installs queue extras.
Failed and overlapping resolutions cannot change an existing source's reload
decision. Unknown video source facts conservatively require reconstruction.

For incoming background resolution, the module uses the selected video and audio
for that request, including explicit alternate audio. An unselected video-only
variant does not force the preferred embedded-audio video to be fetched in the
background. Every video-player mode attempts live construction first, preserving
live quality across video toggles; a failed live attempt falls back once.

Rumble quality selection validates empty lists and invalid preferred indices.
A valid selected manifest passes explicitly to construction, while cached
StreamInfo retains its original URL. Invalid or absent variants keep that URL.
The dedicated audio player retains its existing live selection behavior.
