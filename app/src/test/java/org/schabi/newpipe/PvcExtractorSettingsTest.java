package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;

public class PvcExtractorSettingsTest {
    private static final String KEY = "token_key";

    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;
    private PvcExtractorSettings.OdyseeTokenStore store;

    @Before
    public void setUp() {
        preferences = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class, Answers.RETURNS_SELF);
        when(preferences.edit()).thenReturn(editor);
        store = new PvcExtractorSettings.OdyseeTokenStore(preferences, KEY);
    }

    @Test
    public void odyseeTokenIsReadFromThePreferences() {
        when(preferences.getString(KEY, null)).thenReturn("kept");
        assertEquals("kept", store.load());

        when(preferences.getString(KEY, null)).thenReturn(null);
        assertNull(store.load());
    }

    @Test
    public void odyseeTokenIsWrittenWithoutBlocking() {
        store.save("fresh");

        verify(editor).putString(KEY, "fresh");
        verify(editor).apply();
        verify(editor, never()).commit();
    }

    @Test
    public void forgettingTheOdyseeTokenRemovesItFromThePreferences() {
        when(preferences.contains(KEY)).thenReturn(true);

        store.save(null);

        verify(editor).remove(KEY);
        verify(editor).apply();
        verify(editor, never()).putString(anyString(), anyString());
    }

    @Test
    public void forgettingATokenThatWasNeverKeptWritesNothing() {
        when(preferences.contains(KEY)).thenReturn(false);

        store.save(null);

        verify(preferences, never()).edit();
    }
}
