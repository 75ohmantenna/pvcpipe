package org.schabi.newpipe.player;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.schabi.newpipe.player.Player.STATE_BLOCKED;
import static org.schabi.newpipe.player.Player.STATE_BUFFERING;
import static org.schabi.newpipe.player.Player.STATE_COMPLETED;
import static org.schabi.newpipe.player.Player.STATE_PAUSED;
import static org.schabi.newpipe.player.Player.STATE_PAUSED_SEEK;
import static org.schabi.newpipe.player.Player.STATE_PLAYING;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.util.SponsorBlockPlaybackController;
import org.schabi.newpipe.util.SponsorBlockSegment;
import org.schabi.newpipe.util.SponsorBlockSettings;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class LocalPlayer implements androidx.media3.common.Player.Listener {
    private static final String TAG = "LocalPlayer";
    private static final int PROGRESS_LOOP_INTERVAL_MILLIS = 500;

    private final Context context;
    private final SharedPreferences mPrefs;
    private ExoPlayer simpleExoPlayer;
    private SerialDisposable progressUpdateReactor;
    private SponsorBlockSegment[] sponsorBlockSegments;
    private final SponsorBlockPlaybackController sponsorBlockPlayback =
            new SponsorBlockPlaybackController();
    private LocalPlayerListener listener;

    public LocalPlayer(final Context context) {
        this.context = context;
        this.mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public void initialize(final String uri, final SponsorBlockSegment[] segments) {
        this.sponsorBlockSegments = segments;
        sponsorBlockPlayback.reset();
        this.progressUpdateReactor = new SerialDisposable();

        simpleExoPlayer = new ExoPlayer
                .Builder(context)
                .setLoadControl(new org.schabi.newpipe.player.helper.LoadController())
                .build();
        simpleExoPlayer.addListener(this);
        simpleExoPlayer.setSeekParameters(PlayerHelper.getSeekParameters(context));
        simpleExoPlayer.setHandleAudioBecomingNoisy(true);
        simpleExoPlayer.setWakeMode(C.WAKE_MODE_NETWORK);

        final PlaybackParameters playbackParameters = simpleExoPlayer.getPlaybackParameters();
        final float speed = mPrefs.getFloat(context.getString(
                R.string.playback_speed_key), playbackParameters.speed);
        final float pitch = mPrefs.getFloat(context.getString(
                R.string.playback_pitch_key), playbackParameters.pitch);

        boolean defaultSkipSilence = false;
        defaultSkipSilence = simpleExoPlayer.getSkipSilenceEnabled();

        final boolean skipSilence = mPrefs.getBoolean(context.getString(
                R.string.playback_skip_silence_key), defaultSkipSilence);

        setPlaybackParameters(speed, pitch, skipSilence);

        final String autoPlayStr =
                mPrefs.getString(context.getString(R.string.autoplay_key), "");
        final boolean autoPlay =
                !autoPlayStr.equals(context.getString(R.string.autoplay_never_key));

        simpleExoPlayer.setPlayWhenReady(autoPlay);

        if (uri == null || uri.length() == 0) {
            return;
        }

        final MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .build();

        final MediaSource videoSource = new ProgressiveMediaSource
                .Factory(new DefaultDataSource.Factory(context,
                        new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                                .setUserAgent(DownloaderImpl.USER_AGENT)))
                .createMediaSource(mediaItem);

        simpleExoPlayer.addMediaSource(videoSource);
        simpleExoPlayer.prepare();
    }

    public ExoPlayer getExoPlayer() {
        return this.simpleExoPlayer;
    }

    public void setListener(final LocalPlayerListener listener) {
        this.listener = listener;
    }

    public void destroy() {
        simpleExoPlayer.removeListener(this);
        simpleExoPlayer.stop();
        simpleExoPlayer.release();
        progressUpdateReactor.dispose();
        listener = null;
    }

    public void setPlaybackParameters(final float speed, final float pitch,
                                      final boolean skipSilence) {
        final float roundedSpeed = Math.round(speed * 100.0f) / 100.0f;
        final float roundedPitch = Math.round(pitch * 100.0f) / 100.0f;

        mPrefs.edit()
                .putFloat(context.getString(R.string.playback_speed_key), speed)
                .putFloat(context.getString(R.string.playback_pitch_key), pitch)
                .putBoolean(context.getString(R.string.playback_skip_silence_key), skipSilence)
                .apply();

        simpleExoPlayer.setPlaybackParameters(
                new PlaybackParameters(roundedSpeed, roundedPitch));

        simpleExoPlayer.setSkipSilenceEnabled(skipSilence);
    }

    @Override
    public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
        switch (playbackState) {
            case androidx.media3.common.Player.STATE_IDLE:
                break;
            case androidx.media3.common.Player.STATE_BUFFERING:
                break;
            case androidx.media3.common.Player.STATE_READY:
                changeState(playWhenReady ? STATE_PLAYING : STATE_PAUSED);
                break;
            case androidx.media3.common.Player.STATE_ENDED:
                changeState(STATE_COMPLETED);
                break;
        }
    }

    private boolean isProgressLoopRunning() {
        return progressUpdateReactor.get() != null;
    }

    private void startProgressLoop() {
        progressUpdateReactor.set(getProgressReactor());
    }

    private void stopProgressLoop() {
        progressUpdateReactor.set(null);
    }

    private Disposable getProgressReactor() {
        return Observable.interval(PROGRESS_LOOP_INTERVAL_MILLIS, MILLISECONDS,
                AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> triggerProgressUpdate(),
                        error -> Log.e(TAG, "Progress update failure: ", error));
    }

    private void changeState(final int state) {
        switch (state) {
            case STATE_BLOCKED:
                onBlocked();
                break;
            case STATE_PLAYING:
                onPlaying();
                break;
            case STATE_BUFFERING:
                onBuffering();
                break;
            case STATE_PAUSED:
                onPaused();
                break;
            case STATE_PAUSED_SEEK:
                onPausedSeek();
                break;
            case STATE_COMPLETED:
                onCompleted();
                break;
        }
    }

    private void onBlocked() {
        if (!isProgressLoopRunning()) {
            startProgressLoop();
        }

        if (listener != null) {
            listener.onBlocked(simpleExoPlayer);
        }
    }

    private void onPlaying() {
        if (!isProgressLoopRunning()) {
            startProgressLoop();
        }

        if (listener != null) {
            listener.onPlaying(simpleExoPlayer);
        }
    }

    private void onBuffering() {
        if (listener != null) {
            listener.onBuffering(simpleExoPlayer);
        }
    }

    private void onPaused() {
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }

        if (listener != null) {
            listener.onPaused(simpleExoPlayer);
        }
    }

    private void onPausedSeek() {
        if (listener != null) {
            listener.onPausedSeek(simpleExoPlayer);
        }
    }

    private void onCompleted() {
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }

        if (listener != null) {
            listener.onCompleted(simpleExoPlayer);
        }
    }

    private void triggerProgressUpdate() {
        if (simpleExoPlayer == null) {
            return;
        }
        final int currentProgress = Math.max((int) simpleExoPlayer.getCurrentPosition(), 0);

        final SponsorBlockSettings sponsorBlockSettings =
                new SponsorBlockSettings(context, mPrefs);
        if (!sponsorBlockSettings.isEnabled()) {
            sponsorBlockPlayback.reset();
            return;
        }

        final SponsorBlockSegment segment =
                SponsorBlockSegment.at(sponsorBlockSegments, currentProgress);
        final long skipTarget = sponsorBlockPlayback.target(
                sponsorBlockSegments, currentProgress, false);
        if (segment == null || skipTarget < 0) {
            return;
        }

        // temporarily force EXACT seek parameters to prevent infinite skip looping
        final SeekParameters seekParams = simpleExoPlayer.getSeekParameters();
        simpleExoPlayer.setSeekParameters(SeekParameters.EXACT);

        seekTo(skipTarget);

        simpleExoPlayer.setSeekParameters(seekParams);

        if (sponsorBlockSettings.notificationsEnabled()) {
            Toast.makeText(context, segment.category().skipToast(context),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void seekTo(final long positionMillis) {
        if (simpleExoPlayer != null) {
            long normalizedPositionMillis = positionMillis;
            if (normalizedPositionMillis < 0) {
                normalizedPositionMillis = 0;
            } else if (normalizedPositionMillis > simpleExoPlayer.getDuration()) {
                normalizedPositionMillis = simpleExoPlayer.getDuration();
            }

            simpleExoPlayer.seekTo(normalizedPositionMillis);
        }
    }

}
