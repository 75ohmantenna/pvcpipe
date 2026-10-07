package org.schabi.newpipe.player.audio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;

import org.schabi.newpipe.player.helper.PlayerHelper;

import java.util.function.Consumer;

/** Executes Android focus, audio-session and animation effects for playback audio. */
final class AndroidPlaybackAudioEnvironment implements PlaybackAudio.Environment,
        AudioManager.OnAudioFocusChangeListener, AnalyticsListener {
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC;
    private final Context context;
    private final ExoPlayer player;
    private final AudioManager audioManager;
    private AudioFocusRequestCompat request;
    private Consumer<PlaybackAudio.FocusChange> focusListener;
    private boolean closed;

    AndroidPlaybackAudioEnvironment(final Context context, final ExoPlayer player) {
        this.context = context;
        this.player = player;
        audioManager = context.getSystemService(AudioManager.class);
    }

    @Override
    public void attach(final Consumer<PlaybackAudio.FocusChange> listener) {
        focusListener = listener;
        player.addAnalyticsListener(this);
        request = new AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this).build();
    }

    @Override
    public void onAudioFocusChange(final int change) {
        if (closed) {
            return;
        }
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                focusListener.accept(PlaybackAudio.FocusChange.GAIN);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                focusListener.accept(PlaybackAudio.FocusChange.LOSS);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                focusListener.accept(PlaybackAudio.FocusChange.TRANSIENT_LOSS);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                focusListener.accept(PlaybackAudio.FocusChange.DUCK);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean canReadVolume() {
        return commandAvailable(Player.COMMAND_GET_VOLUME, "get");
    }

    @Override
    public boolean setInternalVolume(final float volume) {
        if (!commandAvailable(Player.COMMAND_SET_VOLUME, "set")) {
            return false;
        }
        player.setVolume(volume);
        return true;
    }

    private boolean commandAvailable(final int command, final String type) {
        if (player.isCommandAvailable(command)) {
            return true;
        }
        Toast.makeText(context, type + " command for internal volume is not available",
                Toast.LENGTH_LONG).show();
        return false;
    }

    @Override
    public float volume() {
        return player.getVolume();
    }

    @Override
    public void setVolume(final float volume) {
        player.setVolume(volume);
    }

    @Override
    public boolean requestFocus() {
        return AudioManagerCompat.requestAudioFocus(audioManager, request)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override
    public void abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, request);
    }

    @Override
    public boolean resumeAfterFocusGain() {
        return PlayerHelper.isResumeAfterAudioFocusGain(context);
    }

    @Override
    public boolean playWhenReady() {
        return player.getPlayWhenReady();
    }

    @Override
    public void play() {
        player.play();
    }

    @Override
    public void pause() {
        player.pause();
    }

    @Override
    public Runnable animate(final float from, final float to, final Consumer<Float> volume) {
        final ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(1500);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(final Animator animation) {
                volume.accept(from);
            }

            @Override
            public void onAnimationCancel(final Animator animation) {
                volume.accept(to);
            }

            @Override
            public void onAnimationEnd(final Animator animation) {
                volume.accept(to);
            }
        });
        animator.addUpdateListener(animation ->
                volume.accept((float) animation.getAnimatedValue()));
        animator.start();
        return animator::cancel;
    }

    @Override
    public int systemVolume() {
        return audioManager.getStreamVolume(STREAM_TYPE);
    }

    @Override
    public void setSystemVolume(final int volume) {
        audioManager.setStreamVolume(STREAM_TYPE, volume, 0);
    }

    @Override
    public int maxSystemVolume() {
        return AudioManagerCompat.getStreamMaxVolume(audioManager, STREAM_TYPE);
    }

    @Override
    public void onAudioSessionIdChanged(@NonNull final EventTime eventTime,
                                        final int audioSessionId) {
        if (!closed) {
            notifyAudioSession(true, audioSessionId);
        }
    }

    @Override
    public void close() {
        closed = true;
        focusListener = null;
        player.removeAnalyticsListener(this);
        notifyAudioSession(false, player.getAudioSessionId());
    }

    private void notifyAudioSession(final boolean active, final int sessionId) {
        final Intent intent = new Intent(active
                ? AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                : AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        context.sendBroadcast(intent);
    }
}
