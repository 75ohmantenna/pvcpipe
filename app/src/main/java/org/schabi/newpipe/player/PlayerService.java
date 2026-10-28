/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * Part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.player;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.IntentCompat;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.schabi.newpipe.R;
import org.schabi.newpipe.ktx.BundleKt;
import org.schabi.newpipe.player.mediabrowser.MediaBrowserImpl;
import org.schabi.newpipe.player.mediabrowser.LegacyBrowserBridge;
import org.schabi.newpipe.player.mediabrowser.MediaBrowserPlaybackPreparer;
import org.schabi.newpipe.player.mediabrowser.SessionLibraryCallback;
import org.schabi.newpipe.player.mediasession.PvcSessionPlayer;
import org.schabi.newpipe.player.notification.NotificationActionData;
import org.schabi.newpipe.player.notification.NotificationConstants;
import org.schabi.newpipe.player.notification.NotificationPlayerUi;
import org.schabi.newpipe.player.notification.NotificationUtil;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ThemeHelper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;


/**
 * One service for all players.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerService extends MediaLibraryService {
    private static final String TAG = PlayerService.class.getSimpleName();
    private static final boolean DEBUG = Player.DEBUG;

    public static final String SHOULD_START_FOREGROUND_EXTRA = "should_start_foreground_extra";
    public static final String BIND_PLAYER_HOLDER_ACTION = "bind_player_holder_action";
    private static final String INTERNAL_START_TOKEN_EXTRA =
            "org.schabi.newpipe.player.INTERNAL_START_TOKEN";
    private static final String INTERNAL_START_TOKEN_PREFERENCES = "player_service_security";
    private static final String INTERNAL_START_TOKEN_KEY = "internal_start_token";

    // These objects are used to cleanly separate the Service implementation (in this file) and the
    // media browser and playback preparer implementations. At the moment the playback preparer is
    // only used in conjunction with the media browser.
    private MediaBrowserImpl mediaBrowserImpl;
    private MediaBrowserPlaybackPreparer mediaBrowserPlaybackPreparer;

    private MediaLibrarySession mediaSession;
    private PvcSessionPlayer sessionPlayer;
    private SessionLibraryCallback libraryCallback;
    private NotificationUtil notificationProvider;
    private boolean notificationVisible;
    private boolean foreground;
    private List<CommandButton> customButtons = List.of();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String BROWSER_REQUEST = "browser_request";
    @Nullable private LegacyBrowserBridge legacyBrowser;
    @Nullable private String browserRequest;
    @Nullable private SettableFuture<Void> browserPreparation;
    @Nullable private Disposable browserResolution;

    @Nullable
    private Player player;

    private final IBinder mBinder = new PlayerService.LocalBinder(this);

    /**
     * The parameter taken by this {@link Consumer} can be null to indicate the player is being
     * stopped.
     */
    @Nullable
    private Consumer<Player> onPlayerStartedOrStopped = null;


    //region Service lifecycle
    @Override
    public void onCreate() {
        super.onCreate();

        if (DEBUG) {
            Log.d(TAG, "onCreate() called");
        }
        ThemeHelper.setTheme(this);

        mediaBrowserImpl = new MediaBrowserImpl(this, parent -> mainHandler.post(() -> {
            if (libraryCallback != null) {
                libraryCallback.notifyChildrenChanged(mediaSession, parent);
                if (legacyBrowser != null) {
                    legacyBrowser.notifyCatalogChanged(parent);
                }
            }
        }));
        sessionPlayer = new PvcSessionPlayer(Looper.getMainLooper(), this::prepareBrowserMediaId);
        mediaBrowserPlaybackPreparer = new MediaBrowserPlaybackPreparer(this);
        libraryCallback = new SessionLibraryCallback(this, mediaBrowserImpl);
        mediaSession = new MediaLibrarySession.Builder(this, sessionPlayer, libraryCallback)
                .build();
        addSession(mediaSession);
        if (Build.VERSION.SDK_INT < 28) {
            legacyBrowser = new LegacyBrowserBridge(this, mediaBrowserImpl,
                    mediaSession.getPlatformToken());
            legacyBrowser.onCreate();
        }
        notificationProvider = new NotificationUtil(this);
        setMediaNotificationProvider(notificationProvider);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        // Perform framework bookkeeping without exposing internal extras or arbitrary actions.
        // Sanitized public media keys are delegated separately after the active-player check.
        super.onStartCommand(null, flags, startId);
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final boolean internalIntent = isInternalIntent(intent);
        final boolean mediaButtonIntent = Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction());
        if (DEBUG) {
            Log.d(TAG, "onStartCommand() called with: action = [" + intent.getAction()
                    + "], authenticated extras = ["
                    + (internalIntent ? BundleKt.toDebugString(intent.getExtras()) : "hidden")
                    + "], flags = [" + flags + "], startId = [" + startId + "]");
        }
        if (!internalIntent) {
            if (mediaButtonIntent && player != null) {
                // Media-button starts are public by design. Only pass their key event to the
                // media session; never honor internal player extras from an untrusted caller.
                handleExternalMediaButtonIntent(intent);
                return START_NOT_STICKY;
            }
            Log.w(TAG, "Ignoring an unauthenticated service start intent");
            if (player == null) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        final String request = intent.getStringExtra(BROWSER_REQUEST);
        if (request != null) {
            if (!request.equals(browserRequest) || browserPreparation == null
                    || browserPreparation.isDone()) {
                finishStaleBrowserStart(intent, startId);
                return START_NOT_STICKY;
            }
            intent.putExtra(Player.RESUME_PLAYBACK, sessionPlayer.browserPlayWhenReady());
        } else {
            cancelBrowserPreparation();
        }

        // Playback-start intents carry SHOULD_START_FOREGROUND_EXTRA so starts made through
        // startForegroundService() create a notification and enter the foreground. Timestamp
        // intents do not carry this flag and do not initialize a player here.
        if (intent.getBooleanExtra(SHOULD_START_FOREGROUND_EXTRA, false)) {
            final boolean playerWasNull = (player == null);
            if (playerWasNull) {
                // make sure the player exists, in case the service was resumed
                player = new Player(this);
                sessionPlayer.attach(player);
            }

            // Be sure that the player notification is set and the service is started in foreground,
            // otherwise, the app may crash on Android 8+ as the service would never be put in the
            // foreground while we said to the system we would do so. For requests bearing this
            // flag, ensure a notification exists and start the service in the foreground.
            // If the service is already started in foreground, requesting it to be started
            // shouldn't do anything.
            player.UIs().get(NotificationPlayerUi.class)
                    .ifPresent(NotificationPlayerUi::createNotificationAndStartForeground);

            if (playerWasNull && onPlayerStartedOrStopped != null) {
                // notify that a new player was created (but do it after creating the foreground
                // notification just to make sure we don't incur, due to slowness, in
                // "Context.startForegroundService() did not then call Service.startForeground()")
                onPlayerStartedOrStopped.accept(player);
            }
        }

        if (player == null) {
            // No need to process media button's actions or other system intents if the player is
            // not running. However, since the current intent might have been issued by the system
            // with `startForegroundService()` (for unknown reasons), we need to ensure that we post
            // a (dummy) foreground notification, otherwise we'd incur in
            // "Context.startForegroundService() did not then call Service.startForeground()". Then
            // we stop the service again.
            Log.d(TAG, "onStartCommand() got a useless intent, closing the service");
            showPlayerNotification(true);
            destroyPlayerAndStopService();
            return START_NOT_STICKY;
        }

        final PlayerType oldPlayerType = player.getPlayerType();
        player.handleIntent(intent);
        player.handleIntentPost(oldPlayerType);
        refreshSession();
        if (request != null && browserPreparation != null) {
            final SettableFuture<Void> prepared = browserPreparation;
            browserPreparation = null;
            browserRequest = null;
            prepared.set(null);
        }

        return START_NOT_STICKY;
    }

    private void handleExternalMediaButtonIntent(@NonNull final Intent intent) {
        try {
            final KeyEvent keyEvent = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_KEY_EVENT, KeyEvent.class);
            if (keyEvent == null) {
                Log.w(TAG, "Ignoring a media-button intent without a key event");
                return;
            }

            final Intent sanitizedIntent = new Intent(Intent.ACTION_MEDIA_BUTTON)
                    .putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
            super.onStartCommand(sanitizedIntent, 0, 0);
        } catch (final BadParcelableException | ClassCastException exception) {
            Log.w(TAG, "Ignoring a malformed media-button intent", exception);
        }
    }

    public void stopForImmediateReusing() {
        if (DEBUG) {
            Log.d(TAG, "stopForImmediateReusing() called");
        }

        if (player != null && !player.exoPlayerIsNull()) {
            // Releases wifi & cpu, disables keepScreenOn, etc.
            // We can't just pause the player here because it will make transition
            // from one stream to a new stream not smooth
            player.smoothStopForImmediateReusing();
        }
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        if (player != null && (!player.videoPlayerSelected() || player.getCasting().isActive())) {
            return;
        }
        // Release video playback and its foreground notification, but let Android stop the
        // service only when no media-browser clients remain bound.
        destroyPlayerAndStopService();
    }

    @Override
    public void onDestroy() {
        if (DEBUG) {
            Log.d(TAG, "destroy() called");
        }
        cleanup();
        cancelBrowserPreparation();
        libraryCallback.dispose();
        if (legacyBrowser != null) {
            legacyBrowser.onDestroy();
        }
        sessionPlayer.release();

        mediaSession.release();
        mediaBrowserImpl.dispose();
        super.onDestroy();
    }

    private void cleanup() {
        if (player != null) {
            if (onPlayerStartedOrStopped != null) {
                // notify that the player is being destroyed
                onPlayerStartedOrStopped.accept(null);
            }
            player.destroy();
            player = null;
        }

        // Browsers retain an idle session after the application player is retired.
        sessionPlayer.attach(null);
        customButtons = List.of();
        mediaSession.setMediaButtonPreferences(customButtons);
        mediaSession.setSessionActivity(null);
        hidePlayerNotification();

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    /**
     * Destroys the player and allows the player instance to be garbage collected.
     * Leaves the browser session idle. Stops the foreground service and removes the player
     * notification
     * associated with it. Tries to stop the {@link PlayerService} completely, but this step will
     * have no effect in case some service connection still uses the service (e.g. the Android Auto
     * system accesses the media browser even when no player is running).
     */
    public void destroyPlayerAndStopService() {
        if (DEBUG) {
            Log.d(TAG, "destroyPlayerAndStopService() called");
        }

        cancelBrowserPreparation();
        cleanup();

        // Remove the started-service lifetime; bound browser clients can keep the service alive.
        // stopSelf() would not override those bindings either. Foreground/notification cleanup
        // above is therefore explicit rather than waiting for service destruction.
        stopService(new Intent(this, PlayerService.class));
    }

    @Override
    protected void attachBaseContext(final Context base) {
        super.attachBaseContext(AudioServiceLeakFix.preventLeakOf(base));
    }
    //endregion

    //region Bind
    @Override
    public IBinder onBind(final Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onBind() called with action = [" + intent.getAction() + "]");
        }

        if (BIND_PLAYER_HOLDER_ACTION.equals(intent.getAction())) {
            // Note that this binder might be reused multiple times while the service is alive, even
            // after unbind() has been called: https://stackoverflow.com/a/8794930 .
            return mBinder;

        } else if (legacyBrowser != null
                && "android.media.browse.MediaBrowserService".equals(intent.getAction())) {
            return legacyBrowser.bindBrowser(intent);
        } else if ("android.media.browse.MediaBrowserService".equals(intent.getAction())
                || MediaLibraryService.SERVICE_INTERFACE.equals(intent.getAction())
                || "androidx.media3.session.MediaSessionService".equals(intent.getAction())) {
            // MediaBrowserService also uses its own binder, so for actions related to the media
            // browser service, pass the onBind to the superclass.
            return super.onBind(intent);

        } else {
            // This is an unknown request, avoid returning any binder to not leak objects.
            return null;
        }
    }

    public static class LocalBinder extends Binder {
        private final WeakReference<PlayerService> playerService;

        LocalBinder(final PlayerService playerService) {
            this.playerService = new WeakReference<>(playerService);
        }

        public PlayerService getService() {
            if (Binder.getCallingUid() != Process.myUid()) {
                throw new SecurityException("Player service binder is internal to this app");
            }
            return playerService.get();
        }
    }

    public static Intent authenticateInternalIntent(@NonNull final Context context,
                                                     @NonNull final Intent intent) {
        return intent.putExtra(INTERNAL_START_TOKEN_EXTRA, getInternalStartToken(context));
    }

    private boolean isInternalIntent(@NonNull final Intent intent) {
        try {
            return Objects.equals(getInternalStartToken(this),
                    intent.getStringExtra(INTERNAL_START_TOKEN_EXTRA));
        } catch (final BadParcelableException | ClassCastException exception) {
            Log.w(TAG, "Ignoring malformed service-start authentication", exception);
            return false;
        }
    }

    private static String getInternalStartToken(@NonNull final Context context) {
        final SharedPreferences preferences = context.getSharedPreferences(
                INTERNAL_START_TOKEN_PREFERENCES, Context.MODE_PRIVATE);
        final String existingToken = preferences.getString(INTERNAL_START_TOKEN_KEY, null);
        if (existingToken != null) {
            return existingToken;
        }

        final String newToken = UUID.randomUUID().toString();
        preferences.edit().putString(INTERNAL_START_TOKEN_KEY, newToken).apply();
        return newToken;
    }

    /**
     * Gets the active player, if the service currently has one.
     *
     * @return the current active player instance. May be null, since the player service can outlive
     * the player e.g. to respond to Android Auto media browser queries.
     */
    @Nullable
    public Player getPlayer() {
        return player;
    }

    /**
     * Sets the listener that will be called when the player is started or stopped. If a
     * {@code null} listener is passed, then the current listener will be unset. The parameter taken
     * by the {@link Consumer} can be null to indicate that the player is stopping.
     * @param listener the listener to set or unset
     */
    public void setPlayerListener(@Nullable final Consumer<Player> listener) {
        this.onPlayerStartedOrStopped = listener;
        if (listener != null) {
            // if there is no player, then `null` will be sent here, to ensure the state is synced
            listener.accept(player);
        }
    }
    //endregion

    @Override
    public MediaLibrarySession onGetSession(final MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    public SessionCommands sessionCommands() {
        final SessionCommands.Builder commands = MediaSession.ConnectionResult
                .DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon();
        for (final String action : List.of(NotificationConstants.ACTION_CLOSE,
                NotificationConstants.ACTION_PLAY_PAUSE, NotificationConstants.ACTION_PLAY_NEXT,
                NotificationConstants.ACTION_PLAY_PREVIOUS,
                NotificationConstants.ACTION_FAST_REWIND,
                NotificationConstants.ACTION_FAST_FORWARD, NotificationConstants.ACTION_REPEAT,
                NotificationConstants.ACTION_SHUFFLE)) {
            commands.add(new SessionCommand(action, Bundle.EMPTY));
        }
        return commands.build();
    }

    public boolean dispatchSessionAction(final String action) {
        if (!sessionCommands().contains(new SessionCommand(action, Bundle.EMPTY))) {
            return false;
        }
        if (NotificationConstants.ACTION_CLOSE.equals(action)) {
            destroyPlayerAndStopService();
            return true;
        }
        if (player == null) {
            return false;
        }
        player.dispatchSessionAction(action);
        refreshSession();
        return true;
    }

    public void refreshSession() {
        if (sessionPlayer == null) {
            return;
        }
        sessionPlayer.refresh();
        if (player == null) {
            return;
        }
        mediaSession.setSessionActivity(notificationProvider.contentIntent(player));
        final List<CommandButton> buttons = new ArrayList<>();
        for (int slot = 0; slot < NotificationConstants.SLOT_PREF_KEYS.length; slot++) {
            final int action = player.getPrefs().getInt(
                    getString(NotificationConstants.SLOT_PREF_KEYS[slot]),
                    NotificationConstants.SLOT_DEFAULTS[slot]);
            final NotificationActionData data = NotificationActionData
                    .fromNotificationActionEnum(player, action);
            if (data != null) {
                buttons.add(new CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                        .setDisplayName(data.name()).setIconResId(data.icon())
                        .setSlots(slot == 0 ? CommandButton.SLOT_BACK_SECONDARY
                                : CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                        .setSessionCommand(new SessionCommand(data.action(), Bundle.EMPTY))
                        .build());
            }
        }
        if (!buttons.equals(customButtons)) {
            customButtons = buttons;
            mediaSession.setMediaButtonPreferences(buttons);
        }
    }

    public void detachSessionEngine() {
        if (sessionPlayer != null) {
            sessionPlayer.detachEngine();
        }
    }

    public void refreshSessionPosition() {
        sessionPlayer.refreshPosition();
    }

    public void showPlayerNotification(final boolean requireForeground) {
        notificationVisible = true;
        updatePlayerNotification(requireForeground);
    }

    public void hidePlayerNotification() {
        notificationVisible = false;
        foreground = false;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        NotificationManagerCompat.from(this).cancel(NotificationUtil.NOTIFICATION_ID);
    }

    @Override
    public ListenableFuture<Void> onUpdateNotificationAsync(final MediaSession session,
                                                           final boolean requireForeground) {
        updatePlayerNotification(requireForeground);
        return Futures.immediateVoidFuture();
    }

    private void updatePlayerNotification(final boolean requireForeground) {
        if (!notificationVisible || notificationProvider == null || mediaSession == null) {
            return;
        }
        final var notification = notificationProvider.buildNotification(mediaSession,
                null, ImmutableList.copyOf(customButtons));
        if (requireForeground || foreground
                || player != null && (player.isPlaying() || player.getCasting().isActive())) {
            foreground = true;
            ServiceCompat.startForeground(this, NotificationUtil.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NotificationUtil.NOTIFICATION_ID,
                    notification);
        }
    }

    private void finishStaleBrowserStart(final Intent intent, final int startId) {
        if (intent.getBooleanExtra(SHOULD_START_FOREGROUND_EXTRA, false) && !foreground) {
            // Even an obsolete startForegroundService request has an Android deadline.
            // Satisfy it without creating an engine, cancelling a newer preparation or
            // changing the current player's notification policy.
            final boolean wasVisible = notificationVisible;
            showPlayerNotification(true);
            if (wasVisible) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
                foreground = false;
            } else {
                hidePlayerNotification();
            }
        }
        if (player == null && (browserPreparation == null || browserPreparation.isDone())) {
            stopSelf(startId);
        }
    }

    private ListenableFuture<?> prepareBrowserMediaId(final String mediaId) {
        cancelBrowserPreparation();
        final String request = UUID.randomUUID().toString();
        final SettableFuture<Void> future = SettableFuture.create();
        browserRequest = request;
        browserPreparation = future;
        browserResolution = io.reactivex.rxjava3.core.Single.defer(() ->
                mediaBrowserPlaybackPreparer.resolve(mediaId))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(queue -> {
                    if (!future.isDone()) {
                        final Intent start = NavigationHelper.getPlayerIntent(this,
                                PlayerService.class, queue, PlayerIntentType.AllOthers)
                                .putExtra(Player.PLAYER_TYPE, PlayerType.AUDIO)
                                .putExtra(BROWSER_REQUEST, request);
                        try {
                            startForegroundService(start);
                        } catch (final RuntimeException error) {
                            future.setException(error);
                        }
                    }
                }, error -> {
                    mediaSession.sendError(new SessionError(SessionError.ERROR_UNKNOWN,
                            getString(R.string.content_not_supported), Bundle.EMPTY));
                    future.setException(error);
                });
        future.addListener(() -> mainHandler.post(() -> {
            if (request.equals(browserRequest)) {
                // A failed/cancelled resolution may be the only owner of a started service.
                // Successful starts already retire this request before completing the future.
                cancelBrowserPreparation();
                if (player == null) {
                    stopSelf();
                }
            }
        }), Runnable::run);
        return future;
    }

    private void cancelBrowserPreparation() {
        if (browserResolution != null) {
            browserResolution.dispose();
            browserResolution = null;
        }
        if (browserPreparation != null) {
            browserPreparation.cancel(false);
            browserPreparation = null;
        }
        browserRequest = null;
    }
}
