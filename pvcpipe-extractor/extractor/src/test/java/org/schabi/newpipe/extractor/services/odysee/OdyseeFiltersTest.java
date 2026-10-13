package org.schabi.newpipe.extractor.services.odysee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.schabi.newpipe.extractor.ServiceList.Odysee;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterContainer;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.odysee.search.filter.OdyseeFilters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tag("offline")
class OdyseeFiltersTest {

    private static final String SEARCH = "https://odysee.com/$/search?q=";

    private final SearchQueryHandlerFactory factory = Odysee.getSearchQHFactory();

    @Test
    void searchWithoutFiltersLooksForVideosAndChannels() throws Exception {
        assertEquals(SEARCH + "a+b%26c&claimType=file,channel",
                factory.fromQuery("a b&c").getUrl());
        assertEquals("a b&c", factory.fromQuery("a b&c").getSearchString());
    }

    @Test
    void eachContentFilterSelectsItsKindOfResult() throws Exception {
        assertEquals(SEARCH + "x&claimType=file,channel", url(OdyseeFilters.ID_CF_MAIN_ALL));
        assertEquals(SEARCH + "x&claimType=file&mediaType=video",
                url(OdyseeFilters.ID_CF_MAIN_VIDEOS));
        assertEquals(SEARCH + "x&claimType=file&mediaType=audio",
                url(OdyseeFilters.ID_CF_MAIN_TRACKS));
        assertEquals(SEARCH + "x&claimType=channel", url(OdyseeFilters.ID_CF_MAIN_CHANNELS));
    }

    @Test
    void eachSortFilterAddsItsParameter() throws Exception {
        assertEquals(SEARCH + "x&claimType=file&mediaType=video",
                url(OdyseeFilters.ID_CF_MAIN_VIDEOS, OdyseeFilters.ID_SF_SORT_BY_RELEVANCE,
                        OdyseeFilters.ID_SF_DATE_ALL, OdyseeFilters.ID_SF_DURATION_ALL));
        assertEquals(SEARCH + "x&claimType=file&mediaType=video&sort_by=%5Erelease_time",
                url(OdyseeFilters.ID_CF_MAIN_VIDEOS, OdyseeFilters.ID_SF_SORT_BY_OLDEST));
        assertEquals(SEARCH + "x&claimType=file,channel&time_filter=today",
                url(OdyseeFilters.ID_CF_MAIN_ALL, OdyseeFilters.ID_SF_DATE_TODAY));
        assertEquals(SEARCH + "x&claimType=file,channel&time_filter=thismonth",
                url(OdyseeFilters.ID_CF_MAIN_ALL, OdyseeFilters.ID_SF_DATE_THIS_MONTH));
        assertEquals(SEARCH + "x&claimType=file,channel&time_filter=thisyear",
                url(OdyseeFilters.ID_CF_MAIN_ALL, OdyseeFilters.ID_SF_DATE_THIS_YEAR));
        assertEquals(SEARCH + "x&claimType=file&mediaType=audio&max_duration=240",
                url(OdyseeFilters.ID_CF_MAIN_TRACKS, OdyseeFilters.ID_SF_DURATION_SHORT));
        assertEquals(SEARCH + "x&claimType=file&mediaType=audio"
                        + "&min_duration=240&max_duration=1200",
                url(OdyseeFilters.ID_CF_MAIN_TRACKS, OdyseeFilters.ID_SF_DURATION_MEDIUM));
    }

    @Test
    void channelsHaveNoSortFilters() throws Exception {
        assertNull(factory.getContentFilterSortFilterVariant(OdyseeFilters.ID_CF_MAIN_CHANNELS));
        assertEquals(SEARCH + "x&claimType=channel",
                url(OdyseeFilters.ID_CF_MAIN_CHANNELS, OdyseeFilters.ID_SF_SORT_BY_NEWEST));
        for (final int id : new int[]{OdyseeFilters.ID_CF_MAIN_ALL,
                OdyseeFilters.ID_CF_MAIN_VIDEOS, OdyseeFilters.ID_CF_MAIN_TRACKS}) {
            final FilterContainer sortFilters = factory.getContentFilterSortFilterVariant(id);
            assertNotNull(sortFilters);
            assertEquals(3, sortFilters.getFilterGroups().size());
        }
    }

    @Test
    void everyFilterHasANameAndItsOwnId() {
        final FilterContainer content = factory.getAvailableContentFilter();
        assertEquals(1, content.getFilterGroups().size());
        final FilterGroup contentGroup = content.getFilterGroups().get(0);
        assertEquals(OdyseeFilters.ID_CF_MAIN_ALL, contentGroup.getDefaultSelectedFilterId());

        final List<FilterItem> items = new ArrayList<>(contentGroup.getFilterItems());
        for (final FilterGroup group : contentGroup.getAllSortFilters().getFilterGroups()) {
            assertNotNull(group.getNameId());
            assertNotNull(factory.getFilterItem(group.getDefaultSelectedFilterId()));
            items.addAll(group.getFilterItems());
        }
        assertEquals(4 + 3 + 5 + 4, items.size());
        final Set<Integer> ids = new HashSet<>();
        for (final FilterItem item : items) {
            assertNotNull(item.getNameId());
            assertSame(item, factory.getFilterItem(item.getIdentifier()));
            ids.add(item.getIdentifier());
        }
        assertEquals(items.size(), ids.size());
    }

    private String url(final int contentFilter, final int... sortFilters) throws Exception {
        final List<FilterItem> sort = new ArrayList<>();
        for (final int id : sortFilters) {
            sort.add(factory.getFilterItem(id));
        }
        return factory.fromQuery("x", List.of(factory.getFilterItem(contentFilter)), sort)
                .getUrl();
    }
}
