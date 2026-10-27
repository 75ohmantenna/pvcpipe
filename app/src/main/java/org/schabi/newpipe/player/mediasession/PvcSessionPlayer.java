package org.schabi.newpipe.player.mediasession;

import android.graphics.Bitmap;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.SimpleBasePlayer;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.schabi.newpipe.R;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.util.image.ImageStrategy;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** The session's application player: commands and clocks belong to PVCPipe, including FCast. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PvcSessionPlayer extends SimpleBasePlayer {
    private final Function<String, ListenableFuture<?>> prepareMediaId;
    @Nullable private org.schabi.newpipe.player.Player player;
    private boolean metadataDirty = true;
    private boolean preparingBrowser;
    private boolean browserReady;
    private int browserGeneration;
    @Nullable private ListenableFuture<?> browserFuture;
    private int windowStart;
    private int unmutedVolume = 100;
    @Nullable private androidx.media3.exoplayer.ExoPlayer observedEngine;
    private final androidx.media3.common.Player.Listener engineListener =
            new androidx.media3.common.Player.Listener() {
                @Override
                public void onEvents(final androidx.media3.common.Player engine,
                                     final androidx.media3.common.Player.Events events) {
                    refresh();
                }
            };
    private List<MediaItemData> playlist = List.of();
    @Nullable private PlayQueue playlistOwner;
    private int playlistIndex = -1;
    @Nullable private Bitmap artwork;
    @Nullable private byte[] artworkData;

    public PvcSessionPlayer(final Looper looper,
                            final Function<String, ListenableFuture<?>> prepareMediaId) {
        super(looper);
        this.prepareMediaId = prepareMediaId;
    }

    public boolean browserPlayWhenReady() {
        return browserReady;
    }

    public void attach(@Nullable final org.schabi.newpipe.player.Player applicationPlayer) {
        detachEngine();
        player = applicationPlayer;
        refresh();
    }

    public void detachEngine() {
        if (observedEngine != null) {
            observedEngine.removeListener(engineListener);
            observedEngine = null;
        }
    }

    public void refresh() {
        final androidx.media3.exoplayer.ExoPlayer engine = player == null
                ? null : player.getExoPlayer();
        if (engine != observedEngine) {
            detachEngine();
            observedEngine = engine;
            if (engine != null) {
                engine.addListener(engineListener);
            }
        }
        metadataDirty = true;
        invalidateState();
    }

    public void refreshPosition() {
        invalidateState();
    }

    @Override
    protected State getState() {
        final Commands.Builder commands = new Commands.Builder().addAll(
                COMMAND_GET_TIMELINE, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_METADATA,
                COMMAND_SET_MEDIA_ITEM, COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP);
        final State.Builder state = new State.Builder();
        final PlayQueue queue = player == null ? null : player.getPlayQueue();
        // Mode changes can retire or empty a queue before the next metadata refresh.
        // Never reuse its cached timeline or consult a missing selected occurrence.
        if (player == null || player.exoPlayerIsNull() || queue == null
                || queue.getBroadcastReceiver() == null || queue.getItem() == null) {
            playlist = List.of();
            playlistOwner = null;
            return state.setAvailableCommands(commands.build()).build();
        }
        if (metadataDirty || playlistOwner != queue || playlistIndex != queue.getIndex()) {
            playlist = buildPlaylist(queue);
            playlistOwner = queue;
            playlistIndex = queue.getIndex();
            metadataDirty = false;
        }
        if (playlist.isEmpty()) {
            return state.setAvailableCommands(commands.build()).build();
        }
        commands.addAll(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_DEFAULT_POSITION,
                COMMAND_SEEK_TO_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SET_REPEAT_MODE,
                COMMAND_SET_SHUFFLE_MODE);
        final boolean remote = player.getCasting().isActive();
        if (!remote || player.getCasting().getSession().getSpeedSupported()) {
            commands.add(COMMAND_SET_SPEED_AND_PITCH);
        }
        final int playbackState;
        switch (player.getCurrentState()) {
            case org.schabi.newpipe.player.Player.STATE_PREFLIGHT:
            case org.schabi.newpipe.player.Player.STATE_BLOCKED:
            case org.schabi.newpipe.player.Player.STATE_BUFFERING:
                playbackState = STATE_BUFFERING;
                break;
            case org.schabi.newpipe.player.Player.STATE_COMPLETED:
                playbackState = STATE_ENDED;
                break;
            default:
                playbackState = !player.isCurrentSourceSynchronized() ? STATE_BUFFERING
                        : remote ? STATE_READY : player.getExoPlayer().getPlaybackState();
                break;
        }
        // Capture this clock; Media3 extrapolates it only while playing. Reading mutable
        // application state from both snapshots would hide paused receiver/engine seeks.
        final long bufferedPosition = remote ? Math.max(0, player.getDuration())
                : player.isCurrentSourceSynchronized() ? player.getExoPlayer().getBufferedPosition()
                : ownedPosition();
        state.setPlaylist(playlist)
                .setCurrentMediaItemIndex(queue.getIndex() - windowStart)
                .setPlaybackState(playbackState)
                .setPlaybackSuppressionReason(remote ? PLAYBACK_SUPPRESSION_REASON_NONE
                        : player.getExoPlayer().getPlaybackSuppressionReason())
                .setIsLoading(playbackState != STATE_IDLE && playbackState != STATE_ENDED
                        && player.isLoading())
                .setPlayWhenReady(player.getPlayWhenReady(),
                        PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackParameters(player.getPlaybackParameters())
                .setRepeatMode(player.getRepeatMode())
                .setShuffleModeEnabled(queue.isShuffled())
                .setContentPositionMs(ownedPosition())
                .setSeekBackIncrementMs(org.schabi.newpipe.player.helper.PlayerHelper
                        .retrieveSeekDurationFromPreferences(player))
                .setSeekForwardIncrementMs(org.schabi.newpipe.player.helper.PlayerHelper
                        .retrieveSeekDurationFromPreferences(player))
                .setContentBufferedPositionMs(PositionSupplier.getConstant(bufferedPosition));
        if (remote) {
            final boolean volumeSupported = player.getCasting().getSession().getVolumeSupported();
            state.setDeviceInfo(new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMaxVolume(volumeSupported ? 100 : 0).build());
            if (volumeSupported) {
                commands.addAll(COMMAND_GET_DEVICE_VOLUME, COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                        COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS);
                final int volume = (int) Math.round(
                        player.getCasting().getSession().getVolume() * 100);
                state.setDeviceVolume(volume).setIsDeviceMuted(volume == 0);
            }
        }
        return state.setAvailableCommands(commands.build()).build();
    }

    private List<MediaItemData> buildPlaylist(final PlayQueue queue) {
        final List<MediaItemData> items = new ArrayList<>();
        final boolean showArtwork = player.getPrefs().getBoolean(
                player.getContext().getString(R.string.show_thumbnail_key), true);
        final Bitmap nextArtwork = showArtwork && player.isCurrentSourceSynchronized()
                ? player.getThumbnail() : null;
        if (artwork != nextArtwork) {
            artwork = nextArtwork;
            artworkData = null;
            if (artwork != null) {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                artwork.compress(Bitmap.CompressFormat.PNG, 100, bytes);
                artworkData = bytes.toByteArray();
            }
        }
        final int count = Math.min(10, queue.size());
        windowStart = Math.max(0, Math.min(queue.getIndex() - (count - 1) / 2,
                queue.size() - count));
        for (int index = windowStart; index < windowStart + count; index++) {
            final PlayQueueItem item = queue.getItem(index);
            final boolean current = index == queue.getIndex();
            final MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .setTitle(current && player.isCurrentSourceSynchronized()
                            ? player.getVideoTitle() : item.getTitle())
                    .setArtist(current && player.isCurrentSourceSynchronized()
                            ? player.getUploaderName() : item.getUploader())
                    .setIsPlayable(true).setIsBrowsable(false);
            if (showArtwork) {
                final String uri = ImageStrategy.choosePreferredImage(item.getThumbnails());
                if (uri != null) {
                    metadata.setArtworkUri(android.net.Uri.parse(uri));
                }
                if (current) {
                    metadata.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
                }
            }
            final long duration = current && player.isCurrentSourceSynchronized()
                    ? player.getDuration() : item.getDuration() * 1000;
            final boolean live = org.schabi.newpipe.util.StreamTypeUtil
                    .isLiveStream(item.getStreamType());
            final MediaItem mediaItem = new MediaItem.Builder()
                    .setMediaId("queue:" + System.identityHashCode(item))
                    .setMediaMetadata(metadata.build()).build();
            items.add(new MediaItemData.Builder(item).setMediaItem(mediaItem)
                    .setIsSeekable(true).setIsDynamic(live)
                    .setLiveConfiguration(live ? new MediaItem.LiveConfiguration.Builder().build()
                            : null)
                    .setDurationUs(!live && duration > 0 ? duration * 1000 : C.TIME_UNSET).build());
        }
        return items;
    }

    private long ownedPosition() {
        final PlayQueue queue = player == null ? null : player.getPlayQueue();
        final PlayQueueItem item = queue == null ? null : queue.getItem();
        if (item == null || queue.getBroadcastReceiver() == null) {
            return 0;
        }
        if (!player.isCurrentSourceSynchronized()
                || player.getCasting().isActive()
                && !player.getCasting().ownsClock(item)) {
            return Math.max(0, item.getRecoveryPosition());
        }
        return Math.max(0, player.getCurrentPosition());
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(final boolean ready) {
        if (preparingBrowser) {
            browserReady = ready;
        } else if (player != null) {
            if (ready) {
                player.play();
                player.UIs().get(org.schabi.newpipe.player.ui.VideoPlayerUi.class)
                        .ifPresent(ui -> ui.hideControls(0, 0));
            } else {
                player.pause();
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        if (player != null) {
            player.onPrepare();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        if (browserFuture != null) {
            browserFuture.cancel(false);
        }
        if (player != null && !player.exoPlayerIsNull()) {
            if (player.getCasting().isActive()
                    || player.getCasting().getSession().getConnectionState() != 0) {
                player.getCasting().stop(false);
            }
            player.getExoPlayer().stop();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        detachEngine();
        player = null;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(final List<MediaItem> mediaItems,
                                                     final int startIndex,
                                                     final long startPositionMs) {
        if (mediaItems.size() != 1 || startIndex > 0) {
            return Futures.immediateFailedFuture(new IllegalArgumentException("One browser ID"));
        }
        final int generation = ++browserGeneration;
        preparingBrowser = true;
        browserReady = false;
        final ListenableFuture<?> future = prepareMediaId.apply(mediaItems.get(0).mediaId);
        browserFuture = future;
        future.addListener(() -> {
            if (browserGeneration == generation) {
                preparingBrowser = false;
            }
        }, Runnable::run);
        return future;
    }

    @Override
    protected ListenableFuture<?> handleSeek(final int index, final long positionMs,
                                             final int command) {
        if (player != null && player.getPlayQueue() != null) {
            if (!player.getCasting().isActive() && !player.isCurrentSourceSynchronized()
                    && (command == COMMAND_SEEK_BACK || command == COMMAND_SEEK_FORWARD)) {
                player.getPlayQueue().setRecovery(player.getPlayQueue().getIndex(),
                        Math.max(0, positionMs));
                return Futures.immediateVoidFuture();
            }
            switch (command) {
                case COMMAND_SEEK_TO_NEXT:
                case COMMAND_SEEK_TO_NEXT_MEDIA_ITEM:
                    player.playNext();
                    break;
                case COMMAND_SEEK_TO_PREVIOUS:
                case COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM:
                    player.playPrevious();
                    break;
                case COMMAND_SEEK_BACK:
                    player.fastRewind();
                    break;
                case COMMAND_SEEK_FORWARD:
                    player.fastForward();
                    break;
                default:
                    if (index + windowStart != player.getPlayQueue().getIndex()) {
                        if (positionMs != C.TIME_UNSET) {
                            player.getPlayQueue().setRecovery(index + windowStart,
                                    Math.max(0, positionMs));
                        }
                        player.selectQueueItem(player.getPlayQueue().getItem(index + windowStart));
                    } else if (positionMs == C.TIME_UNSET) {
                        player.seekToDefault();
                    } else if (!player.getCasting().isActive()
                            && !player.isCurrentSourceSynchronized()) {
                        player.getPlayQueue().setRecovery(index + windowStart,
                                Math.max(0, positionMs));
                    } else {
                        player.seekTo(positionMs);
                    }
                    break;
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(final int repeatMode) {
        if (player != null && !player.exoPlayerIsNull()) {
            player.getExoPlayer().setRepeatMode(repeatMode);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetShuffleModeEnabled(final boolean enabled) {
        if (player != null && !player.exoPlayerIsNull()) {
            player.getExoPlayer().setShuffleModeEnabled(enabled);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(final PlaybackParameters parameters) {
        if (player != null) {
            if (player.getCasting().isActive()) {
                player.getCasting().setSpeed(parameters.speed);
            } else {
                player.setPlaybackParameters(parameters.speed, parameters.pitch,
                        player.getPlaybackSkipSilence());
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetDeviceVolume(final int volume, final int flags) {
        if (player != null && player.getCasting().isActive()) {
            if (volume > 0) {
                unmutedVolume = Math.min(100, volume);
            }
            player.getCasting().getSession().setVolume(Math.max(0, Math.min(100, volume)) / 100.0);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleIncreaseDeviceVolume(final int flags) {
        return handleSetDeviceVolume(getDeviceVolume() + 5, flags);
    }

    @Override
    protected ListenableFuture<?> handleDecreaseDeviceVolume(final int flags) {
        return handleSetDeviceVolume(getDeviceVolume() - 5, flags);
    }

    @Override
    protected ListenableFuture<?> handleSetDeviceMuted(final boolean muted, final int flags) {
        if (muted && getDeviceVolume() > 0) {
            unmutedVolume = getDeviceVolume();
        }
        return handleSetDeviceVolume(muted ? 0 : unmutedVolume, flags);
    }
}
