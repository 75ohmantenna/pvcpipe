package org.schabi.newpipe.fragments.list.search;

import android.os.Bundle;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SearchFragmentSavedStateTest {
    private static final String SEARCH_SUFFIX =
            "org.schabi.newpipe.fragments.list.search.SearchFragment$$StateSaver";
    private static final String BASE_SUFFIX = "org.schabi.newpipe.BaseFragment$$StateSaver";

    @Test
    public void parcelledSearchStateRestoresOwnAndInheritedFields() {
        final ProbeFragment source = new ProbeFragment();
        source.useAsFrontPage(true);
        source.markLoading();
        source.serviceId = 4;
        source.searchString = "restored query";
        source.lastSearchedString = "previous query";
        source.isCorrectedSearch = true;
        source.wasSearchFocused = true;
        source.userSelectedContentFilterList = new ArrayList<>(Arrays.asList(2, 7));
        source.userSelectedSortFilterList = new ArrayList<>(Arrays.asList(3));

        final Bundle saved = new Bundle();
        source.saveFragmentState(saved);
        final Bundle restored = parcelRoundTrip(saved);
        final ProbeFragment target = new ProbeFragment();
        target.restoreFragmentState(restored);

        assertTrue(target.frontPage());
        assertTrue(target.loading());
        assertEquals(4, target.serviceId);
        assertEquals("restored query", target.searchString);
        assertEquals("previous query", target.lastSearchedString);
        assertTrue(target.isCorrectedSearch);
        assertTrue(target.wasSearchFocused);
        assertEquals(Arrays.asList(2, 7), target.userSelectedContentFilterList);
        assertEquals(Arrays.asList(3), target.userSelectedSortFilterList);
    }

    @Test
    public void restoresLegacyGeneratedKeysWithoutDiscardingMissingDefaults() {
        final Bundle legacy = new Bundle();
        legacy.putBoolean("useAsFrontPage" + BASE_SUFFIX, true);
        legacy.putString("searchString" + SEARCH_SUFFIX, "before upgrade");
        legacy.putIntegerArrayList("userSelectedContentFilterList" + SEARCH_SUFFIX,
                new ArrayList<>(Arrays.asList(5)));

        final ProbeFragment target = new ProbeFragment();
        target.restoreFragmentState(parcelRoundTrip(legacy));

        assertTrue(target.frontPage());
        assertEquals("before upgrade", target.searchString);
        assertEquals(Arrays.asList(5), target.userSelectedContentFilterList);
        assertFalse(target.loading());
        assertNull(target.userSelectedSortFilterList);
    }

    private static Bundle parcelRoundTrip(final Bundle state) {
        final Parcel parcel = Parcel.obtain();
        try {
            state.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            final Bundle restored = parcel.readBundle(SearchFragment.class.getClassLoader());
            if (restored == null) {
                throw new AssertionError("Saved state disappeared while unparcelling");
            }
            return restored;
        } finally {
            parcel.recycle();
        }
    }

    private static final class ProbeFragment extends SearchFragment {
        boolean frontPage() {
            return useAsFrontPage;
        }

        void markLoading() {
            wasLoading.set(true);
        }

        boolean loading() {
            return wasLoading.get();
        }
    }
}
