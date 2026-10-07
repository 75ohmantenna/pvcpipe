package org.schabi.newpipe.player.audio;

import android.content.Context;

import com.google.android.exoplayer2.ExoPlayer;

import java.util.function.Consumer;

/** Owns playback audio policy; callers never save volume or coordinate focus themselves. */
public final class PlaybackAudio {
    private static final float DUCK_VOLUME = 0.2f;
    private final Environment environment;
    private float desiredVolume;
    private float lastAudibleVolume;
    private boolean muted;
    private boolean ducked;
    private boolean resumePending;
    private boolean disposed;
    private long generation;
    private Runnable cancelAnimation;

    public PlaybackAudio(final Context context, final ExoPlayer player) {
        this(new AndroidPlaybackAudioEnvironment(context, player));
    }

    PlaybackAudio(final Environment environment) {
        this.environment = environment;
        desiredVolume = environment.canReadVolume() ? environment.volume() : 1;
        lastAudibleVolume = desiredVolume > 0 ? desiredVolume : 1;
        muted = environment.volume() == 0;
        environment.attach(this::onFocusChange);
    }

    public enum Event {
        INITIALIZED, PREPARED_PLAYING, PLAY_REQUESTED, PAUSE_REQUESTED, TOGGLE_MUTE
    }

    /**
     * Applies audio effects before the caller's ordinary playback operation.
     * Call on the player application thread; dispose separately on that thread.
     * @param event the playback intention or preparation fact
     */
    public void onPlaybackEvent(final Event event) {
        if (disposed) {
            return;
        }
        switch (event) {
            case INITIALIZED:
                environment.setInternalVolume(outputVolume());
                break;
            case PREPARED_PLAYING:
            case PLAY_REQUESTED:
                resumePending = false;
                if (!isMuted()) {
                    requestFocus();
                }
                break;
            case PAUSE_REQUESTED:
                resumePending = false;
                cancelRestoration();
                environment.abandonFocus();
                break;
            case TOGGLE_MUTE:
                toggleMute();
                break;
            default:
                break;
        }
    }

    public boolean isMuted() {
        return muted || desiredVolume == 0;
    }

    /**
     * Returns the user's level, including mute, independently of temporary focus attenuation.
     * @return the chosen level, or the existing 1.0 fallback when reading volume is unavailable
     */
    public float getInternalVolume() {
        if (!disposed && !environment.canReadVolume()) {
            return 1;
        }
        return isMuted() ? 0 : desiredVolume;
    }

    /**
     * Changes the user level; accepted changes supersede prior restoration callbacks.
     * @param volume the requested level, clamped to the range 0 to 1; NaN is rejected
     */
    public void setInternalVolume(final float volume) {
        if (disposed) {
            return;
        }
        if (Float.isNaN(volume)) {
            throw new IllegalArgumentException("Internal volume must not be NaN");
        }
        final float level = Math.max(0, Math.min(1, volume));
        final float output = ducked ? Math.min(level, DUCK_VOLUME) : level;
        if (!environment.setInternalVolume(output)) {
            return;
        }
        final boolean wasMuted = isMuted();
        cancelRestoration();
        desiredVolume = level;
        muted = level == 0;
        if (level > 0) {
            lastAudibleVolume = level;
        }
        reconcileMuteFocus(wasMuted);
    }

    public int getSystemVolume() {
        return environment.systemVolume();
    }

    public void setSystemVolume(final int volume) {
        if (!disposed) {
            environment.setSystemVolume(volume);
        }
    }

    public int getMaxSystemVolume() {
        return environment.maxSystemVolume();
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        resumePending = false;
        cancelRestoration();
        environment.abandonFocus();
        environment.close();
    }

    private void toggleMute() {
        final boolean wasMuted = isMuted();
        final float target = desiredVolume > 0 ? desiredVolume : lastAudibleVolume;
        final float output = wasMuted ? (ducked ? Math.min(target, DUCK_VOLUME) : target) : 0;
        if (!environment.setInternalVolume(output)) {
            return;
        }
        cancelRestoration();
        muted = !wasMuted;
        if (wasMuted) {
            desiredVolume = target;
        }
        reconcileMuteFocus(wasMuted);
    }

    private void reconcileMuteFocus(final boolean wasMuted) {
        if (isMuted()) {
            resumePending = false;
            if (!wasMuted) {
                environment.abandonFocus();
            }
        } else if (wasMuted && environment.playWhenReady()) {
            requestFocus();
        }
    }

    private void requestFocus() {
        final long owner = generation;
        final boolean granted = environment.requestFocus();
        if (granted && !disposed && owner == generation && !isMuted()) {
            // An immediate grant need not produce a later Android gain callback.
            onFocusChange(FocusChange.GAIN);
        }
    }

    private float outputVolume() {
        return isMuted() ? 0 : ducked ? Math.min(desiredVolume, DUCK_VOLUME) : desiredVolume;
    }

    private void onFocusChange(final FocusChange change) {
        if (disposed) {
            return;
        }
        cancelRestoration();
        switch (change) {
            case GAIN:
                ducked = false;
                final long owner = generation;
                final boolean resume = resumePending && !isMuted()
                        && environment.resumeAfterFocusGain();
                resumePending = false;
                if (!isMuted()) {
                    restoreVolume();
                }
                if (resume && !disposed && owner == generation && !isMuted() && !ducked) {
                    environment.play();
                }
                break;
            case LOSS:
            case TRANSIENT_LOSS:
                resumePending |= !isMuted() && environment.playWhenReady();
                environment.pause();
                break;
            case DUCK:
                ducked = true;
                if (!isMuted()) {
                    environment.setVolume(outputVolume());
                }
                break;
            default:
                break;
        }
    }

    private void restoreVolume() {
        final float from = environment.volume();
        if (from == desiredVolume) {
            return;
        }
        final long owner = generation;
        final Runnable cancellation = environment.animate(from, desiredVolume, value -> {
            if (!disposed && owner == generation && !isMuted() && !ducked) {
                environment.setVolume(value);
            }
        });
        if (!disposed && owner == generation) {
            cancelAnimation = cancellation;
        } else {
            cancellation.run();
        }
    }

    private void cancelRestoration() {
        generation++;
        final Runnable cancellation = cancelAnimation;
        cancelAnimation = null;
        if (cancellation != null) {
            // Invalidate first: Android cancellation synchronously emits terminal callbacks.
            cancellation.run();
        }
    }

    enum FocusChange {
        GAIN, LOSS, TRANSIENT_LOSS, DUCK
    }

    /** Internal execution seam: production Android effects and controlled test effects. */
    interface Environment {
        void attach(Consumer<FocusChange> focusListener);

        boolean canReadVolume();

        boolean setInternalVolume(float volume);

        float volume();

        void setVolume(float volume);

        boolean requestFocus();

        void abandonFocus();

        boolean resumeAfterFocusGain();

        boolean playWhenReady();

        void play();

        void pause();

        Runnable animate(float from, float to, Consumer<Float> volume);

        int systemVolume();

        void setSystemVolume(int volume);

        int maxSystemVolume();

        void close();
    }
}
