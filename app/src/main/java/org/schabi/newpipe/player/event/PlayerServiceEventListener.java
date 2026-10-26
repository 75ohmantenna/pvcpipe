package org.schabi.newpipe.player.event;

import androidx.media3.common.PlaybackException;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public interface PlayerServiceEventListener extends PlayerEventListener {
    void onViewCreated();

    void onFullscreenStateChanged(boolean fullscreen);

    void onScreenRotationButtonClicked();

    void onMoreOptionsLongClicked();

    void onPlayerError(PlaybackException error, boolean isCatchableException);

    void hideSystemUiIfNeeded();
}
