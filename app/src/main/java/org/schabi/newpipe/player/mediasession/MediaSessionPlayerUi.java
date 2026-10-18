package org.schabi.newpipe.player.mediasession;

import static org.schabi.newpipe.MainActivity.DEBUG;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.session.MediaButtonReceiver;

import androidx.media.VolumeProviderCompat;
import com.google.android.exoplayer2.ForwardingPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player.RepeatMode;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.notification.NotificationActionData;
import org.schabi.newpipe.player.notification.NotificationConstants;
import org.schabi.newpipe.player.ui.PlayerUi;
import org.schabi.newpipe.player.ui.VideoPlayerUi;
import org.schabi.newpipe.util.StreamTypeUtil;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MediaSessionPlayerUi extends PlayerUi
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "MediaSessUi";

    @NonNull
    private final MediaSessionCompat mediaSession;
    @NonNull
    private final MediaSessionConnector sessionConnector;

    private final String ignoreHardwareMediaButtonsKey;
    private boolean shouldIgnoreHardwareMediaButtons = false;

    // used to check whether any notification action changed, before sending costly updates
    private List<NotificationActionData> prevNotificationActions = List.of();
    @Nullable private VolumeProviderCompat castVolume;


    public MediaSessionPlayerUi(@NonNull final Player player,
                                @NonNull final MediaSessionCompat mediaSession,
                                @NonNull final MediaSessionConnector sessionConnector) {
        super(player);
        this.mediaSession = mediaSession;
        this.sessionConnector = sessionConnector;
        this.ignoreHardwareMediaButtonsKey =
                context.getString(R.string.ignore_hardware_media_buttons_key);
    }

    @Override
    public void initPlayer() {
        super.initPlayer();
        destroyPlayer(); // release previously used resources

        mediaSession.setActive(true);

        sessionConnector.setQueueNavigator(new PlayQueueNavigator(mediaSession, player));
        sessionConnector.setPlayer(getForwardingPlayer());

        // It seems like events from the Media Control UI in the notification area don't go through
        // this function, so it's safe to just ignore all events in case we want to ignore the
        // hardware media buttons. Returning true stops all further event processing of the system.
        sessionConnector.setMediaButtonEventHandler((p, i) -> shouldIgnoreHardwareMediaButtons);

        // listen to changes to ignore_hardware_media_buttons_key
        updateShouldIgnoreHardwareMediaButtons(player.getPrefs());
        player.getPrefs().registerOnSharedPreferenceChangeListener(this);

        sessionConnector.setMetadataDeduplicationEnabled(true);
        sessionConnector.setMediaMetadataProvider(exoPlayer -> buildMediaMetadata());

        // force updating media session actions by resetting the previous ones
        prevNotificationActions = List.of();
        updateMediaSessionActions();
    }

    @Override
    public void destroyPlayer() {
        super.destroyPlayer();
        player.getPrefs().unregisterOnSharedPreferenceChangeListener(this);
        sessionConnector.setMediaButtonEventHandler(null);
        sessionConnector.setPlayer(null);
        sessionConnector.setQueueNavigator(null);
        mediaSession.setActive(false);
        prevNotificationActions = List.of();
    }

    @Override
    public void onThumbnailLoaded(@Nullable final Bitmap bitmap) {
        super.onThumbnailLoaded(bitmap);
        // the thumbnail is now loaded: invalidate the metadata to trigger a metadata update
        sessionConnector.invalidateMediaSessionMetadata();
    }


    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                                          final String key) {
        if (key == null || key.equals(ignoreHardwareMediaButtonsKey)) {
            updateShouldIgnoreHardwareMediaButtons(sharedPreferences);
        }
    }

    public void updateShouldIgnoreHardwareMediaButtons(final SharedPreferences sharedPreferences) {
        shouldIgnoreHardwareMediaButtons =
                sharedPreferences.getBoolean(ignoreHardwareMediaButtonsKey, false);
    }


    public void handleMediaButtonIntent(final Intent intent) {
        MediaButtonReceiver.handleIntent(mediaSession, intent);
    }

    public Optional<MediaSessionCompat.Token> getSessionToken() {
        return Optional.ofNullable(mediaSession).map(MediaSessionCompat::getSessionToken);
    }


    private ForwardingPlayer getForwardingPlayer() {
        // ForwardingPlayer defaults to local ExoPlayer; route owned commands and receiver state
        // through Player instead.
        return new ForwardingPlayer(player.getExoPlayer()) {
            @Override
            public void play() {
                player.play();
                // hide the player controls even if the play command came from the media session
                player.UIs().get(VideoPlayerUi.class).ifPresent(ui -> ui.hideControls(0, 0));
            }

            @Override
            public void pause() {
                player.pause();
            }

            @Override
            public void stop() {
                if (player.getCasting().isActive()
                        || player.getCasting().getSession().getConnectionState() != 0) {
                    player.getCasting().stop(false);
                }
                super.stop();
            }

            @Override
            public void setPlayWhenReady(final boolean ready) {
                if (ready) {
                    player.play();
                } else {
                    player.pause();
                }
            }

            @Override
            public boolean getPlayWhenReady() {
                return player.getPlayWhenReady();
            }

            @Override
            public boolean isPlaying() {
                return player.isPlaying();
            }

            @Override
            public int getPlaybackState() {
                if (!player.getCasting().isActive()) {
                    return super.getPlaybackState();
                }
                return player.getCurrentState() == Player.STATE_BUFFERING ? STATE_BUFFERING
                        : player.getCurrentState() == Player.STATE_COMPLETED ? STATE_ENDED
                        : STATE_READY;
            }

            @Override
            public long getCurrentPosition() {
                return player.getCurrentPosition();
            }

            @Override
            public long getContentPosition() {
                return player.getCasting().isActive() ? player.getCurrentPosition()
                        : super.getContentPosition();
            }

            @Override
            public long getDuration() {
                return player.getDuration();
            }

            @Override
            public PlaybackParameters getPlaybackParameters() {
                return player.getPlaybackParameters();
            }

            @Override
            public void seekTo(final long positionMs) {
                player.seekTo(positionMs);
            }

            @Override
            public void seekTo(final int mediaItemIndex, final long positionMs) {
                if (player.getCasting().isActive()
                        && mediaItemIndex == player.getExoPlayer().getCurrentMediaItemIndex()) {
                    player.seekTo(positionMs);
                } else {
                    super.seekTo(mediaItemIndex, positionMs);
                }
            }

            @Override
            public void seekBack() {
                player.fastRewind();
            }

            @Override
            public void seekForward() {
                player.fastForward();
            }
        };
    }

    private MediaMetadataCompat buildMediaMetadata() {
        if (DEBUG) {
            Log.d(TAG, "buildMediaMetadata called");
        }

        // set title and artist
        final MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, player.getVideoTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, player.getUploaderName());

        // set duration (-1 for livestreams or if unknown, see the METADATA_KEY_DURATION docs)
        final long duration = player.getCurrentStreamInfo()
                .filter(info -> !StreamTypeUtil.isLiveStream(info.getStreamType()))
                .map(info -> info.getDuration() * 1000L)
                .orElse(-1L);
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);

        // set album art, unless the user asked not to, or there is no thumbnail available
        final boolean showThumbnail = player.getPrefs().getBoolean(
                context.getString(R.string.show_thumbnail_key), true);
        Optional.ofNullable(player.getThumbnail())
                .filter(bitmap -> showThumbnail)
                .ifPresent(bitmap -> {
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap);
                });

        return builder.build();
    }


    private void updateMediaSessionActions() {
        // On Android 13+ (or Android T or API 33+) the actions in the player notification can't be
        // controlled directly anymore, but are instead derived from custom media session actions.
        // However the system allows customizing only two of these actions, since the other three
        // are fixed to play-pause-buffering, previous, next.

        if (!mediaSession.isActive()) {
            // mediaSession will be inactive after destroyPlayer is called
            return;
        }
        sessionConnector.invalidateMediaSessionPlaybackState();

        // These two preferences retain the original fourth and fifth slot keys.
        final List<NotificationActionData> newNotificationActions =
                IntStream.range(0, NotificationConstants.SLOT_PREF_KEYS.length)
                .map(i -> player.getPrefs().getInt(
                        player.getContext().getString(NotificationConstants.SLOT_PREF_KEYS[i]),
                        NotificationConstants.SLOT_DEFAULTS[i]))
                .mapToObj(action -> NotificationActionData
                        .fromNotificationActionEnum(player, action))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // avoid costly notification actions update, if nothing changed from last time
        if (!newNotificationActions.equals(prevNotificationActions)) {
            prevNotificationActions = newNotificationActions;
            sessionConnector.setCustomActionProviders(
                    newNotificationActions.stream()
                            .map(data -> new SessionConnectorActionProvider(data, context))
                            .toArray(SessionConnectorActionProvider[]::new));
        }
    }

    @Override
    public void onUpdateProgress(final int currentProgress, final int duration,
                                 final int bufferPercent) {
        if (player.getCasting().isActive()) {
            sessionConnector.invalidateMediaSessionPlaybackState();
        }
    }

    @Override
    public void onCastingChanged() {
        if (player.getCasting().isActive()
                && player.getCasting().getSession().getVolumeSupported()) {
            final int volume = (int) Math.round(player.getCasting().getSession().getVolume() * 100);
            if (castVolume == null) {
                castVolume = new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE,
                        100, volume) {
                    @Override
                    public void onSetVolumeTo(final int value) {
                        player.getCasting().getSession().setVolume(value / 100.0);
                    }

                    @Override
                    public void onAdjustVolume(final int direction) {
                        player.getCasting().getSession().setVolume(Math.max(0, Math.min(1,
                                player.getCasting().getSession().getVolume() + direction * 0.05)));
                    }
                };
                mediaSession.setPlaybackToRemote(castVolume);
            } else {
                castVolume.setCurrentVolume(volume);
            }
        } else if (castVolume != null) {
            castVolume = null;
            mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
        }
        sessionConnector.invalidateMediaSessionPlaybackState();
    }

    @Override
    public void onBlocked() {
        super.onBlocked();
        updateMediaSessionActions();
    }

    @Override
    public void onPlaying() {
        super.onPlaying();
        updateMediaSessionActions();
    }

    @Override
    public void onBuffering() {
        super.onBuffering();
        updateMediaSessionActions();
    }

    @Override
    public void onPaused() {
        super.onPaused();
        updateMediaSessionActions();
    }

    @Override
    public void onPausedSeek() {
        super.onPausedSeek();
        updateMediaSessionActions();
    }

    @Override
    public void onCompleted() {
        super.onCompleted();
        updateMediaSessionActions();
    }

    @Override
    public void onRepeatModeChanged(@RepeatMode final int repeatMode) {
        super.onRepeatModeChanged(repeatMode);
        updateMediaSessionActions();
    }

    @Override
    public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled);
        updateMediaSessionActions();
    }

    @Override
    public void onBroadcastReceived(final Intent intent) {
        super.onBroadcastReceived(intent);
        if (ACTION_RECREATE_NOTIFICATION.equals(intent.getAction())) {
            // the notification actions changed
            updateMediaSessionActions();
        }
    }

    @Override
    public void onMetadataChanged(@NonNull final StreamInfo info) {
        super.onMetadataChanged(info);
        updateMediaSessionActions();
    }

    @Override
    public void onPlayQueueEdited() {
        super.onPlayQueueEdited();
        updateMediaSessionActions();
    }
}
