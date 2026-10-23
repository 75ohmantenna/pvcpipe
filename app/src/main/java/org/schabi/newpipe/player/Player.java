package org.schabi.newpipe.player;

import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static com.google.android.exoplayer2.Player.DiscontinuityReason;
import static com.google.android.exoplayer2.Player.Listener;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ONE;
import static com.google.android.exoplayer2.Player.RepeatMode;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;
import static org.schabi.newpipe.player.helper.PlayerHelper.retrievePlaybackParametersFromPrefs;
import static org.schabi.newpipe.player.helper.PlayerHelper.retrieveSeekDurationFromPreferences;
import static org.schabi.newpipe.player.helper.PlayerHelper.savePlaybackParametersToPrefs;
import static org.schabi.newpipe.util.ListHelper.getResolutionIndex;
import static java.util.concurrent.TimeUnit.MILLISECONDS;


import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentCompat;
import androidx.core.math.MathUtils;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player.PositionInfo;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.video.VideoSize;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.history.PlaybackHistory;
import org.schabi.newpipe.player.event.PlayerEventListener;
import org.schabi.newpipe.player.event.PlayerListeners;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;
import org.schabi.newpipe.player.audio.PlaybackAudio;
import org.schabi.newpipe.player.broadcast.AndroidPlayerBroadcasts;
import org.schabi.newpipe.player.broadcast.PlayerBroadcasts;
import org.schabi.newpipe.player.cast.CastPlayback;
import org.schabi.newpipe.player.error.PlaybackErrors;
import org.schabi.newpipe.player.helper.CustomRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediasource.LoadedMediaSource;
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi;
import org.schabi.newpipe.player.notification.NotificationPlayerUi;
import org.schabi.newpipe.player.playback.MediaSourceManager;
import org.schabi.newpipe.player.playback.PlaybackListener;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.resolver.PlaybackSources;
import org.schabi.newpipe.player.ui.BackgroundPlayerUi;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.player.ui.PlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.player.ui.PopupPlayerUi;
import org.schabi.newpipe.player.ui.VideoPlayerUi;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.SerializedCache;
import org.schabi.newpipe.util.SponsorBlockMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class Player implements PlaybackListener, Listener {
    public static final boolean DEBUG = MainActivity.DEBUG;
    public static final String TAG = Player.class.getSimpleName();

    /*//////////////////////////////////////////////////////////////////////////
    // States
    //////////////////////////////////////////////////////////////////////////*/

    public static final int STATE_PREFLIGHT = -1;
    public static final int STATE_BLOCKED = 123;
    public static final int STATE_PLAYING = 124;
    public static final int STATE_BUFFERING = 125;
    public static final int STATE_PAUSED = 126;
    public static final int STATE_PAUSED_SEEK = 127;
    public static final int STATE_COMPLETED = 128;

    /*//////////////////////////////////////////////////////////////////////////
    // Intent
    //////////////////////////////////////////////////////////////////////////*/

    public static final String PLAYBACK_QUALITY = "playback_quality";
    public static final String PLAY_QUEUE_KEY = "play_queue_key";
    public static final String RESUME_PLAYBACK = "resume_playback";
    public static final String PLAY_WHEN_READY = "play_when_ready";
    public static final String PLAYER_TYPE = "player_type";
    public static final String IS_MUTED = "is_muted";
    public static final String VIDEO_SEGMENTS = "video_segments";
    public static final String PLAYER_INTENT_TYPE = "player_intent_type";
    public static final String PLAYER_INTENT_DATA = "player_intent_data";

    /*//////////////////////////////////////////////////////////////////////////
    // Time constants
    //////////////////////////////////////////////////////////////////////////*/

    public static final int PLAY_PREV_ACTIVATION_LIMIT_MILLIS = 5000; // 5 seconds
    public static final int PROGRESS_LOOP_INTERVAL_MILLIS = 1000; // 1 second

    /*//////////////////////////////////////////////////////////////////////////
    // Other constants
    //////////////////////////////////////////////////////////////////////////*/

    public static final int RENDERER_UNAVAILABLE = -1;

    /*//////////////////////////////////////////////////////////////////////////
    // Playback
    //////////////////////////////////////////////////////////////////////////*/

    // play queue might be null e.g. while player is starting
    @Nullable
    private PlayQueue playQueue;

    @Nullable
    private MediaSourceManager playQueueManager;

    @Nullable
    private PlayQueueItem currentItem;
    @Nullable
    private MediaItemTag currentMetadata;
    private final PlayerThumbnail thumbnail = new PlayerThumbnail(this);

    /*//////////////////////////////////////////////////////////////////////////
    // Player
    //////////////////////////////////////////////////////////////////////////*/

    private ExoPlayer simpleExoPlayer;
    private PlaybackAudio playbackAudio;

    @NonNull
    private final DefaultTrackSelector trackSelector;
    @NonNull
    private final LoadController loadController;
    @NonNull
    private final DefaultRenderersFactory renderFactory;

    @NonNull
    private final PlaybackSources playbackSources;

    private final PlayerService service; //TODO try to remove and replace everything with context

    /*//////////////////////////////////////////////////////////////////////////
    // Player states
    //////////////////////////////////////////////////////////////////////////*/

    private PlayerType playerType = PlayerType.MAIN;
    private int currentState = STATE_PREFLIGHT;

    // audio only mode does not mean that player type is background, but that the player was
    // minimized to background but will resume automatically to the original player type
    private boolean isAudioOnly = false;
    private boolean isPrepared = false;

    /*//////////////////////////////////////////////////////////////////////////
    // UIs, listeners and disposables
    //////////////////////////////////////////////////////////////////////////*/

    @SuppressWarnings({"MemberName", "java:S116"}) // keep the unusual member name
    private final PlayerUiList UIs;

    @NonNull
    private final PlayerBroadcasts broadcasts;
    @NonNull
    private final AndroidPlayerBroadcasts broadcastReceiver;
    @NonNull
    private final PlayerListeners listeners = new PlayerListeners();

    @NonNull
    private final SerialDisposable progressUpdateDisposable = new SerialDisposable();
    @NonNull
    private final CompositeDisposable streamItemDisposable = new CompositeDisposable();

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull
    private final Context context;
    @NonNull
    private final SharedPreferences prefs;
    @NonNull
    private final PlaybackHistory playbackHistory;
    private final CastPlayback casting;

    private final SerialDisposable playbackDecision = new SerialDisposable();

    /*//////////////////////////////////////////////////////////////////////////
    // SponsorBlock
    //////////////////////////////////////////////////////////////////////////*/
    private final SponsorBlockController sponsorBlock = new SponsorBlockController();


    /*//////////////////////////////////////////////////////////////////////////
    // Constructor
    //////////////////////////////////////////////////////////////////////////*/
    //region Constructor

    /**
     * Creates a player hosted by the service and connected to its media session.
     *
     * @param service the service this player resides in
     * @param mediaSession used to build the {@link MediaSessionPlayerUi}, lives in the service and
     *                     could possibly be reused with multiple player instances
     * @param sessionConnector used to build the {@link MediaSessionPlayerUi}, lives in the service
     *                         and could possibly be reused with multiple player instances
     */
    public Player(@NonNull final PlayerService service,
                  @NonNull final MediaSessionCompat mediaSession,
                  @NonNull final MediaSessionConnector sessionConnector) {
        this.service = service;
        context = service;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        playbackHistory = new PlaybackHistory(context);
        casting = new CastPlayback(this);

        broadcasts = new PlayerBroadcasts(new BroadcastCommands());
        broadcastReceiver = new AndroidPlayerBroadcasts(context, broadcasts,
                this::onBroadcastReceived);

        trackSelector = new DefaultTrackSelector(context, PlayerHelper.getQualitySelector());
        final PlayerDataSource dataSource = new PlayerDataSource(context,
                new DefaultBandwidthMeter.Builder(context).build());
        loadController = new LoadController();

        renderFactory = prefs.getBoolean(
                context.getString(
                        R.string.always_use_exoplayer_set_output_surface_workaround_key), false)
                ? new CustomRenderersFactory(context) : new DefaultRenderersFactory(context);

        renderFactory.setEnableDecoderFallback(
                prefs.getBoolean(
                        context.getString(
                                R.string.use_exoplayer_decoder_fallback_key), false));

        playbackSources = new PlaybackSources(context, dataSource, getQualityResolver());

        // The UIs added here should always be present. They will be initialized when the player
        // reaches the initialization step. Make sure the media session ui is before the
        // notification ui in the UIs list, since the notification depends on the media session in
        // PlayerUi#initPlayer(), and UIs.call() guarantees UI order is preserved.
        UIs = new PlayerUiList(
                new MediaSessionPlayerUi(this, mediaSession, sessionConnector),
                new NotificationPlayerUi(this)
        );
    }

    private PlaybackSources.QualityResolver getQualityResolver() {
        return new PlaybackSources.QualityResolver() {
            @Override
            public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
                return videoPlayerSelected()
                        ? ListHelper.getDefaultResolutionIndex(context, sortedVideos)
                        : ListHelper.getPopupDefaultResolutionIndex(context, sortedVideos);
            }

            @Override
            public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                                  final String playbackQuality) {
                return getResolutionIndex(context, sortedVideos, playbackQuality);
            }
        };
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback initialization via intent
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback initialization via intent

    public void handleIntent(@NonNull final Intent intent) {
        final var playerIntentType = IntentCompat.getSerializableExtra(intent, PLAYER_INTENT_TYPE,
                PlayerIntentType.class);
        if (playerIntentType == null) {
            return;
        }
        // Timestamp requests keep the current player type. Set up UIs before dispatching
        // any queue or timestamp action.
        if (playerIntentType != PlayerIntentType.TimestampChange) {
            playerType = IntentCompat.getSerializableExtra(intent, PLAYER_TYPE, PlayerType.class);
        }
        initUIsForCurrentPlayerType();
        isAudioOnly = audioPlayerSelected();

        if (intent.hasExtra(PLAYBACK_QUALITY)) {
            playbackSources.setPlaybackQuality(intent.getStringExtra(PLAYBACK_QUALITY));
        }

        final boolean playWhenReady = intent.getBooleanExtra(PLAY_WHEN_READY, true);

        switch (playerIntentType) {
            case Enqueue, EnqueueNext -> {
                if (playQueue != null) {
                    final PlayQueue newQueue = getPlayQueueFromCache(intent);
                    if (newQueue == null) {
                        return;
                    }
                    if (playerIntentType == PlayerIntentType.Enqueue) {
                        playQueue.append(newQueue.getStreams());
                    } else {
                        playQueue.enqueueNext(newQueue.getStreams().get(0), false);
                    }
                    return;
                }
                // With no active queue, install the supplied queue below.
            }
            case TimestampChange -> {
                handleTimestampChange(intent, playWhenReady);
                return;
            }
            case AllOthers -> {
                // fallthrough; TODO: put other intent data in separate cases
            }
        }

        handleQueuePlayback(intent, playWhenReady);
    }

    private void handleQueuePlayback(@NonNull final Intent intent, final boolean playWhenReady) {
        final PlayQueue newQueue = getPlayQueueFromCache(intent);
        if (newQueue == null) {
            return;
        }

        playbackDecision.set(null);
        // branching parameters for below
        final boolean samePlayQueue = playQueue != null && playQueue.equalStreamsAndIndex(newQueue);

        /*
         * TODO As seen in #7427 this does not work:
         * There are 3 situations when playback shouldn't be started from scratch (zero timestamp):
         * 1. User pressed on a timestamp link and the same video should be rewound to the timestamp
         * 2. User changed a player from, for example. main to popup, or from audio to main, etc
         * 3. User chose to resume a video based on a saved timestamp from history of played videos
         * In those cases time will be saved because re-init of the play queue is a not an instant
         *  task and requires network calls
         * */
        switch (QueuePlaybackChoice.of(!exoPlayerIsNull(), playQueue, newQueue, samePlayQueue,
                intent.getBooleanExtra(RESUME_PLAYBACK, false),
                () -> DependentPreferenceHelper.getResumePlaybackEnabled(context))) {
            case SEEK_IN_PLACE:
                // seek to timestamp if stream is already playing
                prepareIfIdle();
                seekToCurrentItem(newQueue.getItem().getRecoveryPosition());
                setPlaying(playWhenReady);
                break;
            case KEEP_CURRENT_QUEUE:
                prepareIfIdle();
                setPlaying(playWhenReady);
                break;
            case RESUME_FROM_HISTORY:
                resumePlaybackFromHistory(newQueue, playWhenReady);
                break;
            case START:
            default:
                // Good to go...
                // In a case of equal PlayQueues we can re-init old one but only when it is disposed
                initPlayback(samePlayQueue ? playQueue : newQueue, playWhenReady);
                break;
        }
    }

    private void handleTimestampChange(@NonNull final Intent intent, final boolean playWhenReady) {
        final var data = Objects.requireNonNull(IntentCompat.getParcelableExtra(intent,
                PLAYER_INTENT_DATA, TimestampChangeData.class));
        final Single<StreamInfo> single =
                ExtractorHelper.getStreamInfo(data.getServiceId(), data.getUrl(), false);
        final SerialDisposable request = new SerialDisposable();
        playbackDecision.set(request);
        request.set(single.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(info -> playFromTimestamp(info, data.getSeconds(), playWhenReady),
                        throwable -> {
                            // Show a snackbar when a root view is available, otherwise notify.
                            final var info = new ErrorInfo(throwable, UserAction.PLAY_ON_POPUP,
                                    data.getUrl(), null, data.getUrl());
                            ErrorUtil.createNotification(context, info);
                        }));
    }

    private void playFromTimestamp(final StreamInfo info, final int seconds,
                                   final boolean playWhenReady) {
        final @Nullable PlayQueue oldPlayQueue = playQueue;
        info.setStartPosition(seconds);
        final PlayQueueItem playQueueItem = new PlayQueueItem(info);

        // If the stream is already playing,
        // we can just seek to the appropriate timestamp
        if (oldPlayQueue != null
                && playQueueItem.isSameItem(oldPlayQueue.getItem())) {
            prepareIfIdle();
            seekToCurrentItem(seconds * 1000L);
            setPlaying(playWhenReady);

        } else {
            final PlayQueue newPlayQueue;

            // If there is no queue yet, just add our item
            if (oldPlayQueue == null) {
                newPlayQueue = new SinglePlayQueue(playQueueItem);

            // else we add the timestamped stream behind the current video
            // and start playing it.
            } else {
                oldPlayQueue.enqueueNext(playQueueItem, true);
                oldPlayQueue.offsetIndex(1);
                newPlayQueue = oldPlayQueue;
            }
            initPlayback(newPlayQueue, playWhenReady);
        }
    }

    private void resumePlaybackFromHistory(@NonNull final PlayQueue newQueue,
                                          final boolean playWhenReady) {
        final SerialDisposable request = new SerialDisposable();
        playbackDecision.set(request);
        request.set(playbackHistory.resumePosition(newQueue.getItem())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(position -> {
                    if (position != PlayQueueItem.RECOVERY_UNSET) {
                        newQueue.setRecovery(newQueue.getIndex(), position);
                    }
                    initPlayback(newQueue, playWhenReady);
                }));
    }

    private void prepareIfIdle() {
        if (simpleExoPlayer.getPlaybackState()
                == com.google.android.exoplayer2.Player.STATE_IDLE) {
            simpleExoPlayer.prepare();
        }
    }



    public void handleIntentPost(final PlayerType oldPlayerType) {
        if (oldPlayerType != playerType && playQueue != null) {
            // If playerType changes from one to another we should reload the player
            // (to disable/enable video stream or to set quality)
            reloadPlayQueueManager();
            stopProgressLoop();
            startProgressLoop();
        }

        UIs.call(PlayerUi::setupAfterIntent);
        NavigationHelper.sendPlayerStartedEvent(context);
    }

    @Nullable
    private static PlayQueue getPlayQueueFromCache(@NonNull final Intent intent) {
        final String queueCache = intent.getStringExtra(PLAY_QUEUE_KEY);
        if (queueCache == null) {
            return null;
        }
        return SerializedCache.getInstance().take(queueCache, PlayQueue.class);
    }

    private void initUIsForCurrentPlayerType() {
        if ((UIs.get(MainPlayerUi.class).isPresent() && playerType == PlayerType.MAIN)
                || (UIs.get(BackgroundPlayerUi.class).isPresent() && playerType == PlayerType.AUDIO)
                || (UIs.get(PopupPlayerUi.class).isPresent() && playerType == PlayerType.POPUP)) {
            // correct UI already in place
            return;
        }

        // try to reuse binding if possible
        final PlayerBinding binding = UIs.get(VideoPlayerUi.class).map(VideoPlayerUi::getBinding)
                .orElseGet(() -> {
                    if (playerType == PlayerType.AUDIO) {
                        return null;
                    } else {
                        return PlayerBinding.inflate(LayoutInflater.from(context));
                    }
                });

        switch (playerType) {
            case MAIN:
                UIs.destroyAll(PopupPlayerUi.class);
                UIs.destroyAll(BackgroundPlayerUi.class);
                UIs.addAndPrepare(new MainPlayerUi(this, binding));
                break;
            case POPUP:
                UIs.destroyAll(MainPlayerUi.class);
                UIs.destroyAll(BackgroundPlayerUi.class);
                UIs.addAndPrepare(new PopupPlayerUi(this, binding));
                break;
            case AUDIO:
                UIs.destroyAll(VideoPlayerUi.class); // destroys both MainPlayerUi and PopupPlayerUi
                UIs.addAndPrepare(new BackgroundPlayerUi(this));
                break;
        }
    }

    private void initPlayback(@NonNull final PlayQueue queue,
                              final boolean playOnReady) {
        destroyPlayer();
        initPlayer(playOnReady);
        casting.setPlayWhenReadyForSource(playOnReady);
        final boolean playbackSkipSilence = getPrefs().getBoolean(getContext().getString(
                R.string.playback_skip_silence_key), getPlaybackSkipSilence());
        final PlaybackParameters savedParameters = retrievePlaybackParametersFromPrefs(this);
        setPlaybackParameters(savedParameters.speed, savedParameters.pitch, playbackSkipSilence);

        playQueue = queue;
        playQueue.init();
        reloadPlayQueueManager();

        UIs.call(PlayerUi::initPlayback);

        playbackAudio.onPlaybackEvent(PlaybackAudio.Event.INITIALIZED);
        notifyQueueUpdateToListeners();
    }

    private void initPlayer(final boolean playOnReady) {
        if (DEBUG) {
            Log.d(TAG, "initPlayer() called with: playOnReady = [" + playOnReady + "]");
        }

        simpleExoPlayer = new ExoPlayer.Builder(context, renderFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadController)
                .setUsePlatformDiagnostics(false)
                .build();
        simpleExoPlayer.addListener(this);
        simpleExoPlayer.setPlayWhenReady(playOnReady && !casting.isActive());
        simpleExoPlayer.setSeekParameters(PlayerHelper.getSeekParameters(context));
        simpleExoPlayer.setWakeMode(C.WAKE_MODE_NETWORK);
        simpleExoPlayer.setHandleAudioBecomingNoisy(true);

        playbackAudio = new PlaybackAudio(context, simpleExoPlayer);

        broadcastReceiver.register();

        // Setup UIs
        UIs.call(PlayerUi::initPlayer);

        // Disable media tunneling if requested by the user from ExoPlayer settings
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.disable_media_tunneling_key), false)) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTunnelingEnabled(true));
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Destroy and recovery
    //////////////////////////////////////////////////////////////////////////*/
    //region Destroy and recovery

    private void destroyPlayer() {
        if (DEBUG) {
            Log.d(TAG, "destroyPlayer() called");
        }
        if (playQueueManager != null) {
            playQueueManager.dispose();
        }
        casting.onPlayerReset();
        UIs.call(PlayerUi::destroyPlayer);

        if (playbackAudio != null) {
            playbackAudio.dispose();
        }
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.removeListener(this);
            simpleExoPlayer.stop();
            simpleExoPlayer.release();
        }
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }
        if (playQueue != null) {
            playQueue.dispose();
        }
    }

    public void destroy() {
        if (DEBUG) {
            Log.d(TAG, "destroy() called");
        }

        saveStreamProgressState();
        setRecovery();
        stopActivityBinding();
        casting.close();

        destroyPlayer();
        broadcastReceiver.unregister();

        playbackDecision.set(null);
        progressUpdateDisposable.set(null);
        streamItemDisposable.clear();

        UIs.destroyAll(Object.class); // destroy every UI: obviously every UI extends Object
    }

    public void setRecovery() {
        if (playQueue == null || exoPlayerIsNull()) {
            return;
        }

        if (!loadedSourceOwnsSelectedItem()
                || casting.isActive() && !casting.ownsClock(playQueue.getItem())) {
            return;
        }

        final int queuePos = playQueue.getIndex();
        final long windowPos = getCurrentPosition();
        final long duration = getDuration();

        // A pending cast seek is valid intent even before its duration is known.
        setRecovery(queuePos, casting.isActive() && duration <= 0
                ? Math.max(0, windowPos) : MathUtils.clamp(windowPos, 0, duration));
    }

    private boolean loadedSourceOwnsSelectedItem() {
        if (playQueue == null || playQueue.getItem() == null || exoPlayerIsNull()) {
            return false;
        }
        // Selection can advance or reindex before ExoPlayer switches sources. Neither a URL,
        // numerical index nor currentItem establishes which occurrence owns the captured clock.
        return MediaItemTag.from(simpleExoPlayer.getCurrentMediaItem())
                .flatMap(tag -> tag.getMaybeExtras(Object.class))
                .filter(LoadedMediaSource.class::isInstance)
                .map(LoadedMediaSource.class::cast)
                .map(source -> source.getStream() == playQueue.getItem())
                .orElse(false);
    }

    /**
     * Cast preparation needs coherent selection, engine ownership and delivered metadata.
     * @return whether the selected occurrence owns the engine source and cached metadata
     */
    public boolean isCurrentSourceSynchronized() {
        return loadedSourceOwnsSelectedItem() && currentItem == playQueue.getItem()
                && currentMetadata != null
                && MediaItemTag.from(simpleExoPlayer.getCurrentMediaItem())
                        .filter(tag -> tag == currentMetadata).isPresent();
    }

    private void setRecovery(final int queuePos, final long windowPos) {
        if (playQueue == null || playQueue.size() <= queuePos) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Setting recovery, queue: " + queuePos + ", pos: " + windowPos);
        }
        playQueue.setRecovery(queuePos, windowPos);
    }

    public void reloadPlayQueueManager() {
        if (playQueueManager != null) {
            playQueueManager.dispose();
        }

        if (playQueue != null) {
            playQueueManager = new MediaSourceManager(context, this, playQueue);
        }
    }

    @Override // own playback listener
    public void onPlaybackShutdown() {
        if (DEBUG) {
            Log.d(TAG, "onPlaybackShutdown() called");
        }
        // destroys the service, which in turn will destroy the player
        service.destroyPlayerAndStopService();
    }

    public void smoothStopForImmediateReusing() {
        // Pausing would make transition from one stream to a new stream not smooth, so only stop
        simpleExoPlayer.stop();
        setRecovery();
        UIs.call(PlayerUi::smoothStopForImmediateReusing);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Broadcast receiver
    //////////////////////////////////////////////////////////////////////////*/
    //region Broadcast receiver

    private void onBroadcastReceived(final Intent intent) {
        UIs.call(playerUi -> playerUi.onBroadcastReceived(intent));
    }

    /** Lets {@link PlayerBroadcasts} drive this player. */
    private final class BroadcastCommands implements PlayerBroadcasts.Commands {
        @Override
        public void pause() {
            // A headset unplugged from the phone must not pause the receiver's speakers.
            if (!casting.isActive()) {
                Player.this.pause();
            }
        }

        @Override
        public void close() {
            service.destroyPlayerAndStopService();
        }

        @Override
        public void playPause() {
            Player.this.playPause();
        }

        @Override
        public void playPrevious() {
            Player.this.playPrevious();
        }

        @Override
        public void playNext() {
            Player.this.playNext();
        }

        @Override
        public void fastRewind() {
            Player.this.fastRewind();
        }

        @Override
        public void fastForward() {
            Player.this.fastForward();
        }

        @Override
        public void cycleNextRepeatMode() {
            Player.this.cycleNextRepeatMode();
        }

        @Override
        public void toggleShuffleModeEnabled() {
            Player.this.toggleShuffleModeEnabled();
        }
    }
    //endregion






    /*//////////////////////////////////////////////////////////////////////////
    // Playback parameters
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback parameters

    public float getPlaybackSpeed() {
        return getPlaybackParameters().speed;
    }

    public void setPlaybackSpeed(final float speed) {
        setPlaybackParameters(speed, getPlaybackPitch(), getPlaybackSkipSilence());
    }

    public float getPlaybackPitch() {
        return getPlaybackParameters().pitch;
    }

    public boolean getPlaybackSkipSilence() {
        return !exoPlayerIsNull() && simpleExoPlayer.getSkipSilenceEnabled();
    }

    public PlaybackParameters getPlaybackParameters() {
        if (casting.isActive()) {
            return casting.getParameters();
        }
        if (exoPlayerIsNull()) {
            return PlaybackParameters.DEFAULT;
        }
        return simpleExoPlayer.getPlaybackParameters();
    }

    /**
     * Sets the playback parameters of the player, and also saves them to shared preferences.
     * Speed and pitch are rounded to the nearest 2 decimal places before being used or saved.
     *
     * @param speed       the playback speed, will be rounded to up to 2 decimal places
     * @param pitch       the playback pitch, will be rounded to up to 2 decimal places
     * @param skipSilence skip silence during playback
     */
    public void setPlaybackParameters(final float speed, final float pitch,
                                      final boolean skipSilence) {
        final float roundedSpeed = Math.round(speed * 100.0f) / 100.0f;
        final float roundedPitch = Math.round(pitch * 100.0f) / 100.0f;

        savePlaybackParametersToPrefs(this, roundedSpeed, roundedPitch, skipSilence);
        simpleExoPlayer.setPlaybackParameters(
                new PlaybackParameters(roundedSpeed, roundedPitch));
        simpleExoPlayer.setSkipSilenceEnabled(skipSilence);
        if (casting.isActive()) {
            casting.setSpeed(roundedSpeed);
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Progress loop and updates
    //////////////////////////////////////////////////////////////////////////*/
    //region Progress loop and updates

    private void onUpdateProgress(final int currentProgress,
                                  final int duration,
                                  final int bufferPercent) {
        if (isPrepared) {
            UIs.call(ui -> ui.onUpdateProgress(currentProgress, duration, bufferPercent));
            listeners.onProgressUpdate(currentProgress, duration, bufferPercent);
        }
    }

    public void startProgressLoop() {
        progressUpdateDisposable.set(getProgressUpdateDisposable());
    }

    private void stopProgressLoop() {
        progressUpdateDisposable.set(null);
    }

    public boolean isProgressLoopRunning() {
        return progressUpdateDisposable.get() != null;
    }

    public void triggerProgressUpdate() {
        triggerProgressUpdate(false);
    }

    private void triggerProgressUpdate(final boolean isRewind) {
        if (exoPlayerIsNull()) {
            return;
        }

        final int currentProgress = (int) Math.min(Integer.MAX_VALUE, getCurrentPosition());

        onUpdateProgress(
                currentProgress,
                (int) Math.min(Integer.MAX_VALUE, getDuration()),
                casting.isActive() ? 100 : simpleExoPlayer.getBufferedPercentage());

        if (isPrepared) {
            sponsorBlock.skip(this, currentProgress, isRewind);
        }
    }

    private Disposable getProgressUpdateDisposable() {
        return Observable.interval(PROGRESS_LOOP_INTERVAL_MILLIS, MILLISECONDS,
                        AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> triggerProgressUpdate(),
                        error -> Log.e(TAG, "Progress update failure: ", error));
    }

    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Playback states
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback states
    @Override
    public void onPlayWhenReadyChanged(final boolean playWhenReady, final int reason) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPlayWhenReadyChanged() called with: "
                    + "playWhenReady = [" + playWhenReady + "], "
                    + "reason = [" + reason + "]");
        }
        final int playbackState = exoPlayerIsNull()
                ? com.google.android.exoplayer2.Player.STATE_IDLE
                : simpleExoPlayer.getPlaybackState();
        updatePlaybackState(playWhenReady, playbackState);
    }

    @Override
    public void onPlaybackStateChanged(final int playbackState) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPlaybackStateChanged() called with: "
                    + "playbackState = [" + playbackState + "]");
        }
        updatePlaybackState(getPlayWhenReady(), playbackState);
    }

    private void updatePlaybackState(final boolean playWhenReady, final int playbackState) {
        if (casting.isActive()) {
            return; // Local ExoPlayer stays paused while the receiver owns playback.
        }
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - updatePlaybackState() called with: "
                    + "playWhenReady = [" + playWhenReady + "], "
                    + "playbackState = [" + playbackState + "]");
        }

        if (currentState == STATE_PAUSED_SEEK) {
            if (DEBUG) {
                Log.d(TAG, "updatePlaybackState() is currently blocked");
            }
            return;
        }

        switch (playbackState) {
            case com.google.android.exoplayer2.Player.STATE_IDLE: // 1
                isPrepared = false;
                break;
            case com.google.android.exoplayer2.Player.STATE_BUFFERING: // 2
                if (isPrepared) {
                    changeState(STATE_BUFFERING);
                }
                break;
            case com.google.android.exoplayer2.Player.STATE_READY: //3
                if (!isPrepared) {
                    isPrepared = true;
                    onPrepared(playWhenReady);
                }
                changeState(playWhenReady ? STATE_PLAYING : STATE_PAUSED);
                break;
            case com.google.android.exoplayer2.Player.STATE_ENDED: // 4
                changeState(STATE_COMPLETED);
                saveStreamProgressStateCompleted();
                isPrepared = false;
                break;
        }
    }

    @Override // exoplayer listener
    public void onIsLoadingChanged(final boolean isLoading) {
        if (casting.isActive()) {
            return;
        }
        if (!isLoading && currentState == STATE_PAUSED && isProgressLoopRunning()) {
            stopProgressLoop();
        } else if (isLoading && !isProgressLoopRunning()) {
            startProgressLoop();
        }
    }

    @Override // own playback listener
    public void onPlaybackBlock() {
        if (exoPlayerIsNull()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackBlock() called");
        }

        currentItem = null;
        currentMetadata = null;
        simpleExoPlayer.stop();
        isPrepared = false;

        changeState(STATE_BLOCKED);
    }

    @Override // own playback listener
    public void onPlaybackUnblock(final MediaSource mediaSource) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackUnblock() called");
        }

        if (exoPlayerIsNull()) {
            return;
        }
        // Installing a replacement timeline can synchronously report REMOVE at the old
        // engine index. Keep it blocked so history cannot mistake that reset for navigation.
        simpleExoPlayer.setMediaSource(mediaSource, false);
        if (currentState == STATE_BLOCKED) {
            changeState(STATE_BUFFERING);
        }
        simpleExoPlayer.prepare();
    }

    public void changeState(final int state) {
        if (DEBUG) {
            Log.d(TAG, "changeState() called with: state = [" + state + "]");
        }
        currentState = state;
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
        notifyPlaybackUpdateToListeners();
    }

    private void onPrepared(final boolean playWhenReady) {
        if (DEBUG) {
            Log.d(TAG, "onPrepared() called with: playWhenReady = [" + playWhenReady + "]");
        }

        UIs.call(PlayerUi::onPrepared);

        if (playWhenReady) {
            playbackAudio.onPlaybackEvent(PlaybackAudio.Event.PREPARED_PLAYING);
        }
    }

    private void onBlocked() {
        if (DEBUG) {
            Log.d(TAG, "onBlocked() called");
        }
        if (!isProgressLoopRunning()) {
            startProgressLoop();
        }

        UIs.call(PlayerUi::onBlocked);
    }

    private void onPlaying() {
        if (DEBUG) {
            Log.d(TAG, "onPlaying() called");
        }
        if (!isProgressLoopRunning()) {
            startProgressLoop();
        }

        UIs.call(PlayerUi::onPlaying);
    }

    private void onBuffering() {
        if (DEBUG) {
            Log.d(TAG, "onBuffering() called");
        }

        UIs.call(PlayerUi::onBuffering);
    }

    private void onPaused() {
        if (DEBUG) {
            Log.d(TAG, "onPaused() called");
        }

        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }

        UIs.call(PlayerUi::onPaused);
    }

    private void onPausedSeek() {
        if (DEBUG) {
            Log.d(TAG, "onPausedSeek() called");
        }
        UIs.call(PlayerUi::onPausedSeek);
    }

    private void onCompleted() {
        if (DEBUG) {
            Log.d(TAG, "onCompleted() called" + (playQueue == null ? ". playQueue is null" : ""));
        }
        if (playQueue == null) {
            return;
        }

        UIs.call(PlayerUi::onCompleted);

        if (playQueue.getIndex() < playQueue.size() - 1) {
            playQueue.offsetIndex(+1);
        }
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Repeat and shuffle
    //////////////////////////////////////////////////////////////////////////*/
    //region Repeat and shuffle

    @RepeatMode
    public int getRepeatMode() {
        return exoPlayerIsNull() ? REPEAT_MODE_OFF : simpleExoPlayer.getRepeatMode();
    }

    public void cycleNextRepeatMode() {
        if (!exoPlayerIsNull()) {
            @RepeatMode final int repeatMode;
            switch (simpleExoPlayer.getRepeatMode()) {
                case REPEAT_MODE_OFF:
                    repeatMode = REPEAT_MODE_ONE;
                    break;
                case REPEAT_MODE_ONE:
                    repeatMode = REPEAT_MODE_ALL;
                    break;
                case REPEAT_MODE_ALL:
                default:
                    repeatMode = REPEAT_MODE_OFF;
                    break;
            }
            simpleExoPlayer.setRepeatMode(repeatMode);
        }
    }

    @Override
    public void onRepeatModeChanged(@RepeatMode final int repeatMode) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onRepeatModeChanged() called with: "
                    + "repeatMode = [" + repeatMode + "]");
        }
        UIs.call(playerUi -> playerUi.onRepeatModeChanged(repeatMode));
        notifyPlaybackUpdateToListeners();
    }

    @Override
    public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onShuffleModeEnabledChanged() called with: "
                    + "mode = [" + shuffleModeEnabled + "]");
        }

        if (playQueue != null) {
            if (shuffleModeEnabled) {
                playQueue.shuffle();
            } else {
                playQueue.unshuffle();
            }
        }

        UIs.call(playerUi -> playerUi.onShuffleModeEnabledChanged(shuffleModeEnabled));
        notifyPlaybackUpdateToListeners();
    }

    public void toggleShuffleModeEnabled() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.setShuffleModeEnabled(!simpleExoPlayer.getShuffleModeEnabled());
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Mute / Unmute
    //////////////////////////////////////////////////////////////////////////*/
    //region Mute / Unmute

    public void toggleMute() {
        if (casting.isActive()) {
            casting.toggleMute();
            return;
        }
        playbackAudio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        UIs.call(playerUi -> playerUi.onMuteUnmuteChanged(isMuted()));
        notifyPlaybackUpdateToListeners();
    }

    public boolean isMuted() {
        if (casting.isActive()) {
            return casting.getSession().getVolume() == 0;
        }
        return !exoPlayerIsNull() && playbackAudio.isMuted();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // ExoPlayer listeners (that didn't fit in other categories)
    //////////////////////////////////////////////////////////////////////////*/
    //region ExoPlayer listeners (that didn't fit in other categories)

    /**
     * <p>Listens for event or state changes on ExoPlayer. When any event happens, we check for
     * changes in the currently-playing metadata and update the encapsulating
     * {@link Player}. Downstream listeners are also informed.</p>
     *
     * <p>When the renewed metadata contains any error, it is reported as a notification.
     * This is done because not all source resolution errors are {@link PlaybackException}, which
     * are also captured by {@link ExoPlayer} and stops the playback.</p>
     *
     * @param player The {@link com.google.android.exoplayer2.Player} whose state changed.
     * @param events The {@link com.google.android.exoplayer2.Player.Events} that has triggered
     *               the player state changes.
     **/
    @Override
    public void onEvents(@NonNull final com.google.android.exoplayer2.Player player,
                         @NonNull final com.google.android.exoplayer2.Player.Events events) {
        Listener.super.onEvents(player, events);
        MediaItemTag.from(player.getCurrentMediaItem()).ifPresent(tag -> {
            if (tag == currentMetadata) {
                return; // we still have the same metadata, no need to do anything
            }
            final StreamInfo previousInfo = Optional.ofNullable(currentMetadata)
                    .flatMap(MediaItemTag::getMaybeStreamInfo).orElse(null);
            final MediaItemTag.AudioTrack previousAudioTrack =
                    Optional.ofNullable(currentMetadata)
                            .flatMap(MediaItemTag::getMaybeAudioTrack).orElse(null);
            currentMetadata = tag;

            if (!currentMetadata.getErrors().isEmpty()) {
                // new errors might have been added even if previousInfo == tag.getMaybeStreamInfo()
                final ErrorInfo errorInfo = new ErrorInfo(
                        currentMetadata.getErrors(),
                        UserAction.PLAY_STREAM,
                        "Loading failed for [" + currentMetadata.getTitle()
                                + "]: " + currentMetadata.getStreamUrl(),
                        currentMetadata.getServiceId(),
                        currentMetadata.getStreamUrl());
                ErrorUtil.createNotification(context, errorInfo);
            }

            currentMetadata.getMaybeStreamInfo().ifPresent(info -> {
                if (DEBUG) {
                    Log.d(TAG, "ExoPlayer - onEvents() update stream info: " + info.getName());
                }
                if (previousInfo == null || !previousInfo.getUrl().equals(info.getUrl())) {
                    // only update with the new stream info if it has actually changed
                    updateMetadataWith(info);
                } else if (previousAudioTrack == null
                        || tag.getMaybeAudioTrack()
                        .map(t -> t.getSelectedAudioStreamIndex()
                                != previousAudioTrack.getSelectedAudioStreamIndex())
                        .orElse(false)) {
                    listeners.onAudioTrackUpdate();
                }
            });
            if (loadedSourceOwnsSelectedItem()) {
                casting.onQueueItemChanged(playQueue.getItem());
            }
            casting.onSourceChanged();
        });
    }

    @Override
    public void onTracksChanged(@NonNull final Tracks tracks) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onTracksChanged(), "
                    + "track group size = " + tracks.getGroups().size());
        }
        UIs.call(playerUi -> playerUi.onTextTracksChanged(tracks));
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull final PlaybackParameters playbackParameters) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - playbackParameters(), speed = [" + playbackParameters.speed
                    + "], pitch = [" + playbackParameters.pitch + "]");
        }
        UIs.call(playerUi -> playerUi.onPlaybackParametersChanged(playbackParameters));
    }

    @Override
    public void onPositionDiscontinuity(@NonNull final PositionInfo oldPosition,
                                        @NonNull final PositionInfo newPosition,
                                        @DiscontinuityReason final int discontinuityReason) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPositionDiscontinuity() called with "
                    + "oldPositionIndex = [" + oldPosition.mediaItemIndex + "], "
                    + "oldPositionMs = [" + oldPosition.positionMs + "], "
                    + "newPositionIndex = [" + newPosition.mediaItemIndex + "], "
                    + "newPositionMs = [" + newPosition.positionMs + "], "
                    + "discontinuityReason = [" + discontinuityReason + "]");
        }
        if (playQueue == null) {
            return;
        }

        // Refresh the playback if there is a transition to the next video
        final int newIndex = newPosition.mediaItemIndex;
        if (discontinuityReason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
            sponsorBlock.resetPlayback();
        }
        playbackHistory.onDiscontinuity(historySnapshot(),
                new PlaybackHistory.Discontinuity(discontinuityReason, newIndex, getRepeatMode(),
                        isPrepared, getCurrentState() == STATE_BLOCKED));
    }

    @Override
    public void onRenderedFirstFrame() {
        UIs.call(PlayerUi::onRenderedFirstFrame);
    }

    @Override
    public void onCues(@NonNull final CueGroup cueGroup) {
        UIs.call(playerUi -> playerUi.onCues(cueGroup.cues));
    }

    /**
     * To be called when the {@code PlaybackPreparer} set in the {@link MediaSessionConnector}
     * receives an {@code onPrepare()} call. This function allows restoring the default behavior
     * that would happen if there was no playback preparer set, i.e. to just call
     * {@code player.prepare()}. You can find the default behavior in `onPlay()` inside the
     * {@link MediaSessionConnector} file.
     */
    public void onPrepare() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.prepare();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Errors
    //////////////////////////////////////////////////////////////////////////*/
    //region Errors

    /**
     * Process exceptions produced by {@link com.google.android.exoplayer2.ExoPlayer ExoPlayer}.
     * {@link PlaybackErrors} decides how to recover from each of them. For every error the
     * player cannot handle by itself, it creates a notification so users are aware.
     *
     * @see com.google.android.exoplayer2.Player.Listener#onPlayerError(PlaybackException)
     */
    @Override
    public void onPlayerError(@NonNull final PlaybackException error) {
        Log.e(TAG, "ExoPlayer - onPlayerError() called with:", error);

        saveStreamProgressState();
        final PlaybackErrors.Recovery recovery = PlaybackErrors.recoveryFor(error.errorCode);

        switch (recovery) {
            case RESTART_AT_LIVE_EDGE:
                simpleExoPlayer.seekToDefaultPosition();
                simpleExoPlayer.prepare();
                // Inform the user that we are reloading the stream by
                // switching to the buffering state
                onBuffering();
                break;
            case SKIP_STREAM:
                if (!exoPlayerIsNull() && playQueue != null) {
                    playQueue.error();
                }
                break;
            case RELOAD:
                setRecovery();
                reloadPlayQueueManager();
                break;
            case SHUT_DOWN:
            default:
                onPlaybackShutdown();
                break;
        }

        if (!recovery.isCatchable()) {
            createErrorNotification(error);
        }

        listeners.onPlayerError(error, recovery.isCatchable());
    }

    private void createErrorNotification(@NonNull final PlaybackException error) {
        final ErrorInfo errorInfo;
        if (currentMetadata == null) {
            errorInfo = new ErrorInfo(error, UserAction.PLAY_STREAM,
                    "Player error[type=" + error.getErrorCodeName()
                            + "] occurred, currentMetadata is null");
        } else {
            errorInfo = new ErrorInfo(error, UserAction.PLAY_STREAM,
                    "Player error[type=" + error.getErrorCodeName()
                            + "] occurred while playing " + currentMetadata.getStreamUrl(),
                    currentMetadata.getServiceId(), currentMetadata.getStreamUrl());
        }
        ErrorUtil.createNotification(context, errorInfo);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback position and seek
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback position and seek

    @Override // own playback listener (this is a getter)
    public boolean isApproachingPlaybackEdge(final long timeToEndMillis) {
        // If live, then not near playback edge
        // If not playing, then not approaching playback edge
        if (exoPlayerIsNull() || isLive() || !isPlaying()) {
            return false;
        }

        final long currentPositionMillis = getCurrentPosition();
        final long currentDurationMillis = getDuration();
        return currentDurationMillis - currentPositionMillis < timeToEndMillis;
    }

    /**
     * Checks if the current playback is a livestream AND is playing at or beyond the live edge.
     *
     * @return whether the livestream is playing at or beyond the edge
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isLiveEdge() {
        if (exoPlayerIsNull() || !isLive()) {
            return false;
        }

        final Timeline currentTimeline = simpleExoPlayer.getCurrentTimeline();
        final int currentWindowIndex = simpleExoPlayer.getCurrentMediaItemIndex();
        if (currentTimeline.isEmpty() || currentWindowIndex < 0
                || currentWindowIndex >= currentTimeline.getWindowCount()) {
            return false;
        }

        final Timeline.Window timelineWindow = new Timeline.Window();
        currentTimeline.getWindow(currentWindowIndex, timelineWindow);
        return timelineWindow.getDefaultPositionMs() <= simpleExoPlayer.getCurrentPosition();
    }

    @Override // own playback listener
    public void onPlaybackSynchronize(@NonNull final PlayQueueItem item, final boolean wasBlocked) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackSynchronize(was blocked: " + wasBlocked
                    + ") called with item=[" + item.getTitle() + "], url=[" + item.getUrl() + "]");
        }
        if (exoPlayerIsNull() || playQueue == null) {
            return;
        }
        if (currentItem == item) {
            // Returning to an unchanged engine source can restore previously lost coherence.
            if (currentMetadata != null) {
                casting.onSourceChanged();
            }
            return;
        }

        final int playQueueIndex = playQueue.indexOf(item);
        final int playlistIndex = simpleExoPlayer.getCurrentMediaItemIndex();
        final int playlistSize = simpleExoPlayer.getCurrentTimeline().getWindowCount();
        final boolean removeThumbnailBeforeSync = currentItem == null
                || currentItem.getServiceId() != item.getServiceId()
                || !currentItem.getUrl().equals(item.getUrl());

        casting.onQueueItemChanged(item);

        currentItem = item;

        if (playQueueIndex != playQueue.getIndex()) {
            // wrong window (this should be impossible, as this method is called with
            // `item=playQueue.getItem()`, so the index of that item must be equal to `getIndex()`)
            Log.e(TAG, "Playback - Play Queue may be not in sync: item index=["
                    + playQueueIndex + "], " + "queue index=[" + playQueue.getIndex() + "]");

        } else if ((playlistSize > 0 && playQueueIndex >= playlistSize) || playQueueIndex < 0) {
            // the queue and the player's timeline are not in sync, since the play queue index
            // points outside of the timeline
            Log.e(TAG, "Playback - Trying to seek to invalid index=[" + playQueueIndex
                    + "] with playlist length=[" + playlistSize + "]");

        } else if (wasBlocked || playlistIndex != playQueueIndex || !isPlaying()) {
            // either the player needs to be unblocked, or the play queue index has just been
            // changed and needs to be synchronized, or the player is not playing
            if (DEBUG) {
                Log.d(TAG, "Playback - Rewinding to correct index=[" + playQueueIndex + "], "
                        + "from=[" + playlistIndex + "], size=[" + playlistSize + "].");
            }

            if (removeThumbnailBeforeSync) {
                // unset the current (now outdated) thumbnail to ensure it is not used during sync
                thumbnail.clear();
            }

            // sync the player index with the queue index, and seek to the correct position
            if (item.getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET) {
                simpleExoPlayer.seekTo(playQueueIndex, item.getRecoveryPosition());
                playQueue.unsetRecovery(playQueueIndex);
            } else {
                simpleExoPlayer.seekToDefaultPosition(playQueueIndex);
            }
        }
        // Metadata may already have arrived for this occurrence before manager synchronization.
        if (currentMetadata != null) {
            casting.onSourceChanged();
        }
    }

    public void seekTo(final long positionMillis) {
        if (casting.isActive()) {
            casting.seek(positionMillis);
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "seekTo() called with: position = [" + positionMillis + "]");
        }
        if (!exoPlayerIsNull()) {
            // prevent invalid positions when fast-forwarding/-rewinding
            simpleExoPlayer.seekTo(MathUtils.clamp(positionMillis, 0,
                    simpleExoPlayer.getDuration()));
        }
    }

    private void seekBy(final long offsetMillis) {
        if (DEBUG) {
            Log.d(TAG, "seekBy() called with: offsetMillis = [" + offsetMillis + "]");
        }
        seekTo(getCurrentPosition() + offsetMillis);
    }

    public void seekToDefault() {
        if (casting.isActive()) {
            casting.seek(0);
            return;
        }
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.seekToDefaultPosition();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Player actions (play, pause, previous, fast-forward, ...)
    //////////////////////////////////////////////////////////////////////////*/
    //region Player actions (play, pause, previous, fast-forward, ...)

    public void play() {
        if (casting.isActive()) {
            casting.play();
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "play() called");
        }
        if (playbackAudio == null || playQueue == null || exoPlayerIsNull()) {
            return;
        }

        playbackAudio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);

        if (currentState == STATE_COMPLETED) {
            if (playQueue.getIndex() == 0) {
                seekToDefault();
            } else {
                playQueue.setIndex(0);
            }
        }

        simpleExoPlayer.play();
        saveStreamProgressState();
    }

    public void pause() {
        if (casting.isActive()) {
            casting.pause();
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "pause() called");
        }
        if (playbackAudio == null || exoPlayerIsNull()) {
            return;
        }

        playbackAudio.onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED);
        simpleExoPlayer.pause();
        saveStreamProgressState();
    }

    public void playPause() {
        if (DEBUG) {
            Log.d(TAG, "onPlayPause() called");
        }

        if (getPlayWhenReady()
                // When state is completed (replay button is shown) then (re)play and do not pause
                && currentState != STATE_COMPLETED) {
            pause();
        } else {
            play();
        }
    }

    public void playPrevious() {
        if (DEBUG) {
            Log.d(TAG, "onPlayPrevious() called");
        }
        if (exoPlayerIsNull() || playQueue == null) {
            return;
        }

        /* If current playback has run for PLAY_PREV_ACTIVATION_LIMIT_MILLIS milliseconds,
         * restart current track. Also restart the track if the current track
         * is the first in a queue.*/
        if (getCurrentPosition() > PLAY_PREV_ACTIVATION_LIMIT_MILLIS
                || playQueue.getIndex() == 0) {
            seekToDefault();
            playQueue.offsetIndex(0);
        } else {
            saveStreamProgressState();
            playQueue.offsetIndex(-1);
        }
        triggerProgressUpdate();
    }

    public void playNext() {
        if (DEBUG) {
            Log.d(TAG, "onPlayNext() called");
        }
        if (playQueue == null) {
            return;
        }

        saveStreamProgressState();
        playQueue.offsetIndex(+1);
        triggerProgressUpdate();
    }

    public void fastForward() {
        if (DEBUG) {
            Log.d(TAG, "fastForward() called");
        }
        seekBy(retrieveSeekDurationFromPreferences(this));
        triggerProgressUpdate();
    }

    public void fastRewind() {
        if (DEBUG) {
            Log.d(TAG, "fastRewind() called");
        }
        seekBy(-retrieveSeekDurationFromPreferences(this));
        triggerProgressUpdate(true);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // StreamInfo history: views and progress
    //////////////////////////////////////////////////////////////////////////*/
    //region StreamInfo history: views and progress

    private PlaybackHistory.Snapshot historySnapshot() {
        // PROGRESS accepts only a clock owned by the selected occurrence, before either
        // recovery mutation or asynchronous persistence. Accepted writes keep their lifetime.
        // VIEWED and COMPLETED remain metadata observations, independent of this clock gate.
        return new PlaybackHistory.Snapshot(getCurrentStreamInfo().orElse(null), playQueue,
                currentMetadata == null || !loadedSourceOwnsSelectedItem()
                        || casting.isActive() && !casting.ownsClock(playQueue.getItem())
                        || MediaItemTag.from(simpleExoPlayer.getCurrentMediaItem())
                                .filter(tag -> tag == currentMetadata).isEmpty()
                        ? -1 : simpleExoPlayer.getCurrentMediaItemIndex(),
                casting.isActive() ? casting.getPosition()
                        : exoPlayerIsNull() ? 0 : simpleExoPlayer.getContentPosition(),
                getCurrentPosition());
    }

    private void registerStreamViewed() {
        playbackHistory.record(PlaybackHistory.Event.VIEWED, historySnapshot());
    }

    public void saveStreamProgressState() {
        playbackHistory.record(PlaybackHistory.Event.PROGRESS, historySnapshot());
    }

    public void saveStreamProgressStateCompleted() {
        playbackHistory.record(PlaybackHistory.Event.COMPLETED, historySnapshot());
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Metadata
    //////////////////////////////////////////////////////////////////////////*/
    //region Metadata

    private void updateMetadataWith(@NonNull final StreamInfo info) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onMetadataChanged() called, playing: " + info.getName());
        }
        if (exoPlayerIsNull()) {
            return;
        }

        maybeAutoQueueNextStream(info);

        thumbnail.load(info.getThumbnails());
        registerStreamViewed();

        notifyMetadataUpdateToListeners();
        listeners.onAudioTrackUpdate();
        UIs.call(playerUi -> playerUi.onMetadataChanged(info));
    }

    @NonNull
    public String getVideoUrl() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getStreamUrl();
    }

    @NonNull
    public String getVideoUrlAtCurrentTime() {
        final long timeSeconds = getCurrentPosition() / 1000;
        String videoUrl = getVideoUrl();
        if (!isLive() && timeSeconds >= 0 && currentMetadata != null
                && currentMetadata.getServiceId() == YouTube.getServiceId()) {
            // Timestamp doesn't make sense in a live stream so drop it
            videoUrl += ("&t=" + timeSeconds);
        }
        return videoUrl;
    }

    @NonNull
    public String getVideoTitle() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getTitle();
    }

    @NonNull
    public String getUploaderName() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getUploaderName();
    }

    @Nullable
    public Bitmap getThumbnail() {
        return thumbnail.get();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Play queue, segments and streams
    //////////////////////////////////////////////////////////////////////////*/
    //region Play queue, segments and streams

    private void maybeAutoQueueNextStream(@NonNull final StreamInfo info) {
        if (playQueue == null || playQueue.getIndex() != playQueue.size() - 1
                || getRepeatMode() != REPEAT_MODE_OFF
                || !PlayerHelper.isAutoQueueEnabled(context)) {
            return;
        }
        // auto queue when starting playback on the last item when not repeating
        final PlayQueue autoQueue = PlayerHelper.autoQueueOf(info,
                playQueue.getStreams());
        if (autoQueue != null) {
            playQueue.append(autoQueue.getStreams());
        }
    }

    public void selectQueueItem(final PlayQueueItem item) {
        if (playQueue == null || exoPlayerIsNull()) {
            return;
        }

        final int index = playQueue.indexOf(item);
        if (index == -1) {
            return;
        }

        if (playQueue.getIndex() == index && simpleExoPlayer.getCurrentMediaItemIndex() == index) {
            seekToDefault();
        } else {
            saveStreamProgressState();
        }
        playQueue.setIndex(index);
    }

    @Override
    public void onPlayQueueEdited() {
        notifyPlaybackUpdateToListeners();
        UIs.call(PlayerUi::onPlayQueueEdited);
    }

    @Override // own playback listener
    @Nullable
    public MediaSource sourceOf(final PlayQueueItem item, final StreamInfo info) {
        return playbackSources.resolve(info, audioPlayerSelected(), isAudioOnly);
    }

    public void disablePreloadingOfCurrentTrack() {
        loadController.disablePreloadingOfCurrentTrack();
    }

    public Optional<VideoStream> getSelectedVideoStream() {
        return Optional.ofNullable(currentMetadata)
                .flatMap(MediaItemTag::getMaybeQuality)
                .filter(quality -> {
                    final int selectedStreamIndex = quality.getSelectedVideoStreamIndex();
                    return selectedStreamIndex >= 0
                            && selectedStreamIndex < quality.getSortedVideoStreams().size();
                })
                .map(quality -> quality.getSortedVideoStreams()
                        .get(quality.getSelectedVideoStreamIndex()));
    }

    public Optional<AudioStream> getSelectedAudioStream() {
        return Optional.ofNullable(currentMetadata)
                .flatMap(MediaItemTag::getMaybeAudioTrack)
                .map(MediaItemTag.AudioTrack::getSelectedAudioStream);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Captions (text tracks)
    //////////////////////////////////////////////////////////////////////////*/
    //region Captions (text tracks)

    public int getCaptionRendererIndex() {
        if (exoPlayerIsNull()) {
            return RENDERER_UNAVAILABLE;
        }

        for (int t = 0; t < simpleExoPlayer.getRendererCount(); t++) {
            if (simpleExoPlayer.getRendererType(t) == C.TRACK_TYPE_TEXT) {
                return t;
            }
        }

        return RENDERER_UNAVAILABLE;
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Video size
    //////////////////////////////////////////////////////////////////////////*/
    //region Video size
    @Override // exoplayer listener
    public void onVideoSizeChanged(@NonNull final VideoSize videoSize) {
        if (DEBUG) {
            Log.d(TAG, "onVideoSizeChanged() called with: "
                    + "width / height = [" + videoSize.width + " / " + videoSize.height
                    + " = " + (((float) videoSize.width) / videoSize.height) + "], "
                    + "unappliedRotationDegrees = [" + videoSize.unappliedRotationDegrees + "], "
                    + "pixelWidthHeightRatio = [" + videoSize.pixelWidthHeightRatio + "]");
        }

        UIs.call(playerUi -> playerUi.onVideoSizeChanged(videoSize));
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Activity / fragment binding
    //////////////////////////////////////////////////////////////////////////*/
    //region Activity / fragment binding

    public void setFragmentListener(final PlayerServiceEventListener listener) {
        listeners.setFragmentListener(listener);
        UIs.call(PlayerUi::onFragmentListenerSet);
        notifyQueueUpdateToListeners();
        notifyMetadataUpdateToListeners();
        notifyPlaybackUpdateToListeners();
        triggerProgressUpdate();
    }

    public void removeFragmentListener(final PlayerServiceEventListener listener) {
        listeners.removeFragmentListener(listener);
    }

    void setActivityListener(final PlayerEventListener listener) {
        listeners.setActivityListener(listener);
        // TODO why not queue update?
        notifyMetadataUpdateToListeners();
        notifyPlaybackUpdateToListeners();
        triggerProgressUpdate();
    }

    void removeActivityListener(final PlayerEventListener listener) {
        listeners.removeActivityListener(listener);
    }

    void stopActivityBinding() {
        listeners.onServiceStopped();
    }

    private void notifyQueueUpdateToListeners() {
        if (playQueue != null) {
            listeners.onQueueUpdate(playQueue);
        }
    }

    private void notifyMetadataUpdateToListeners() {
        getCurrentStreamInfo().ifPresent(info -> listeners.onMetadataUpdate(info, playQueue));
    }

    private void notifyPlaybackUpdateToListeners() {
        if (!exoPlayerIsNull() && playQueue != null) {
            listeners.onPlaybackUpdate(currentState, getRepeatMode(), playQueue.isShuffled(),
                    getPlaybackParameters());
        }
    }

    public void useVideoAndSubtitles(final boolean videoAndSubtitlesEnabled) {
        if (casting.isActive()) {
            return; // Moving the app to the background must not replace the cast video source.
        }
        if (playQueue == null) {
            return;
        }

        isAudioOnly = !videoAndSubtitlesEnabled;

        final var item = playQueue.getItem();
        final boolean hasPendingRecovery =
                item != null && item.getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET;
        final boolean hasTimeline =
                !exoPlayerIsNull() && !simpleExoPlayer.getCurrentTimeline().isEmpty();


        if (hasTimeline || !hasPendingRecovery) {
            // Preserve pending recovery until a timeline exists.
            setRecovery();
        }
        final boolean reloadNeeded = playbackSources.requiresReload(currentMetadata,
                getVideoRendererIndex() != RENDERER_UNAVAILABLE);
        if (reloadNeeded) {
            reloadPlayQueueManager();
        }

        // Disable or enable video and subtitles renderers depending of the
        // videoAndSubtitlesEnabled value
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !videoAndSubtitlesEnabled)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoAndSubtitlesEnabled));
    }

    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Getters
    //////////////////////////////////////////////////////////////////////////*/
    //region Getters

    public Optional<StreamInfo> getCurrentStreamInfo() {
        return Optional.ofNullable(currentMetadata).flatMap(MediaItemTag::getMaybeStreamInfo);
    }

    public int getCurrentState() {
        return currentState;
    }

    public boolean exoPlayerIsNull() {
        return simpleExoPlayer == null;
    }

    public ExoPlayer getExoPlayer() {
        return simpleExoPlayer;
    }

    public boolean isStopped() {
        if (casting.isActive()) {
            return false;
        }
        return exoPlayerIsNull() || simpleExoPlayer.getPlaybackState() == ExoPlayer.STATE_IDLE;
    }

    public boolean isPlaying() {
        return casting.isActive() ? currentState == STATE_PLAYING
                : !exoPlayerIsNull() && simpleExoPlayer.isPlaying();
    }

    public boolean getPlayWhenReady() {
        return casting.isActive() ? casting.getPlayWhenReady()
                : !exoPlayerIsNull() && simpleExoPlayer.getPlayWhenReady();
    }

    public boolean isLoading() {
        return casting.isActive() ? casting.isPreparing()
                : !exoPlayerIsNull() && simpleExoPlayer.isLoading();
    }

    private void setPlaying(final boolean playing) {
        if (casting.isActive()) {
            if (playing) {
                casting.play();
            } else {
                casting.pause();
            }
        } else {
            simpleExoPlayer.setPlayWhenReady(playing);
        }
    }

    private void seekToCurrentItem(final long position) {
        if (casting.isActive()) {
            casting.seek(position);
        } else {
            // Timestamp intents may arrive before the timeline is prepared: retain ExoPlayer's
            // pending indexed seek instead of clamping against an unknown/old duration.
            simpleExoPlayer.seekTo(playQueue.getIndex(), position);
        }
    }

    public CastPlayback getCasting() {
        return casting;
    }

    public long getCurrentPosition() {
        return casting.isActive() ? casting.getPosition()
                : exoPlayerIsNull() ? 0 : simpleExoPlayer.getCurrentPosition();
    }

    public long getDuration() {
        return casting.isActive() ? casting.getDuration()
                : exoPlayerIsNull() ? 0 : simpleExoPlayer.getDuration();
    }

    public void pauseLocalForCasting() {
        playbackAudio.onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED);
        simpleExoPlayer.pause();
    }

    public void restoreLocalAfterCasting(final long position, final boolean playHere) {
        if (exoPlayerIsNull()) {
            return;
        }
        // A negative position means casting had only a reset/pending clock. Leave the
        // selected occurrence's engine position/recovery for normal synchronization.
        if (position >= 0) {
            simpleExoPlayer.seekTo(position);
        } else if (playHere && loadedSourceOwnsSelectedItem()
                && playQueue.getItem().getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET) {
            // Play here can precede manager synchronization. Apply B's pending recovery
            // before play() checkpoints its local preview; leave consumption to synchronization.
            simpleExoPlayer.seekTo(playQueue.getIndex(), playQueue.getItem().getRecoveryPosition());
        }
        if (currentState == STATE_PAUSED_SEEK) {
            changeState(STATE_PAUSED);
        }
        updatePlaybackState(false, simpleExoPlayer.getPlaybackState());
        if (playHere) {
            play();
        }
        triggerProgressUpdate();
    }

    public void onCastingStateChanged(final int state) {
        isPrepared = true;
        if (currentState != state && currentState != STATE_PAUSED_SEEK) {
            changeState(state);
        }
    }

    public void onCastingChanged() {
        UIs.call(PlayerUi::onCastingChanged);
        UIs.call(playerUi -> playerUi.onMuteUnmuteChanged(isMuted()));
        notifyPlaybackUpdateToListeners();
    }

    public void onCastingEnded() {
        saveStreamProgressStateCompleted();
        if (getRepeatMode() == REPEAT_MODE_ONE
                || getRepeatMode() == REPEAT_MODE_ALL && playQueue != null
                && playQueue.size() == 1) {
            casting.play();
        } else if (playQueue != null && getRepeatMode() == REPEAT_MODE_ALL
                && playQueue.getIndex() == playQueue.size() - 1) {
            casting.setPlayWhenReadyForSource(true);
            casting.onPlayerReset();
            playQueue.setIndex(0);
        } else {
            if (playQueue != null && playQueue.getIndex() < playQueue.size() - 1) {
                casting.setPlayWhenReadyForSource(true);
            }
            changeState(STATE_COMPLETED);
        }
    }

    private boolean isLive() {
        try {
            return !exoPlayerIsNull() && simpleExoPlayer.isCurrentMediaItemDynamic();
        } catch (final IndexOutOfBoundsException e) {
            // Why would this even happen =(... but lets log it anyway, better safe than sorry
            if (DEBUG) {
                Log.d(TAG, "player.isCurrentWindowDynamic() failed: ", e);
            }
            return false;
        }
    }

    public void setPlaybackQuality(@Nullable final String quality) {
        saveStreamProgressState();
        setRecovery();
        playbackSources.setPlaybackQuality(quality);
        reloadPlayQueueManager();
    }

    public void setAudioTrack(@Nullable final String audioTrackId) {
        saveStreamProgressState();
        setRecovery();
        playbackSources.setAudioTrack(audioTrackId);
        reloadPlayQueueManager();
    }


    @NonNull
    public Context getContext() {
        return context;
    }

    @NonNull
    public SharedPreferences getPrefs() {
        return prefs;
    }


    public PlayerType getPlayerType() {
        return playerType;
    }

    public boolean audioPlayerSelected() {
        return playerType == PlayerType.AUDIO;
    }

    public boolean videoPlayerSelected() {
        return playerType == PlayerType.MAIN;
    }

    public boolean popupPlayerSelected() {
        return playerType == PlayerType.POPUP;
    }


    @Nullable
    public PlayQueue getPlayQueue() {
        return playQueue;
    }

    public PlaybackAudio getPlaybackAudio() {
        return playbackAudio;
    }

    public PlayerService getService() {
        return service;
    }

    public boolean isAudioOnly() {
        return isAudioOnly;
    }

    @NonNull
    public DefaultTrackSelector getTrackSelector() {
        return trackSelector;
    }

    @Nullable
    public MediaItemTag getCurrentMetadata() {
        return currentMetadata;
    }

    @Nullable
    public PlayQueueItem getCurrentItem() {
        return currentItem;
    }

    public Optional<PlayerServiceEventListener> getFragmentListener() {
        return listeners.getFragmentListener();
    }

    //endregion

    /*//////////////////////////////////////////////////////////////////////////
    // SponsorBlock
    //////////////////////////////////////////////////////////////////////////*/
    //region

    public SponsorBlockMode getSponsorBlockMode() {
        return sponsorBlock.getMode();
    }

    public void setSponsorBlockMode(final SponsorBlockMode mode) {
        sponsorBlock.setMode(mode);
    }

    //endregion

    /**
     * Gets the user interfaces connected to this player.
     *
     * @return the user interfaces connected with the player
     */
    @SuppressWarnings("MethodName") // keep the unusual method name
    public PlayerUiList UIs() {
        return UIs;
    }

    /**
     * Get the video renderer index of the current playing stream.
     * <p>
     * This method returns the video renderer index of the current
     * {@link MappingTrackSelector.MappedTrackInfo} or {@link #RENDERER_UNAVAILABLE} if the current
     * {@link MappingTrackSelector.MappedTrackInfo} is null or if there is no video renderer index.
     *
     * @return the video renderer index or {@link #RENDERER_UNAVAILABLE} if it cannot be get
     */
    private int getVideoRendererIndex() {
        final MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector
                .getCurrentMappedTrackInfo();

        if (mappedTrackInfo == null) {
            return RENDERER_UNAVAILABLE;
        }

        // Check every renderer
        return IntStream.range(0, mappedTrackInfo.getRendererCount())
                // Check the renderer is a video renderer and has at least one track
                .filter(i -> !mappedTrackInfo.getTrackGroups(i).isEmpty()
                        && simpleExoPlayer.getRendererType(i) == C.TRACK_TYPE_VIDEO)
                // Return the first index found (there is at most one renderer per renderer type)
                .findFirst()
                // No video renderer index with at least one track found: return unavailable index
                .orElse(RENDERER_UNAVAILABLE);
    }
    //endregion

    /**
     * Reports whether the device screen is on.
     *
     * @return whether the device screen is turned on.
     */
    public boolean isScreenOn() {
        return broadcasts.isScreenOn();
    }
}
