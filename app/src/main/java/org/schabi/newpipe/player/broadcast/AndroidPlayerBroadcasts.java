package org.schabi.newpipe.player.broadcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.MainActivity;

import java.util.function.Consumer;

/**
 * Registers the player's broadcast receiver with Android and passes on what it receives.
 */
public final class AndroidPlayerBroadcasts {
    private static final String TAG = AndroidPlayerBroadcasts.class.getSimpleName();
    private static final boolean DEBUG = MainActivity.DEBUG;

    @NonNull
    private final Context context;
    @NonNull
    private final BroadcastReceiver receiver;
    @NonNull
    private final IntentFilter filter = new IntentFilter();
    private boolean registered = false;

    /**
     * Creates a receiver that dispatches player commands before notifying the observer.
     *
     * @param context    the context to register the receiver with
     * @param broadcasts runs the player command of each received action
     * @param observer   gets every broadcast after its command has run, for the player UIs
     */
    public AndroidPlayerBroadcasts(@NonNull final Context context,
                                   @NonNull final PlayerBroadcasts broadcasts,
                                   @NonNull final Consumer<Intent> observer) {
        this.context = context;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context ctx, final Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                if (DEBUG) {
                    Log.d(TAG, "onReceive() called with: intent = [" + intent + "]");
                }
                broadcasts.onBroadcast(intent.getAction());
                observer.accept(intent);
            }
        };
        PlayerBroadcasts.actions().forEach(filter::addAction);
    }

    /** Registers the receiver. Does nothing if it is registered already. */
    public void register() {
        if (registered) {
            return;
        }
        ContextCompat.registerReceiver(context, receiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
        registered = true;
    }

    /** Unregisters the receiver. Does nothing if it is not registered. */
    public void unregister() {
        if (!registered) {
            return;
        }
        registered = false;
        try {
            context.unregisterReceiver(receiver);
        } catch (final IllegalArgumentException unregisteredException) {
            Log.w(TAG, "Broadcast receiver already unregistered: "
                    + unregisteredException.getMessage());
        }
    }
}
