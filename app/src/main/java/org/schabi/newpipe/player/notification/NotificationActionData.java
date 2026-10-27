package org.schabi.newpipe.player.notification;

import static androidx.media3.common.Player.REPEAT_MODE_ALL;
import static androidx.media3.common.Player.REPEAT_MODE_ONE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_CLOSE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_FAST_FORWARD;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_FAST_REWIND;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_NEXT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PAUSE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PREVIOUS;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_REPEAT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_SHUFFLE;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.R;
import org.schabi.newpipe.player.Player;

import java.util.Objects;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NotificationActionData {

    @NonNull
    private final String action;
    @NonNull
    private final String name;
    @DrawableRes
    private final int icon;


    public NotificationActionData(@NonNull final String action, @NonNull final String name,
                                  @DrawableRes final int icon) {
        this.action = action;
        this.name = name;
        this.icon = icon;
    }

    @NonNull
    public String action() {
        return action;
    }

    @NonNull
    public String name() {
        return name;
    }

    @DrawableRes
    public int icon() {
        return icon;
    }


    @Nullable
    public static NotificationActionData fromNotificationActionEnum(
            @NonNull final Player player,
            @NotificationConstants.Action final int selectedAction
    ) {

        final int baseActionIcon = NotificationConstants.ACTION_ICONS[selectedAction];
        final Context ctx = player.getContext();

        switch (selectedAction) {
            case NotificationConstants.PREVIOUS:
                return new NotificationActionData(ACTION_PLAY_PREVIOUS,
                        ctx.getString(R.string.previous_stream), baseActionIcon);

            case NotificationConstants.NEXT:
                return new NotificationActionData(ACTION_PLAY_NEXT,
                        ctx.getString(R.string.next_stream), baseActionIcon);

            case NotificationConstants.REWIND:
                return new NotificationActionData(ACTION_FAST_REWIND,
                        ctx.getString(R.string.rewind), baseActionIcon);

            case NotificationConstants.FORWARD:
                return new NotificationActionData(ACTION_FAST_FORWARD,
                        ctx.getString(R.string.forward), baseActionIcon);

            case NotificationConstants.SMART_REWIND_PREVIOUS:
                if (player.getPlayQueue() != null && player.getPlayQueue().size() > 1) {
                    return new NotificationActionData(ACTION_PLAY_PREVIOUS,
                            ctx.getString(R.string.previous_stream), R.drawable.ic_previous);
                } else {
                    return new NotificationActionData(ACTION_FAST_REWIND,
                            ctx.getString(R.string.rewind), R.drawable.ic_fast_rewind);
                }

            case NotificationConstants.SMART_FORWARD_NEXT:
                if (player.getPlayQueue() != null && player.getPlayQueue().size() > 1) {
                    return new NotificationActionData(ACTION_PLAY_NEXT,
                            ctx.getString(R.string.next_stream), R.drawable.ic_next);
                } else {
                    return new NotificationActionData(ACTION_FAST_FORWARD,
                            ctx.getString(R.string.forward), R.drawable.ic_fast_forward);
                }

            case NotificationConstants.PLAY_PAUSE_BUFFERING:
                if (player.getCurrentState() == Player.STATE_PREFLIGHT
                        || player.getCurrentState() == Player.STATE_BLOCKED
                        || player.getCurrentState() == Player.STATE_BUFFERING) {
                    return new NotificationActionData(ACTION_PLAY_PAUSE,
                            ctx.getString(R.string.notification_action_buffering),
                            R.drawable.ic_hourglass_top);
                }

                // fallthrough
            case NotificationConstants.PLAY_PAUSE:
                if (player.getCurrentState() == Player.STATE_COMPLETED) {
                    return new NotificationActionData(ACTION_PLAY_PAUSE,
                            ctx.getString(R.string.replay),
                            R.drawable.ic_replay);
                } else if (player.isPlaying()
                        || player.getCurrentState() == Player.STATE_PREFLIGHT
                        || player.getCurrentState() == Player.STATE_BLOCKED
                        || player.getCurrentState() == Player.STATE_BUFFERING) {
                    return new NotificationActionData(ACTION_PLAY_PAUSE,
                            ctx.getString(R.string.pause), R.drawable.ic_pause);
                } else {
                    return new NotificationActionData(ACTION_PLAY_PAUSE,
                            ctx.getString(R.string.play), R.drawable.ic_play_arrow);
                }

            case NotificationConstants.REPEAT:
                if (player.getRepeatMode() == REPEAT_MODE_ALL) {
                    return new NotificationActionData(ACTION_REPEAT,
                            ctx.getString(R.string.repeat_all), R.drawable.ic_repeat_all);
                } else if (player.getRepeatMode() == REPEAT_MODE_ONE) {
                    return new NotificationActionData(ACTION_REPEAT,
                            ctx.getString(R.string.repeat_one), R.drawable.ic_repeat_one);
                } else /* player.getRepeatMode() == REPEAT_MODE_OFF */ {
                    return new NotificationActionData(ACTION_REPEAT,
                            ctx.getString(R.string.repeat_off), R.drawable.ic_repeat_off);
                }

            case NotificationConstants.SHUFFLE:
                if (player.getPlayQueue() != null && player.getPlayQueue().isShuffled()) {
                    return new NotificationActionData(ACTION_SHUFFLE,
                            ctx.getString(R.string.shuffle_on), R.drawable.ic_shuffle_on);
                } else {
                    return new NotificationActionData(ACTION_SHUFFLE,
                            ctx.getString(R.string.shuffle_off), R.drawable.ic_shuffle_off);
                }

            case NotificationConstants.CLOSE:
                return new NotificationActionData(ACTION_CLOSE, ctx.getString(R.string.close),
                        R.drawable.ic_close);

            case NotificationConstants.NOTHING:
            default:
                // do nothing
                return null;
        }
    }


    @Override
    public boolean equals(@Nullable final Object obj) {
        return (obj instanceof NotificationActionData other)
                && this.action.equals(other.action)
                && this.name.equals(other.name)
                && this.icon == other.icon;
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, name, icon);
    }
}
