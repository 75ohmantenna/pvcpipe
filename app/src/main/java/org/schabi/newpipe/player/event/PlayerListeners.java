package org.schabi.newpipe.player.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.playqueue.PlayQueue;

import java.util.Optional;

/**
 * The fragment and the activity that follow a player, and the updates the player sends them.
 * <p>
 * There is at most one of each. Every update goes to the fragment first and then to the
 * activity. A listener that was replaced or removed gets nothing more.
 */
public final class PlayerListeners {
    @Nullable
    private PlayerServiceEventListener fragmentListener = null;
    @Nullable
    private PlayerEventListener activityListener = null;

    public void setFragmentListener(@Nullable final PlayerServiceEventListener listener) {
        fragmentListener = listener;
    }

    /**
     * @param listener the listener to forget; another listener that has replaced it stays
     */
    public void removeFragmentListener(@Nullable final PlayerServiceEventListener listener) {
        if (fragmentListener == listener) {
            fragmentListener = null;
        }
    }

    public void setActivityListener(@Nullable final PlayerEventListener listener) {
        activityListener = listener;
    }

    /**
     * @param listener the listener to forget; another listener that has replaced it stays
     */
    public void removeActivityListener(@Nullable final PlayerEventListener listener) {
        if (activityListener == listener) {
            activityListener = null;
        }
    }

    @NonNull
    public Optional<PlayerServiceEventListener> getFragmentListener() {
        return Optional.ofNullable(fragmentListener);
    }

    /** Tells both listeners that the player service stopped, and forgets them. */
    public void onServiceStopped() {
        if (fragmentListener != null) {
            fragmentListener.onServiceStopped();
            fragmentListener = null;
        }
        if (activityListener != null) {
            activityListener.onServiceStopped();
            activityListener = null;
        }
    }

    public void onQueueUpdate(@NonNull final PlayQueue queue) {
        if (fragmentListener != null) {
            fragmentListener.onQueueUpdate(queue);
        }
        if (activityListener != null) {
            activityListener.onQueueUpdate(queue);
        }
    }

    public void onMetadataUpdate(@NonNull final StreamInfo info, @Nullable final PlayQueue queue) {
        if (fragmentListener != null) {
            fragmentListener.onMetadataUpdate(info, queue);
        }
        if (activityListener != null) {
            activityListener.onMetadataUpdate(info, queue);
        }
    }

    public void onPlaybackUpdate(final int state, final int repeatMode, final boolean shuffled,
                                 @NonNull final PlaybackParameters parameters) {
        if (fragmentListener != null) {
            fragmentListener.onPlaybackUpdate(state, repeatMode, shuffled, parameters);
        }
        if (activityListener != null) {
            activityListener.onPlaybackUpdate(state, repeatMode, shuffled, parameters);
        }
    }

    public void onProgressUpdate(final int currentProgress, final int duration,
                                 final int bufferPercent) {
        if (fragmentListener != null) {
            fragmentListener.onProgressUpdate(currentProgress, duration, bufferPercent);
        }
        if (activityListener != null) {
            activityListener.onProgressUpdate(currentProgress, duration, bufferPercent);
        }
    }

    public void onAudioTrackUpdate() {
        if (fragmentListener != null) {
            fragmentListener.onAudioTrackUpdate();
        }
        if (activityListener != null) {
            activityListener.onAudioTrackUpdate();
        }
    }

    /**
     * Player errors only concern the fragment.
     *
     * @param error                the error the player reported
     * @param isCatchableException whether the player handles the error by itself
     */
    public void onPlayerError(@NonNull final PlaybackException error,
                              final boolean isCatchableException) {
        if (fragmentListener != null) {
            fragmentListener.onPlayerError(error, isCatchableException);
        }
    }
}
