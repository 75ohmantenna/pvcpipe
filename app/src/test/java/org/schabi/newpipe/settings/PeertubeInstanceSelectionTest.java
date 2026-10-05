package org.schabi.newpipe.settings;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import androidx.recyclerview.widget.ListAdapter;

import org.junit.Test;
import org.schabi.newpipe.extractor.services.peertube.PeertubeInstance;

import java.util.List;

public class PeertubeInstanceSelectionTest {
    private static final PeertubeInstance FIRST =
            new PeertubeInstance("https://first.example", "First");
    private static final PeertubeInstance SECOND =
            new PeertubeInstance("https://second.example", "Second");
    private static final PeertubeInstance THIRD =
            new PeertubeInstance("https://third.example", "Third");

    @SuppressWarnings("unchecked")
    private final ListAdapter<PeertubeInstance, ?> adapter = mock(ListAdapter.class);

    @Test
    public void updatesOnlyOldAndNewSelections() {
        when(adapter.getCurrentList()).thenReturn(List.of(FIRST, SECOND, THIRD));

        PeertubeInstanceListFragment.notifySelectionChanged(
                adapter, FIRST.getUrl(), SECOND.getUrl());

        assertUpdates(0, 1);
    }

    @Test
    public void usesCurrentPositionsAfterReordering() {
        final String previousUrl = FIRST.getUrl();
        final String requestedUrl = SECOND.getUrl();
        when(adapter.getCurrentList()).thenReturn(List.of(SECOND, THIRD, FIRST));

        PeertubeInstanceListFragment.notifySelectionChanged(adapter, previousUrl, requestedUrl);

        assertUpdates(0, 2);
    }

    @Test
    public void handlesPreviouslySelectedInstanceMissingFromCurrentList() {
        when(adapter.getCurrentList()).thenReturn(List.of(THIRD, SECOND));

        PeertubeInstanceListFragment.notifySelectionChanged(
                adapter, FIRST.getUrl(), SECOND.getUrl());

        assertUpdates(1);
    }

    @Test
    public void handlesNewSelectionMissingFromCurrentList() {
        when(adapter.getCurrentList()).thenReturn(List.of(FIRST, THIRD));

        PeertubeInstanceListFragment.notifySelectionChanged(
                adapter, FIRST.getUrl(), SECOND.getUrl());

        assertUpdates(0);
    }

    @Test
    public void ignoresUnrelatedInstances() {
        when(adapter.getCurrentList()).thenReturn(List.of(THIRD));

        PeertubeInstanceListFragment.notifySelectionChanged(
                adapter, FIRST.getUrl(), SECOND.getUrl());

        assertUpdates();
    }

    @Test
    public void handlesEmptyCurrentList() {
        when(adapter.getCurrentList()).thenReturn(List.of());

        PeertubeInstanceListFragment.notifySelectionChanged(
                adapter, FIRST.getUrl(), SECOND.getUrl());

        assertUpdates();
    }

    @Test
    public void updatesEveryRowSharingChangedUrls() {
        when(adapter.getCurrentList()).thenReturn(List.of(
                FIRST, new PeertubeInstance(FIRST.getUrl(), "First copy"),
                SECOND, new PeertubeInstance(SECOND.getUrl(), "Second copy"), THIRD));

        PeertubeInstanceListFragment.notifySelectionChanged(
                adapter, FIRST.getUrl(), SECOND.getUrl());

        assertUpdates(0, 1, 2, 3);
    }

    @Test
    public void ignoresMalformedRowsWithMissingUrls() {
        when(adapter.getCurrentList()).thenReturn(List.of(
                FIRST, new PeertubeInstance(null, "Missing URL"), SECOND));

        PeertubeInstanceListFragment.notifySelectionChanged(
                adapter, FIRST.getUrl(), SECOND.getUrl());

        assertUpdates(0, 2);
    }

    @Test
    public void doesNotRefreshAnUnchangedSelection() {
        PeertubeInstanceListFragment.notifySelectionChanged(
                adapter, FIRST.getUrl(), FIRST.getUrl());

        verifyNoInteractions(adapter);
    }

    private void assertUpdates(final int... positions) {
        verify(adapter).getCurrentList();
        for (final int position : positions) {
            verify(adapter).notifyItemChanged(position);
        }
        verifyNoMoreInteractions(adapter);
    }
}
