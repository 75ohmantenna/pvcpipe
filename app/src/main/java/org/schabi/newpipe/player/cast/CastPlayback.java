package org.schabi.newpipe.player.cast;

import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.PlaybackParameters;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.notification.NotificationPlayerUi;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Owns the remote playback handoff for one player service, on the application thread. */
public final class CastPlayback implements FCastSession.Listener {
    private final Player player;
    private final FCastSession session;
    private final SerialDisposable preparation = new SerialDisposable();
    private final List<Runnable> listeners = new ArrayList<>();
    private final PowerManager.WakeLock wakeLock;
    @Nullable private CastMediaResolver resolver;
    @Nullable private CastMediaResolver preparingResolver;
    @Nullable private CastMediaResolver previousResolver;
    @Nullable private MediaItemTag loadedTag;
    @Nullable private PlayQueueItem loadedItem;
    private boolean active;
    private boolean preparing;
    private boolean closed;
    private boolean playWhenReady;
    private boolean ended;
    private boolean reloadOnConnection;
    private int generation;
    private long position;
    private long duration;
    private long updateTime;
    private float speed = 1;
    private PlaybackParameters parameters = PlaybackParameters.DEFAULT;
    private double volumeBeforeMute = 1;
    @Nullable private String error;

    public CastPlayback(final Player player) {
        this.player = player;
        session = new FCastSession(player.getContext(), this);
        final PowerManager power = (PowerManager) player.getContext()
                .getSystemService(android.content.Context.POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PVCPipe:FCast");
        wakeLock.setReferenceCounted(false);
    }

    public FCastSession getSession() {
        return session;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPreparing() {
        return preparing;
    }

    public boolean getPlayWhenReady() {
        return playWhenReady;
    }

    public long getPosition() {
        final long elapsed = active && playWhenReady && !preparing && !ended
                ? (long) ((SystemClock.elapsedRealtime() - updateTime) * speed) : 0;
        final long value = Math.max(0, position + elapsed);
        return duration > 0 ? Math.min(value, duration) : value;
    }

    public long getDuration() {
        return duration;
    }

    public float getSpeed() {
        return speed;
    }

    public PlaybackParameters getParameters() {
        return parameters;
    }

    @Nullable
    public String getError() {
        return error;
    }

    public void addListener(final Runnable listener) {
        listeners.add(listener);
        listener.run();
    }

    public void removeListener(final Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (final Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    public void connect(final FCastDevice device) {
        prepareConnection();
        session.connect(device);
    }

    public void connect(final String host, final int port) {
        prepareConnection();
        session.connect(host, port);
    }

    public void connectUrl(final String url) {
        prepareConnection();
        session.connectUrl(url);
    }

    private void prepareConnection() {
        error = null;
        final long currentPosition = getPosition();
        cancelPreparation();
        reloadOnConnection = true;
        if (active) {
            position = currentPosition;
            updateTime = SystemClock.elapsedRealtime();
            preparing = true;
            player.onCastingStateChanged(Player.STATE_BUFFERING);
        }
    }

    @Override
    public void onConnectionChanged() {
        if (closed) {
            return;
        }
        if (session.getConnectionState() == 2) {
            final boolean reload = reloadOnConnection;
            reloadOnConnection = false;
            loadCurrent(reload);
        } else if (session.getConnectionState() == 0) {
            returnToDevice(false);
        }
        player.onCastingChanged();
        notifyListeners();
    }

    /** Called when local source metadata changes, including quality/audio changes and failures. */
    public void onSourceChanged() {
        if (session.getConnectionState() == 2) {
            loadCurrent(false);
        }
    }

    private void loadCurrent(final boolean force) {
        final StreamInfo info = player.getCurrentStreamInfo().orElse(null);
        final MediaItemTag tag = player.getCurrentMetadata();
        if (info == null || tag == null || tag == loadedTag && !force || closed) {
            return;
        }
        if (session.getLocalAddress() == null) {
            onError(player.getContext().getString(R.string.fcast_no_route));
            return;
        }
        final boolean continuing = active && loadedTag != null
                && loadedTag.getStreamUrl().equals(tag.getStreamUrl());
        final long start = continuing ? getPosition() : player.getExoPlayer().getCurrentPosition();
        final boolean shouldPlay = active ? playWhenReady : player.getPlayWhenReady();
        if (continuing && !force && canControlReceiver()) {
            // Freeze the old source at the position used by a quality/audio replacement.
            session.pause();
        }
        cancelPreparation();
        loadedTag = tag;
        loadedItem = player.getCurrentItem();
        preparing = true;
        playWhenReady = shouldPlay;
        if (active) {
            // Pending controls belong to this load, not to the receiver's previous source.
            position = start;
            duration = Math.max(0, info.getDuration() * 1000L);
            updateTime = SystemClock.elapsedRealtime();
        }
        ended = false;
        final int request = generation;
        final CastMediaResolver next = new CastMediaResolver(player.getContext());
        preparingResolver = next;
        if (active) {
            player.onCastingStateChanged(Player.STATE_BUFFERING);
        }
        preparation.set(Single.fromCallable(() ->
                        next.resolve(info, tag, session.getLocalAddress()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(media -> {
                    if (closed || request != generation || session.getConnectionState() != 2) {
                        next.close();
                        return;
                    }
                    if (previousResolver != null) {
                        previousResolver.close();
                    }
                    previousResolver = resolver;
                    resolver = next;
                    preparingResolver = null;
                    // Local playback continues during preparation; take its handoff position now.
                    if (!active) {
                        position = player.getCurrentPosition();
                    }
                    duration = Math.max(0, info.getDuration() * 1000L);
                    speed = player.getPlaybackSpeed();
                    parameters = new PlaybackParameters(speed);
                    playWhenReady = active ? playWhenReady : player.getPlayWhenReady();
                    updateTime = SystemClock.elapsedRealtime();
                    active = true;
                    player.pauseLocalForCasting();
                    player.UIs().get(NotificationPlayerUi.class)
                            .ifPresent(NotificationPlayerUi::createNotificationAndStartForeground);
                    if (!wakeLock.isHeld()) {
                        wakeLock.acquire();
                    }
                    final String thumbnail = info.getThumbnails().isEmpty()
                            ? null : info.getThumbnails().get(0).getUrl();
                    session.load(media, position, speed, playWhenReady, info.getName(), thumbnail);
                    player.onCastingStateChanged(Player.STATE_BUFFERING);
                    player.onCastingChanged();
                    player.onPlaybackParametersChanged(parameters);
                    notifyListeners();
                }, failure -> {
                    if (!closed && request == generation) {
                        onError(player.getContext().getString(R.string.fcast_source_error)
                                + "\n" + failure.getMessage());
                    }
                }));
        notifyListeners();
    }

    /** Invalidate work before the player replaces its queue/timeline. */
    public void onPlayerReset() {
        cancelPreparation();
        loadedTag = null;
        loadedItem = null;
        ended = false;
        if (active) {
            if (!closed && session.getConnectionState() == 2) {
                session.pause();
            }
            position = 0;
            duration = 0;
            preparing = true;
        }
    }

    /**
     * During an active cast, quality reloads keep the item; another occurrence invalidates ownership.
     *
     * @param item the selected queue occurrence, not merely its video URL
     */
    public void onQueueItemChanged(final PlayQueueItem item) {
        if (active && loadedItem != item) {
            onPlayerReset();
        }
    }

    public void setPlayWhenReadyForSource(final boolean ready) {
        playWhenReady = ready;
    }

    private void cancelPreparation() {
        generation++;
        preparation.set(null);
        if (preparingResolver != null) {
            preparingResolver.close();
            preparingResolver = null;
        }
        preparing = false;
    }

    private boolean canControlReceiver() {
        return !closed && session.getConnectionState() == 2 && !reloadOnConnection
                && preparingResolver == null && loadedTag != null;
    }

    public void play() {
        if (ended) {
            ended = false;
            seek(0);
        }
        position = getPosition();
        updateTime = SystemClock.elapsedRealtime();
        playWhenReady = true;
        if (canControlReceiver()) {
            session.resume();
        }
        player.onCastingStateChanged(preparing ? Player.STATE_BUFFERING : Player.STATE_PLAYING);
        notifyListeners();
    }

    public void pause() {
        position = getPosition();
        playWhenReady = false;
        if (canControlReceiver()) {
            session.pause();
        }
        player.onCastingStateChanged(Player.STATE_PAUSED);
        player.saveStreamProgressState();
        notifyListeners();
    }

    public void seek(final long target) {
        position = duration > 0 ? Math.min(duration, Math.max(0, target)) : Math.max(0, target);
        if (duration <= 0 || position < duration) {
            ended = false;
        }
        updateTime = SystemClock.elapsedRealtime();
        if (canControlReceiver()) {
            session.seek(position);
        }
        player.triggerProgressUpdate();
        notifyListeners();
    }

    public void setSpeed(final float value) {
        position = getPosition();
        updateTime = SystemClock.elapsedRealtime();
        speed = value;
        parameters = new PlaybackParameters(value);
        if (canControlReceiver()) {
            session.setSpeed(value);
        }
        player.onPlaybackParametersChanged(parameters);
        player.onCastingChanged();
        notifyListeners();
    }

    public void toggleMute() {
        if (!canControlReceiver()) {
            return;
        }
        if (session.getVolume() > 0) {
            volumeBeforeMute = session.getVolume();
            session.setVolume(0);
        } else {
            session.setVolume(volumeBeforeMute);
        }
    }

    public void stop(final boolean playHere) {
        returnToDevice(playHere);
        session.disconnect(true);
        player.onCastingChanged();
        notifyListeners();
    }

    private void returnToDevice(final boolean playHere) {
        final boolean wasActive = active;
        final long resumePosition = getPosition();
        if (wasActive) {
            player.saveStreamProgressState();
        }
        active = false;
        reloadOnConnection = false;
        cancelPreparation();
        loadedTag = null;
        loadedItem = null;
        if (resolver != null) {
            resolver.close();
            resolver = null;
        }
        if (previousResolver != null) {
            previousResolver.close();
            previousResolver = null;
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wasActive && !closed) {
            player.restoreLocalAfterCasting(resumePosition, playHere);
        }
    }

    @Override
    public void onPlaybackChanged(final int state, final long positionMs,
                                  final long durationMs, final double playbackSpeed) {
        if (!active || !canControlReceiver() || ended) {
            return;
        }
        position = positionMs >= 0 ? positionMs : getPosition();
        if (durationMs > 0) {
            duration = durationMs;
        }
        // A finite protocol double can underflow to zero or overflow when narrowed for ExoPlayer.
        final float nextSpeed = (float) playbackSpeed;
        if (nextSpeed > 0 && Float.isFinite(nextSpeed)) {
            speed = nextSpeed;
            if (parameters.speed != speed) {
                parameters = new PlaybackParameters(speed);
                player.onPlaybackParametersChanged(parameters);
                player.onCastingChanged();
            }
        }
        updateTime = SystemClock.elapsedRealtime();
        preparing = state == 3;
        // Buffering describes readiness, not the user's play/pause intent.
        if (state != 3) {
            playWhenReady = state == 1;
        }
        player.onCastingStateChanged(state == 1 ? Player.STATE_PLAYING
                : state == 3 ? Player.STATE_BUFFERING : Player.STATE_PAUSED);
        player.triggerProgressUpdate();
        notifyListeners();
    }

    @Override
    public void onStopped() {
        if (closed || !active || reloadOnConnection || session.getConnectionState() != 2) {
            return;
        }
        returnToDevice(false);
        session.disconnect(false);
        player.onCastingChanged();
        notifyListeners();
    }

    @Override
    public void onVolumeChanged(final double volume) {
        player.onCastingChanged();
        notifyListeners();
    }

    @Override
    public void onEnded() {
        if (!active || ended || !canControlReceiver()) {
            return;
        }
        ended = true;
        position = duration;
        playWhenReady = false;
        player.onCastingEnded();
        notifyListeners();
    }

    @Override
    public void onError(final String message) {
        if (closed) {
            return;
        }
        error = message;
        Toast.makeText(player.getContext(), message, Toast.LENGTH_LONG).show();
        stop(false);
    }

    public void close() {
        closed = true;
        session.close();
        returnToDevice(false);
        preparation.dispose();
        listeners.clear();
    }
}
