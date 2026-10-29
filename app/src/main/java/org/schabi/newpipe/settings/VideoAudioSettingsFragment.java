package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.widget.Toast;

import androidx.preference.ListPreference;

import com.google.android.material.snackbar.Snackbar;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.PermissionHelper;

import java.util.LinkedList;
import java.util.List;

public class VideoAudioSettingsFragment extends BasePreferenceFragment {
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        updateSeekOptions();
        updateResolutionOptions();
        listener = (sharedPreferences, key) -> {

            // If the user chooses to minimise to the popup player on exit and the app does not
            // have display-over-other-apps permission,
            // show a snackbar to let the user give permission
            if (getString(R.string.minimize_on_exit_key).equals(key)) {
                final String newSetting = sharedPreferences.getString(key, null);
                if (newSetting != null
                        && newSetting.equals(getString(R.string.minimize_on_exit_popup_key))
                        && !Settings.canDrawOverlays(getContext())) {

                    Snackbar.make(getListView(), R.string.permission_display_over_apps,
                            Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.settings, view ->
                                    PermissionHelper.checkSystemAlertWindowPermission(getContext()))
                            .show();

                }
            } else if (getString(R.string.use_inexact_seek_key).equals(key)) {
                updateSeekOptions();
            } else if (getString(R.string.show_higher_resolutions_key).equals(key)) {
                updateResolutionOptions();
            }
        };
    }

    /**
     * Update default resolution, default popup resolution & mobile data resolution options.
     * <br />
     * Show high resolutions when "Show higher resolution" option is enabled.
     * Set default resolution to "best resolution" when "Show higher resolution" option
     * is disabled.
     */
    private void updateResolutionOptions() {
        final Resources resources = getResources();
        final boolean showHigherResolutions =  getPreferenceManager().getSharedPreferences()
                .getBoolean(resources.getString(R.string.show_higher_resolutions_key), false);

        // get sorted resolution lists
        final List<String> resolutionListDescriptions = ListHelper.getSortedResolutionList(
                resources,
                R.array.resolution_list_description,
                R.array.high_resolution_list_descriptions,
                showHigherResolutions);
        final List<String> resolutionListValues = ListHelper.getSortedResolutionList(
                resources,
                R.array.resolution_list_values,
                R.array.high_resolution_list_values,
                showHigherResolutions);
        final List<String> limitDataUsageResolutionValues = ListHelper.getSortedResolutionList(
                resources,
                R.array.limit_data_usage_values_list,
                R.array.high_resolution_limit_data_usage_values_list,
                showHigherResolutions);
        final List<String> limitDataUsageResolutionDescriptions = ListHelper
                .getSortedResolutionList(resources,
                R.array.limit_data_usage_description_list,
                R.array.high_resolution_list_descriptions,
                showHigherResolutions);

        // get resolution preferences
        final ListPreference defaultResolution = requirePreference(
                R.string.default_resolution_key);
        final ListPreference defaultPopupResolution = requirePreference(
                R.string.default_popup_resolution_key);
        final ListPreference mobileDataResolution = requirePreference(
                R.string.limit_mobile_data_usage_key);

        // update resolution preferences with new resolutions, entries & values for each
        defaultResolution.setEntries(resolutionListDescriptions.toArray(new String[0]));
        defaultResolution.setEntryValues(resolutionListValues.toArray(new String[0]));
        defaultPopupResolution.setEntries(resolutionListDescriptions.toArray(new String[0]));
        defaultPopupResolution.setEntryValues(resolutionListValues.toArray(new String[0]));
        mobileDataResolution.setEntries(
                limitDataUsageResolutionDescriptions.toArray(new String[0]));
        mobileDataResolution.setEntryValues(limitDataUsageResolutionValues.toArray(new String[0]));

        // if "Show higher resolution" option is disabled,
        // set default resolution to "best resolution"
        if (!showHigherResolutions) {
            if (ListHelper.isHighResolutionSelected(defaultResolution.getValue(),
                    R.array.high_resolution_list_values,
                    resources)) {
                defaultResolution.setValueIndex(0);
            }
            if (ListHelper.isHighResolutionSelected(defaultPopupResolution.getValue(),
                    R.array.high_resolution_list_values,
                    resources)) {
                defaultPopupResolution.setValueIndex(0);
            }
            if (ListHelper.isHighResolutionSelected(mobileDataResolution.getValue(),
                    R.array.high_resolution_limit_data_usage_values_list,
                    resources)) {
                mobileDataResolution.setValueIndex(0);
            }
        }
    }

    /**
     * Builds localized fast-forward/rewind entries and filters 5, 15 and 25 seconds in
     * inexact-seek mode. The filtering is an inherited coarse-seek workaround, not a probe
     * of the current Media3 decoder's seek accuracy.
     * Falls back to the paired static label when plural lookup fails.
     */
    private void updateSeekOptions() {
        final Resources res = getResources();
        final String[] durationsValues = res.getStringArray(R.array.seek_duration_value);
        final List<String> displayedDurationValues = new LinkedList<>();
        final List<String> displayedDescriptionValues = new LinkedList<>();
        int currentDurationValue;
        final boolean inexactSeek = getPreferenceManager().getSharedPreferences()
                .getBoolean(res.getString(R.string.use_inexact_seek_key), false);

        for (int i = 0; i < durationsValues.length; i++) {
            final String durationsValue = durationsValues[i];
            currentDurationValue =
                    Integer.parseInt(durationsValue) / (int) DateUtils.SECOND_IN_MILLIS;
            if (inexactSeek && currentDurationValue % 10 == 5) {
                continue;
            }

            displayedDurationValues.add(durationsValue);
            try {
                displayedDescriptionValues.add(String.format(
                        res.getQuantityString(R.plurals.seconds,
                                currentDurationValue),
                        currentDurationValue));
            } catch (final Resources.NotFoundException ignored) {
                // Static labels use the original array positions, before inexact-seek filtering.
                displayedDescriptionValues.add(
                        res.getStringArray(R.array.seek_duration_description)[i]);
            }
        }

        final ListPreference durations = requirePreference(R.string.seek_duration_key);
        durations.setEntryValues(displayedDurationValues.toArray(new CharSequence[0]));
        durations.setEntries(displayedDescriptionValues.toArray(new CharSequence[0]));
        final int selectedDuration = Integer.parseInt(durations.getValue());
        if (inexactSeek && selectedDuration / (int) DateUtils.SECOND_IN_MILLIS % 10 == 5) {
            final int newDuration = selectedDuration / (int) DateUtils.SECOND_IN_MILLIS + 5;
            durations.setValue(Integer.toString(newDuration * (int) DateUtils.SECOND_IN_MILLIS));

            final Toast toast = Toast
                    .makeText(getContext(),
                            getString(R.string.new_seek_duration_toast, newDuration),
                            Toast.LENGTH_LONG);
            toast.show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(listener);

    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(listener);
    }
}
