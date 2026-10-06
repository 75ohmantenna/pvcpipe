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

## Preserved behavior

This structural change retains the last video resolution's classification for
background routing and reload decisions. It also retains Rumble's existing cached
information mutation and unchecked quality index. These failures are reproduced
and corrected separately, rather than being hidden in the structural move.
