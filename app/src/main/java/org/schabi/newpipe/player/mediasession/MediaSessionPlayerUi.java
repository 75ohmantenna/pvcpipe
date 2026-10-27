package org.schabi.newpipe.player.mediasession;

import android.content.Intent;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackParameters;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.ui.PlayerUi;

/** Publishes application changes, including receiver-only changes, to the service session. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MediaSessionPlayerUi extends PlayerUi {
    public MediaSessionPlayerUi(final Player player) {
        super(player);
    }

    @Override
    public void destroyPlayer() {
        player.getService().detachSessionEngine();
    }

    private void refresh() {
        player.getService().refreshSession();
    }

    @Override
    public void initPlayer() {
        refresh();
    }

    @Override
    public void initPlayback() {
        refresh();
    }

    @Override
    public void onBlocked() {
        refresh();
    }

    @Override
    public void onPlaying() {
        refresh();
    }

    @Override
    public void onBuffering() {
        refresh();
    }

    @Override
    public void onPaused() {
        refresh();
    }

    @Override
    public void onPausedSeek() {
        refresh();
    }

    @Override
    public void onCompleted() {
        refresh();
    }

    @Override
    public void onCastingChanged() {
        refresh();
    }

    @Override
    public void onPlayQueueEdited() {
        refresh();
    }

    @Override
    public void onMetadataChanged(final StreamInfo info) {
        refresh();
    }

    @Override
    public void onThumbnailLoaded(@Nullable final Bitmap bitmap) {
        refresh();
    }

    @Override
    public void onRepeatModeChanged(final int repeatMode) {
        refresh();
    }

    @Override
    public void onShuffleModeEnabledChanged(final boolean enabled) {
        refresh();
    }

    @Override
    public void onPlaybackParametersChanged(final PlaybackParameters parameters) {
        refresh();
    }

    @Override
    public void onBroadcastReceived(final Intent intent) {
        refresh();
    }

    @Override
    public void onUpdateProgress(final int position, final int duration, final int bufferPercent) {
        player.getService().refreshSessionPosition();
    }
}
