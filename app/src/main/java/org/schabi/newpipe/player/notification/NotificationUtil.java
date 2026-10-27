package org.schabi.newpipe.player.notification;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_CLOSE;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.PendingIntentCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.media3.common.Player;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaStyleNotificationHelper.MediaStyle;

import com.google.common.collect.ImmutableList;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.player.PlayerIntentType;
import org.schabi.newpipe.player.PlayerService;
import org.schabi.newpipe.util.NavigationHelper;

/** Builds the service's single media notification without owning foreground state. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NotificationUtil implements MediaNotification.Provider {
    public static final int NOTIFICATION_ID = 123789;
    private final PlayerService service;

    public NotificationUtil(final PlayerService service) {
        this.service = service;
    }

    @Override
    public MediaNotification createNotification(final MediaSession session,
            final ImmutableList<CommandButton> buttons,
            final MediaNotification.ActionFactory actions,
            final MediaNotification.Provider.Callback callback) {
        return new MediaNotification(NOTIFICATION_ID, buildNotification(session, actions, buttons));
    }

    public Notification buildNotification(final MediaSession session,
            final MediaNotification.ActionFactory actions,
            final ImmutableList<CommandButton> buttons) {
        final var player = service.getPlayer();
        final var builder = setupNotificationBuilder(service)
                .setStyle(new MediaStyle(session));
        if (player != null) {
            builder.setColorized(player.getPrefs().getBoolean(
                    service.getString(R.string.notification_colorize_key), true));
            builder.setContentTitle(player.getVideoTitle()).setContentText(player.getUploaderName())
                    .setTicker(player.getVideoTitle())
                    .setContentIntent(contentIntent(player));
            setLargeIcon(builder, player);
            if (actions == null) {
                for (final int action : new int[] {NotificationConstants.PREVIOUS,
                        NotificationConstants.PLAY_PAUSE, NotificationConstants.NEXT}) {
                    final NotificationActionData data = NotificationActionData
                            .fromNotificationActionEnum(player, action);
                    builder.addAction(data.icon(), data.name(), PendingIntentCompat.getBroadcast(
                            service, NOTIFICATION_ID, new Intent(data.action())
                                    .setPackage(service.getPackageName()),
                            FLAG_UPDATE_CURRENT, false));
                }
                for (final CommandButton button : buttons) {
                    builder.addAction(button.iconResId, button.displayName,
                            PendingIntentCompat.getBroadcast(service, NOTIFICATION_ID,
                                    new Intent(button.sessionCommand.customAction)
                                            .setPackage(service.getPackageName()),
                                    FLAG_UPDATE_CURRENT, false));
                }
                builder.setStyle(new MediaStyle(session).setShowActionsInCompactView(0, 1, 2));
            } else {
                builder.addAction(actions.createMediaAction(session,
                        IconCompat.createWithResource(service, R.drawable.ic_previous),
                        service.getString(R.string.previous_stream),
                        Player.COMMAND_SEEK_TO_PREVIOUS));
                builder.addAction(actions.createMediaAction(session,
                        IconCompat.createWithResource(service, player.getPlayWhenReady()
                                ? R.drawable.ic_pause : R.drawable.ic_play_arrow),
                        service.getString(player.getPlayWhenReady()
                                ? R.string.pause : R.string.play),
                        Player.COMMAND_PLAY_PAUSE));
                builder.addAction(actions.createMediaAction(session,
                        IconCompat.createWithResource(service, R.drawable.ic_next),
                        service.getString(R.string.next_stream),
                        Player.COMMAND_SEEK_TO_NEXT));
                for (final CommandButton button : buttons) {
                    builder.addAction(actions.createCustomActionFromCustomCommandButton(
                            session, button));
                }
            }
        }
        return builder.build();
    }

    public PendingIntent contentIntent(final org.schabi.newpipe.player.Player player) {
        return PendingIntentCompat.getActivity(service, NOTIFICATION_ID,
                getIntentForNotification(player), FLAG_UPDATE_CURRENT, false);
    }

    @Override
    public boolean handleCustomCommand(final MediaSession session, final String action,
                                        final Bundle extras) {
        return service.dispatchSessionAction(action);
    }

    @Override
    public NotificationChannelInfo getNotificationChannelInfo() {
        return new NotificationChannelInfo(service.getString(R.string.notification_channel_id),
                service.getString(R.string.app_name));
    }

    private static NotificationCompat.Builder setupNotificationBuilder(final Context context) {
        return new NotificationCompat.Builder(context,
                context.getString(R.string.notification_channel_id))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setShowWhen(false)
                .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
                .setColor(context.getColor(R.color.dark_background_color))
                .setDeleteIntent(PendingIntentCompat.getBroadcast(context, NOTIFICATION_ID,
                        new Intent(ACTION_CLOSE).setPackage(context.getPackageName()),
                        FLAG_UPDATE_CURRENT, false));
    }

    private Intent getIntentForNotification(final org.schabi.newpipe.player.Player player) {
        if (player.audioPlayerSelected() || player.popupPlayerSelected()) {
            // Means we play in popup or audio only. Let's show the play queue
            return NavigationHelper.getPlayQueueActivityIntent(player.getContext());
        } else {
            // We are playing in fragment. Don't open another activity just show fragment. That's it
            final Intent intent = NavigationHelper.getPlayerIntent(
                    player.getContext(), MainActivity.class, null,
                    PlayerIntentType.AllOthers);
            intent.putExtra(org.schabi.newpipe.player.Player.RESUME_PLAYBACK, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            return intent;
        }
    }


    private void setLargeIcon(final NotificationCompat.Builder builder,
                              final org.schabi.newpipe.player.Player player) {
        final boolean showThumbnail = player.getPrefs().getBoolean(
                player.getContext().getString(R.string.show_thumbnail_key), true);
        final Bitmap thumbnail = player.getThumbnail();
        if (thumbnail == null || !showThumbnail) {
            // since the builder is reused, make sure the thumbnail is unset if there is not one
            builder.setLargeIcon((Bitmap) null);
            return;
        }

        final boolean scaleImageToSquareAspectRatio = player.getPrefs().getBoolean(
                player.getContext().getString(R.string.scale_to_square_image_in_notifications_key),
                false);
        if (scaleImageToSquareAspectRatio) {
            builder.setLargeIcon(getBitmapWithSquareAspectRatio(thumbnail));
        } else {
            builder.setLargeIcon(thumbnail);
        }
    }

    private Bitmap getBitmapWithSquareAspectRatio(@NonNull final Bitmap bitmap) {
        // Find the smaller dimension and then take a center portion of the image that
        // has that size.
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int dstSize = Math.min(w, h);
        final int x = (w - dstSize) / 2;
        final int y = (h - dstSize) / 2;
        return Bitmap.createBitmap(bitmap, x, y, dstSize, dstSize);
    }
}
