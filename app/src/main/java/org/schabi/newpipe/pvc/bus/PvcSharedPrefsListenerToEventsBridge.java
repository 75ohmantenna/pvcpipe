package org.schabi.newpipe.pvc.bus;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.pvc.feature.challenge.PvcCfChallengeConfig;

/**
 * Passes PVCPipe settings that are needed outside any screen on to the code that uses them.
 * <p>
 * Settings that only matter to the detail screen are followed by
 * {@link org.schabi.newpipe.fragments.detail.DetailPagerViewModel} instead.
 */
public class PvcSharedPrefsListenerToEventsBridge
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final Context context;

    public PvcSharedPrefsListenerToEventsBridge(
            final Context context) {
        this.context = context;
        initSharedPrefs();
    }

    @Override
    public void onSharedPreferenceChanged(
            final SharedPreferences prefs,
            @Nullable final String key) {
        if (hasPrefChanged(
                R.string.pvc_settings_handle_cloudflare_challenge_interactive_enable_key, key)) {
            postPrefCloudflareChallengeInteractive(prefs);
        }
    }

    private void postPrefCloudflareChallengeInteractive(final SharedPreferences prefs) {
        final boolean isInteractive = prefs.getBoolean(context.getString(
                        R.string.pvc_settings_handle_cloudflare_challenge_interactive_enable_key),
                false);
        PvcCfChallengeConfig.INSTANCE.updateFloatingVisible(isInteractive);
    }

    private void initPrefCloudflareChallengeInteractive(final SharedPreferences prefs) {
        final boolean isInteractive = prefs.getBoolean(context.getString(
                        R.string.pvc_settings_handle_cloudflare_challenge_interactive_enable_key),
                false);
        PvcCfChallengeConfig.INSTANCE.init(isInteractive);
    }

    private void initSharedPrefs() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        initPrefCloudflareChallengeInteractive(prefs);
    }

    private boolean hasPrefChanged(
            @StringRes final int resId,
            @Nullable final String key) {
        return context.getString(resId).equals(key);
    }
}
