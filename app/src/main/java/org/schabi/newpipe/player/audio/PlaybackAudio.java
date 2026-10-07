package org.schabi.newpipe.player.audio;

import android.content.Context;

import com.google.android.exoplayer2.ExoPlayer;

import java.util.function.Consumer;

/** Owns playback audio policy; callers never save volume or coordinate focus themselves. */
public final class PlaybackAudio {
    private static final float DUCK_VOLUME = 0.2f;
    private final Environment environment;
    private float savedVolume;

    public PlaybackAudio(final Context context, final ExoPlayer player) {
        this(new AndroidPlaybackAudioEnvironment(context, player));
    }

    PlaybackAudio(final Environment environment) {
        this.environment = environment;
        savedVolume = environment.internalVolume();
        environment.attach(this::onFocusChange);
    }

    public enum Event {
        INITIALIZED, PREPARED_PLAYING, PLAY_REQUESTED, PAUSE_REQUESTED, TOGGLE_MUTE
    }

    /**
     * Applies audio effects before the caller's ordinary playback operation.
     * Call on the player application thread, including initialization and disposal.
     * @param event the playback intention or preparation fact
     */
    public void onPlaybackEvent(final Event event) {
        switch (event) {
            case INITIALIZED:
                setMuted(isMuted());
                break;
            case PREPARED_PLAYING:
            case PLAY_REQUESTED:
                if (!isMuted()) {
                    environment.requestFocus();
                }
                break;
            case PAUSE_REQUESTED:
                environment.abandonFocus();
                break;
            case TOGGLE_MUTE:
                final boolean wasMuted = isMuted();
                setMuted(!wasMuted);
                if (wasMuted) {
                    environment.requestFocus();
                } else {
                    environment.abandonFocus();
                }
                break;
            default:
                break;
        }
    }

    public boolean isMuted() {
        return environment.volume() == 0;
    }

    public float getInternalVolume() {
        return environment.internalVolume();
    }

    /**
     * Changes internal volume and owns the restore bookkeeping previously required by gestures.
     * @param volume the requested internal volume
     */
    public void setInternalVolume(final float volume) {
        environment.setInternalVolume(volume);
        saveVolume();
    }

    public int getSystemVolume() {
        return environment.systemVolume();
    }

    public void setSystemVolume(final int volume) {
        environment.setSystemVolume(volume);
    }

    public int getMaxSystemVolume() {
        return environment.maxSystemVolume();
    }

    public void dispose() {
        environment.abandonFocus();
        environment.close();
    }

    private void setMuted(final boolean muted) {
        if (muted) {
            if (environment.internalVolume() != 0) {
                saveVolume();
                environment.setInternalVolume(0);
            }
        } else {
            environment.setInternalVolume(savedVolume);
        }
    }

    private void saveVolume() {
        savedVolume = environment.internalVolume();
    }

    private void onFocusChange(final FocusChange change) {
        switch (change) {
            case GAIN:
                environment.setVolume(DUCK_VOLUME);
                environment.animate(DUCK_VOLUME, savedVolume, environment::setVolume);
                if (environment.resumeAfterFocusGain()) {
                    environment.play();
                }
                break;
            case LOSS:
            case TRANSIENT_LOSS:
                saveVolume();
                environment.pause();
                break;
            case DUCK:
                saveVolume();
                environment.setVolume(DUCK_VOLUME);
                break;
            default:
                break;
        }
    }

    enum FocusChange {
        GAIN, LOSS, TRANSIENT_LOSS, DUCK
    }

    /** Internal execution seam: production Android effects and controlled test effects. */
    interface Environment {
        void attach(Consumer<FocusChange> focusListener);

        float internalVolume();

        boolean setInternalVolume(float volume);

        float volume();

        void setVolume(float volume);

        void requestFocus();

        void abandonFocus();

        boolean resumeAfterFocusGain();

        void play();

        void pause();

        Runnable animate(float from, float to, Consumer<Float> volume);

        int systemVolume();

        void setSystemVolume(int volume);

        int maxSystemVolume();

        void close();
    }
}
