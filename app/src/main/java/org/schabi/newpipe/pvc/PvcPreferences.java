package org.schabi.newpipe.pvc;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * Reads PVCPipe's own settings.
 */
public final class PvcPreferences {
    private PvcPreferences() {
    }

    /**
     * @param context any context
     * @return whether comment replies are shown inside the comments page instead of on a screen
     * of their own
     */
    public static boolean commentRepliesInSameWindow(@NonNull final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                context.getString(R.string.pvc_settings_comment_replies_same_window_key), false);
    }
}
