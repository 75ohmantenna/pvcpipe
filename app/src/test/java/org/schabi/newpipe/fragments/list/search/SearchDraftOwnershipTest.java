package org.schabi.newpipe.fragments.list.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentSearchBinding;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.info_list.InfoListAdapter;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.StateSaver;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 26)
public class SearchDraftOwnershipTest {
    private static final String SUFFIX =
            "org.schabi.newpipe.fragments.list.search.SearchFragment$$StateSaver";

    @Test
    public void saveRecreateBackAndPaginationPreserveOwnerAndExactDraft() throws Exception {
        for (final String draft : List.of("  B  ", "")) {
            final Probe source = new Probe();
            source.editor.setText(draft);
            final Page page = new Page("https://fixture.invalid/page-A");
            ReflectionHelpers.setField(source, "nextPage", page);
            final Bundle saved = new Bundle();
            try (var saver = mockStatic(StateSaver.class)) {
                source.onSaveInstanceState(saved);
            }
            org.mockito.Mockito.verify(source.adapter(), org.mockito.Mockito.never())
                    .clearStreamItemList();
            assertEquals("A", source.searchString);
            assertSame(page, ReflectionHelpers.getField(source, "nextPage"));
            assertEquals(draft, saved.getString("searchDraft" + SUFFIX));
            final java.util.Queue<Object> rowsAndPage = new java.util.LinkedList<>();
            source.writeTo(rowsAndPage);
            final Probe restored = new Probe();
            restored.restoreFragmentState(parcelRoundTrip(saved));
            restored.readFrom(rowsAndPage);
            assertEquals(source.adapter().getItemsList(), restored.adapter().getItemsList());
            assertSame(page, ReflectionHelpers.getField(restored, "nextPage"));
            ReflectionHelpers.callInstanceMethod(restored, "showSearchOnStart");
            assertEquals(draft, restored.editor.getText().toString());
            assertEquals("A", restored.searchString);
            restored.finishLoading();
            ReflectionHelpers.setField(restored, "suggestionsPanelVisible", true);
            assertTrue(restored.onBackPressed());
            assertEquals("A", restored.editor.getText().toString());
            assertEquals("A", restored.searchString);
            try (var extractor = mockStatic(ExtractorHelper.class)) {
                extractor.when(() -> ExtractorHelper.getMoreSearchItems(
                        -1, "A", List.of(), List.of(), page)).thenReturn(Single.never());
                restored.loadMoreItems();
                extractor.verify(() -> ExtractorHelper.getMoreSearchItems(
                        -1, "A", List.of(), List.of(), page));
            }
        }
    }

    @Test
    public void loadingResumeKeepsDraftAndExplicitSubmissionChangesOwner() {
        final Probe source = new Probe();
        source.editor.setText("");
        source.markLoading();
        final Bundle saved = new Bundle();
        try (var saver = mockStatic(StateSaver.class)) {
            source.onSaveInstanceState(saved);
        }
        final Probe restored = new Probe();
        restored.restoreFragmentState(parcelRoundTrip(saved));
        ReflectionHelpers.callInstanceMethod(restored, "showSearchOnStart");
        assertEquals("A", restored.searchString);
        restored.onResume();
        assertEquals("A", restored.searchString);
        assertEquals("", restored.editor.getText().toString());
        assertEquals(1, restored.loads);
        restored.editor.setText("B");
        ReflectionHelpers.callInstanceMethod(restored, "search",
                ReflectionHelpers.ClassParameter.from(String.class, "B"));
        assertEquals("B", restored.searchString);
        assertEquals(2, restored.loads);
    }

    @Test
    public void legacyKeyRemainsReadableWithoutInventingADifferentOwner() {
        final Bundle legacy = new Bundle();
        legacy.putString("searchString" + SUFFIX, "legacy ambiguous value");
        final Probe fragment = new Probe();
        fragment.restoreFragmentState(parcelRoundTrip(legacy));
        ReflectionHelpers.callInstanceMethod(fragment, "showSearchOnStart");
        assertEquals("legacy ambiguous value", fragment.searchString);
        assertEquals(fragment.searchString, fragment.editor.getText().toString());
    }

    private static Bundle parcelRoundTrip(final Bundle saved) {
        final android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            saved.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return parcel.readBundle(SearchFragment.class.getClassLoader());
        } finally {
            parcel.recycle();
        }
    }

    private static final class Probe extends SearchFragment {
        private final EditText editor;
        private int loads;

        Probe() {
            final var context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                    R.style.DarkTheme);
            activity = mock(AppCompatActivity.class);
            editor = new EditText(context);
            ReflectionHelpers.setField(this, "searchEditText", editor);
            ReflectionHelpers.setField(this, "searchToolbarContainer", new View(context));
            ReflectionHelpers.setField(this, "searchBinding",
                    FragmentSearchBinding.inflate(LayoutInflater.from(context)));
            ReflectionHelpers.setField(this, "historyRecordManager",
                    mock(HistoryRecordManager.class));
            ReflectionHelpers.setField(this, "suggestionDisposable", Disposable.empty());
            infoListAdapter = mock(InfoListAdapter.class);
            when(infoListAdapter.getItemsList()).thenReturn(
                    new ArrayList<>(List.of(mock(InfoItem.class))));
            searchString = "A";
            lastSearchedString = "A";
        }

        InfoListAdapter adapter() {
            return infoListAdapter;
        }

        void markLoading() {
            wasLoading.set(true);
        }

        void finishLoading() {
            isLoading.set(false);
        }

        @Override
        protected void hideKeyboardSearch() {
            // No keyboard in this controlled fragment fixture.
        }

        @Override
        public void showListFooter(final boolean show) {
            // No RecyclerView layout needed to verify extraction ownership.
        }

        @Override
        public void startLoading(final boolean forceLoad) {
            loads++;
        }
    }
}
