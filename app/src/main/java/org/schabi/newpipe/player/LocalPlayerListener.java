package org.schabi.newpipe.player;

import androidx.media3.exoplayer.ExoPlayer;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public interface LocalPlayerListener {
    void onBlocked(ExoPlayer player);
    void onPlaying(ExoPlayer player);
    void onBuffering(ExoPlayer player);
    void onPaused(ExoPlayer player);
    void onPausedSeek(ExoPlayer player);
    void onCompleted(ExoPlayer player);
}
