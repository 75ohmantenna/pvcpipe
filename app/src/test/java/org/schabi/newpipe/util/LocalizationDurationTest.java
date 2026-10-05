package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;

import org.junit.Test;
import org.schabi.newpipe.R;

public class LocalizationDurationTest {
    @Test
    public void selectsLargestWholeUnitAtBoundaries() {
        assertDuration(0, R.plurals.seconds, 0);
        assertDuration(59, R.plurals.seconds, 59);
        assertDuration(60, R.plurals.minutes, 1);
        assertDuration(119, R.plurals.minutes, 1);
        assertDuration(3599, R.plurals.minutes, 59);
        assertDuration(3600, R.plurals.hours, 1);
        assertDuration(86399, R.plurals.hours, 23);
        assertDuration(86400, R.plurals.days, 1);
        assertDuration(172799, R.plurals.days, 1);
        assertDuration(Integer.MAX_VALUE, R.plurals.days, 24855);
    }

    @Test
    public void rejectsNegativeDurationsBeforeAccessingResources() {
        final Context context = mock(Context.class);

        assertThrows(IllegalArgumentException.class,
                () -> Localization.localizeDuration(context, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Localization.localizeDuration(context, Integer.MIN_VALUE));
        verifyNoInteractions(context);
    }

    private static void assertDuration(final int seconds, final int plural, final int quantity) {
        final Context context = mock(Context.class);
        final Resources resources = mock(Resources.class);
        when(context.getResources()).thenReturn(resources);
        when(resources.getQuantityString(plural, quantity, quantity)).thenReturn("localized");

        assertEquals("localized", Localization.localizeDuration(context, seconds));
        verify(resources).getQuantityString(plural, quantity, quantity);
    }
}
