package org.schabi.newpipe.player.notification;

import static org.schabi.newpipe.player.helper.PlayerHelper.MinimizeMode.MINIMIZE_ON_EXIT_MODE_NONE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION;

import android.content.Intent;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.Player.RepeatMode;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.ui.PlayerUi;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NotificationPlayerUi extends PlayerUi {

    public NotificationPlayerUi(@NonNull final Player player) {
        super(player);
    }

    @Override
    public void destroy() {
        super.destroy();
        player.getService().hidePlayerNotification();
    }

    @Override
    public void onThumbnailLoaded(@Nullable final Bitmap bitmap) {
        super.onThumbnailLoaded(bitmap);
        player.getService().showPlayerNotification(false);
    }

    @Override
    public void onBlocked() {
        super.onBlocked();
        player.getService().showPlayerNotification(false);
    }

    @Override
    public void onPlaying() {
        super.onPlaying();
        player.getService().showPlayerNotification(false);
    }

    @Override
    public void onPaused() {
        super.onPaused();

        // Remove running notification when user does not want minimization to background or popup
        if (PlayerHelper.getMinimizeOnExitAction(context) == MINIMIZE_ON_EXIT_MODE_NONE
                && player.videoPlayerSelected() && !player.getCasting().isActive()) {
            player.getService().hidePlayerNotification();
        } else {
            player.getService().showPlayerNotification(false);
        }
    }

    @Override
    public void onPausedSeek() {
        super.onPausedSeek();
        player.getService().showPlayerNotification(false);
    }

    @Override
    public void onCompleted() {
        super.onCompleted();
        player.getService().showPlayerNotification(false);
    }

    @Override
    public void onRepeatModeChanged(@RepeatMode final int repeatMode) {
        super.onRepeatModeChanged(repeatMode);
        player.getService().showPlayerNotification(false);
    }

    @Override
    public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled);
        player.getService().showPlayerNotification(false);
    }

    @Override
    public void onBroadcastReceived(final Intent intent) {
        super.onBroadcastReceived(intent);
        if (ACTION_RECREATE_NOTIFICATION.equals(intent.getAction())) {
            player.getService().showPlayerNotification(false);
        }
    }

    @Override
    public void onMetadataChanged(@NonNull final StreamInfo info) {
        super.onMetadataChanged(info);
        player.getService().showPlayerNotification(false);
    }

    @Override
    public void onPlayQueueEdited() {
        super.onPlayQueueEdited();
        player.getService().showPlayerNotification(false);
    }

    public void createNotificationAndStartForeground() {
        player.getService().showPlayerNotification(true);
    }
}
