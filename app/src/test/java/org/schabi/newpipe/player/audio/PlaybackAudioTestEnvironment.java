package org.schabi.newpipe.player.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class PlaybackAudioTestEnvironment implements PlaybackAudio.Environment {
    float volume = 1;
    boolean getAvailable = true;
    boolean setAvailable = true;
    boolean resume;
    int systemVolume = 5;
    final List<String> effects = new ArrayList<>();
    final List<Fade> fades = new ArrayList<>();
    private Consumer<PlaybackAudio.FocusChange> listener;

    @Override
    public void attach(final Consumer<PlaybackAudio.FocusChange> focusListener) {
        listener = focusListener;
    }

    void focus(final PlaybackAudio.FocusChange change) {
        listener.accept(change);
    }

    @Override
    public float internalVolume() {
        if (!getAvailable) {
            effects.add("get-unavailable");
            return 1;
        }
        return volume;
    }

    @Override
    public boolean setInternalVolume(final float level) {
        if (!setAvailable) {
            effects.add("set-unavailable");
            return false;
        }
        setVolume(level);
        return true;
    }

    @Override
    public float volume() {
        return volume;
    }

    @Override
    public void setVolume(final float level) {
        volume = level;
        effects.add("volume:" + level);
    }

    @Override
    public void requestFocus() {
        effects.add("request-focus");
    }

    @Override
    public void abandonFocus() {
        effects.add("abandon-focus");
    }

    @Override
    public boolean resumeAfterFocusGain() {
        return resume;
    }

    @Override
    public void play() {
        effects.add("play");
    }

    @Override
    public void pause() {
        effects.add("pause");
    }

    @Override
    public Runnable animate(final float from, final float to, final Consumer<Float> output) {
        final Fade fade = new Fade(to, output);
        fades.add(fade);
        output.accept(from);
        return fade::cancel;
    }

    @Override
    public int systemVolume() {
        return systemVolume;
    }

    @Override
    public void setSystemVolume(final int level) {
        systemVolume = level;
    }

    @Override
    public int maxSystemVolume() {
        return 10;
    }

    @Override
    public void close() {
        effects.add("close");
    }

    static final class Fade {
        private final float target;
        private final Consumer<Float> output;
        boolean canceled;

        Fade(final float target, final Consumer<Float> output) {
            this.target = target;
            this.output = output;
        }

        void frame(final float level) {
            output.accept(level);
        }

        void finish() {
            output.accept(target);
        }

        void cancel() {
            canceled = true;
            // ValueAnimator sends cancel and end callbacks synchronously.
            finish();
            finish();
        }
    }
}
