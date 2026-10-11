package org.schabi.newpipe.player.broadcast;

import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_CLOSE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_FAST_FORWARD;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_FAST_REWIND;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_NEXT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PAUSE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PREVIOUS;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_REPEAT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_SHUFFLE;

import android.content.Intent;
import android.media.AudioManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.fragments.detail.VideoDetailFragment;

import java.util.List;

/**
 * The broadcasts the player listens for, and what each of them makes the player do.
 * <p>
 * It holds no Android objects, so the mapping can be tested without a device.
 * {@link AndroidPlayerBroadcasts} registers the receiver that feeds it.
 */
public final class PlayerBroadcasts {

    /** What a broadcast can ask of the player. */
    public interface Commands {
        void pause();

        void close();

        void playPause();

        void playPrevious();

        void playNext();

        void fastRewind();

        void fastForward();

        void cycleNextRepeatMode();

        void toggleShuffleModeEnabled();
    }

    /**
     * Every action the player's receiver is registered for. An action a player UI wants to see
     * has to be listed here even if the player itself ignores it, because the receiver is set up
     * once, before any UI is attached.
     */
    private static final List<String> ACTIONS = List.of(
            AudioManager.ACTION_AUDIO_BECOMING_NOISY,

            ACTION_CLOSE,
            ACTION_PLAY_PAUSE,
            ACTION_PLAY_PREVIOUS,
            ACTION_PLAY_NEXT,
            ACTION_FAST_REWIND,
            ACTION_FAST_FORWARD,
            ACTION_REPEAT,
            ACTION_SHUFFLE,
            ACTION_RECREATE_NOTIFICATION,

            VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED,
            VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED,

            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_HEADSET_PLUG);

    @NonNull
    private final Commands commands;
    private boolean screenOn = true;

    public PlayerBroadcasts(@NonNull final Commands commands) {
        this.commands = commands;
    }

    /**
     * @return the actions to register the receiver for
     */
    @NonNull
    public static List<String> actions() {
        return ACTIONS;
    }

    /**
     * @return whether the device screen is on, going by the last screen broadcast
     */
    public boolean isScreenOn() {
        return screenOn;
    }

    /**
     * Runs the player command that belongs to a received action, if it has one.
     *
     * @param action the action of the received broadcast
     */
    public void onBroadcast(@Nullable final String action) {
        if (action == null) {
            return;
        }

        switch (action) {
            case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                commands.pause();
                break;
            case ACTION_CLOSE:
                commands.close();
                break;
            case ACTION_PLAY_PAUSE:
                commands.playPause();
                break;
            case ACTION_PLAY_PREVIOUS:
                commands.playPrevious();
                break;
            case ACTION_PLAY_NEXT:
                commands.playNext();
                break;
            case ACTION_FAST_REWIND:
                commands.fastRewind();
                break;
            case ACTION_FAST_FORWARD:
                commands.fastForward();
                break;
            case ACTION_REPEAT:
                commands.cycleNextRepeatMode();
                break;
            case ACTION_SHUFFLE:
                commands.toggleShuffleModeEnabled();
                break;
            case Intent.ACTION_SCREEN_OFF:
                screenOn = false;
                break;
            case Intent.ACTION_SCREEN_ON:
                screenOn = true;
                break;
            default:
                break;
        }
    }
}
