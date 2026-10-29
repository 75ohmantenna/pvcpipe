/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.schabi.newpipe.R;

import java.lang.reflect.Method;

/** Actual update method and AndroidX preferences; label resources are controlled doubles. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class SeekDurationOptionsTest {
    private static final String SEEK_KEY = "seek_duration";
    private static final String INEXACT_KEY = "use_inexact_seek_key";
    private static final String[] VALUES = {"5000", "10000", "15000", "20000", "25000", "30000"};
    private static final String[] LOCALIZED = {"localized 5", "localized 10", "localized 15",
            "localized 20", "localized 25", "localized 30"};
    private static final String[] FALLBACK = {"static 5", "static 10", "static 15",
            "static 20", "static 25", "static 30"};

    private VideoAudioSettingsFragment fragment;
    private Resources resources;
    private SharedPreferences preferences;
    private ListPreference durations;

    @Before
    public void setup() {
        final Context context = RuntimeEnvironment.getApplication();
        final PreferenceManager manager = new PreferenceManager(context);
        manager.setSharedPreferencesName("seek-options-test");
        preferences = manager.getSharedPreferences();
        assertTrue(preferences.edit().clear().commit());
        durations = new ListPreference(context, null, 0, 0);
        durations.setKey(SEEK_KEY);
        final var screen = manager.createPreferenceScreen(context);
        screen.addPreference(durations);
        durations.setValue("10000");
        assertEquals("10000", preferences.getString(SEEK_KEY, null));

        resources = mock(Resources.class);
        when(resources.getStringArray(R.array.seek_duration_value)).thenReturn(VALUES.clone());
        when(resources.getStringArray(R.array.seek_duration_description))
                .thenReturn(FALLBACK.clone());
        when(resources.getString(R.string.use_inexact_seek_key)).thenReturn(INEXACT_KEY);
        when(resources.getString(R.string.seek_duration_key)).thenReturn(SEEK_KEY);
        when(resources.getQuantityString(eq(R.plurals.seconds), anyInt()))
                .thenAnswer(call -> "localized " + call.getArgument(1));
        when(resources.getString(eq(R.string.new_seek_duration_toast), anyInt()))
                .thenAnswer(call -> "selected " + call.getArgument(1));

        fragment = spy(new VideoAudioSettingsFragment());
        doReturn(resources).when(fragment).getResources();
        doReturn(context).when(fragment).getContext();
        doReturn(manager).when(fragment).getPreferenceManager();
        doReturn(durations).when(fragment).findPreference(SEEK_KEY);
        ShadowToast.reset();
    }

    private void update() throws Exception {
        final Method method = VideoAudioSettingsFragment.class
                .getDeclaredMethod("updateSeekOptions");
        method.setAccessible(true);
        method.invoke(fragment);
    }

    private void failLabel(final int seconds) {
        doThrow(new Resources.NotFoundException("controlled plural failure"))
                .when(resources).getQuantityString(R.plurals.seconds, seconds);
    }

    private void failAllLabels() {
        for (final String value : VALUES) {
            failLabel(Integer.parseInt(value) / 1000);
        }
    }

    private void assertSelection(final String value, final String label) {
        assertEquals(value, durations.getValue());
        assertEquals(value, preferences.getString(SEEK_KEY, null));
        assertEquals(label, durations.getEntry());
    }

    private void singleFailure(final int seconds, final int index) throws Exception {
        failLabel(seconds);
        durations.setValue(VALUES[index]);
        update();
        final String[] expected = LOCALIZED.clone();
        expected[index] = FALLBACK[index];
        assertArrayEquals(VALUES, durations.getEntryValues());
        assertArrayEquals(expected, durations.getEntries());
        assertSelection(VALUES[index], FALLBACK[index]);
    }

    @Test
    public void normalLabelsPreserveTheSavedSelection() throws Exception {
        durations.setValue("15000");
        update();
        assertArrayEquals(VALUES, durations.getEntryValues());
        assertArrayEquals(LOCALIZED, durations.getEntries());
        assertSelection("15000", "localized 15");
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void firstLabelFailureKeepsItsValueAndFallbackPaired() throws Exception {
        singleFailure(5, 0);
    }

    @Test
    public void middleLabelFailureKeepsItsValueAndFallbackPaired() throws Exception {
        singleFailure(15, 2);
    }

    @Test
    public void lastLabelFailureKeepsItsValueAndFallbackPaired() throws Exception {
        singleFailure(30, 5);
    }

    @Test
    public void allLabelFailuresKeepEveryValueSelectable() throws Exception {
        failAllLabels();
        update();
        assertArrayEquals(VALUES, durations.getEntryValues());
        assertArrayEquals(FALLBACK, durations.getEntries());
        for (int i = 0; i < VALUES.length; i++) {
            durations.setValue(VALUES[i]);
            assertSelection(VALUES[i], FALLBACK[i]);
        }
    }

    @Test
    public void inexactModeKeepsLocalizedLabelsAndAValidSelection() throws Exception {
        assertTrue(preferences.edit().putBoolean(INEXACT_KEY, true).commit());
        durations.setValue("20000");
        update();
        assertArrayEquals(new String[]{"10000", "20000", "30000"}, durations.getEntryValues());
        assertArrayEquals(new String[]{"localized 10", "localized 20", "localized 30"},
                durations.getEntries());
        assertSelection("20000", "localized 20");
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void inexactFallbackUsesOriginalRatherThanFilteredArrayPositions() throws Exception {
        failAllLabels();
        assertTrue(preferences.edit().putBoolean(INEXACT_KEY, true).commit());
        update();
        assertArrayEquals(new String[]{"10000", "20000", "30000"}, durations.getEntryValues());
        assertArrayEquals(new String[]{"static 10", "static 20", "static 30"},
                durations.getEntries());
        assertSelection("10000", "static 10");
    }

    @Test
    public void filteredDurationsNeverRequestLabels() throws Exception {
        failLabel(5);
        failLabel(15);
        failLabel(25);
        assertTrue(preferences.edit().putBoolean(INEXACT_KEY, true).commit());
        update();
        assertArrayEquals(new String[]{"localized 10", "localized 20", "localized 30"},
                durations.getEntries());
        verify(resources, never()).getQuantityString(R.plurals.seconds, 5);
        verify(resources, never()).getQuantityString(R.plurals.seconds, 15);
        verify(resources, never()).getQuantityString(R.plurals.seconds, 25);
    }

    @Test
    public void inexactModePersistsTheReplacementEvenWhenItsLabelNeedsFallback() throws Exception {
        failLabel(20);
        durations.setValue("15000");
        assertTrue(preferences.edit().putBoolean(INEXACT_KEY, true).commit());
        update();
        assertArrayEquals(new String[]{"10000", "20000", "30000"}, durations.getEntryValues());
        assertArrayEquals(new String[]{"localized 10", "static 20", "localized 30"},
                durations.getEntries());
        assertSelection("20000", "static 20");
        assertEquals("selected 20", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void togglingModesRebuildsPairsAndRetainsTheAdjustedSelection() throws Exception {
        failAllLabels();
        durations.setValue("25000");
        update();
        assertArrayEquals(FALLBACK, durations.getEntries());
        assertSelection("25000", "static 25");
        assertTrue(preferences.edit().putBoolean(INEXACT_KEY, true).commit());
        update();
        assertSelection("30000", "static 30");
        assertEquals("selected 30", ShadowToast.getTextOfLatestToast());
        assertTrue(preferences.edit().putBoolean(INEXACT_KEY, false).commit());
        ShadowToast.reset();
        update();
        assertArrayEquals(VALUES, durations.getEntryValues());
        assertArrayEquals(FALLBACK, durations.getEntries());
        assertSelection("30000", "static 30");
        assertNull(ShadowToast.getTextOfLatestToast());
    }
}
