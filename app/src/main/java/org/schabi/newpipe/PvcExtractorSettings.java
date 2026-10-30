package org.schabi.newpipe;

import android.content.Context;
import android.content.SharedPreferences;

import org.schabi.newpipe.extractor.services.kick.settings.KickSettings;
import org.schabi.newpipe.extractor.services.odysee.OdyseeAuth;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.services.rumble.settings.RumbleSettings;
import org.schabi.newpipe.extractor.services.youtube.settings.YoutubeSettings;

import java.util.Objects;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

/**
 * PVCPipeExtractor Settings that will be pushed into the extractor library.
 */
public class PvcExtractorSettings {
    private final Context context;
    private boolean odyseeTokenStoreSet;

    public PvcExtractorSettings(final Context context) {
        this.context = context;
    }

    public void initExtractorConfig() {
        final SharedPreferences sharedCfg = PreferenceManager.getDefaultSharedPreferences(context);
        initExtractorPremiumSetting(sharedCfg);
        initOdyseeCounts(sharedCfg);
    }

    /**
     * Odysee gives view counts and follower counts only to a visitor it has registered. The
     * extractor registers an anonymous one when the user wants the counts, and its token is
     * kept in the preferences so that the next start of the app uses the same one. Switching
     * the counts off also forgets the token.
     *
     * @param sharedCfg the preferences that hold the user's choice and the token
     */
    private void initOdyseeCounts(final SharedPreferences sharedCfg) {
        if (!odyseeTokenStoreSet) {
            // Once: this runs again on every tap in the settings.
            OdyseeAuth.setTokenStore(new OdyseeTokenStore(sharedCfg,
                    context.getString(R.string.pvc_odysee_visitor_token_key)));
            odyseeTokenStoreSet = true;
        }
        final boolean showCounts = sharedCfg.getBoolean(context.getString(
                R.string.pvc_settings_odysee_counts_key), true);
        OdyseeSettings.getInstance().setting(showCounts, OdyseeSettings.SHOW_COUNTS);
        if (!showCounts) {
            OdyseeAuth.forget();
        }
    }

    /** Keeps Odysee's visitor token in the app's preferences. */
    @VisibleForTesting
    static final class OdyseeTokenStore implements OdyseeAuth.TokenStore {
        private final SharedPreferences preferences;
        private final String key;

        OdyseeTokenStore(final SharedPreferences preferences, final String key) {
            this.preferences = preferences;
            this.key = key;
        }

        @Nullable
        @Override
        public String load() {
            return preferences.getString(key, null);
        }

        @Override
        public void save(@Nullable final String token) {
            if (token != null) {
                preferences.edit().putString(key, token).apply();
            } else if (preferences.contains(key)) {
                preferences.edit().remove(key).apply();
            }
        }
    }

    private void initExtractorPremiumSetting(final SharedPreferences sharedCfg) {
        final boolean doHidePaidContent = Objects.requireNonNull(sharedCfg)
                .getBoolean(context.getResources().getString(
                        R.string.pvc_settings_hide_paid_content_key), false);

        YoutubeSettings.getInstance()
                .setting(doHidePaidContent, YoutubeSettings.HIDE_MEMBERS_ONLY_STREAMS);
        RumbleSettings.getInstance()
                .setting(doHidePaidContent, RumbleSettings.HIDE_PREMIUM_STREAMS);
        OdyseeSettings.getInstance()
                .setting(doHidePaidContent, OdyseeSettings.HIDE_PAID_STREAMS);
        KickSettings.getInstance().setting(doHidePaidContent, KickSettings.HIDE_PAID_STREAMS);
    }
}
