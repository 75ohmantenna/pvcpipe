package org.schabi.newpipe.player.history;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;

/** Android preferences and the existing Room-backed stream-history implementation. */
final class AndroidPlaybackHistoryEnvironment implements PlaybackHistory.Environment {
    private final HistoryRecordManager records;
    private final SharedPreferences preferences;
    private final String watchHistoryKey;

    AndroidPlaybackHistoryEnvironment(final Context context) {
        records = new HistoryRecordManager(context);
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        watchHistoryKey = context.getString(R.string.enable_watch_history_key);
    }

    @Override
    public boolean saveEnabled() {
        return preferences.getBoolean(watchHistoryKey, true);
    }

    @Override
    public Maybe<Long> viewed(final StreamInfo info) {
        return records.onViewed(info);
    }

    @Override
    public Completable save(final StreamInfo info, final long position) {
        return records.saveStreamState(info, position);
    }

    @Override
    public Maybe<StreamStateEntity> load(final PlayQueueItem item) {
        return records.loadStreamState(item);
    }

    @Override
    public void logError(final Throwable error) {
        if (MainActivity.DEBUG) {
            Log.w("PlaybackHistory", "Playback history operation failed", error);
        }
    }
}
