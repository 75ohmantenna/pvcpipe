package org.schabi.newpipe.fragments.list.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.R;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 26)
public class SuggestionActionDescriptionTest {
    @Test
    public void recycledInsertionTargetKeepsCurrentQueryAndSeparateAction() {
        final var context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.DarkTheme);
        final var adapter = new SuggestionListAdapter();
        final var listener = mock(SuggestionListAdapter.OnSuggestionItemSelected.class);
        adapter.setListener(listener);
        final var holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
        for (final SuggestionItem item : List.of(new SuggestionItem(true, "history query"),
                new SuggestionItem(false, "remote query"))) {
            // Clearing makes both replacements synchronous while exercising the same holder.
            adapter.submitList(null);
            adapter.submitList(List.of(item));
            adapter.onBindViewHolder(holder, 0);
            final var binding = holder.getBinding();
            assertEquals("Insert search suggestion for editing: " + item.query,
                    binding.suggestionInsert.getContentDescription());
            assertEquals(item.query, binding.itemSuggestionQuery.getText().toString());
            assertFalse(binding.suggestionInsert.getChildAt(0).isFocusable());
            binding.suggestionInsert.performClick();
            verify(listener).onSuggestionItemInserted(item);
            binding.suggestionSearch.performClick();
            verify(listener).onSuggestionItemSelected(item);
            binding.suggestionSearch.performLongClick();
            verify(listener).onSuggestionItemLongClick(item);
        }
        verifyNoMoreInteractions(listener);
    }
}
