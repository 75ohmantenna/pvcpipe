package org.schabi.newpipe.filter;

import static org.schabi.newpipe.extractor.search.filter.FilterContainer.ITEM_IDENTIFIER_UNKNOWN;

import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.BaseSearchFilters;
import org.schabi.newpipe.extractor.search.filter.FilterContainer;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Search filters that belong to no service, so that the filter logic can be tested against a
 * layout that does not change when a service changes its filters.
 * <p>
 * There are two content filter groups: an exclusive one whose default item is the only one with
 * sort filters, and one with a single item that can be selected in addition. The sort filters
 * are two exclusive groups.
 * </p>
 */
final class FakeSearchFilters extends BaseSearchFilters {
    static final int ID_CF_MAIN_GRP = 0;
    static final int ID_CF_MAIN_VIDEOS = 1;
    static final int ID_CF_MAIN_CHANNELS = 2;
    static final int ID_CF_MAIN_PLAYLISTS = 3;
    static final int ID_CF_OTHER_GRP = 4;
    static final int ID_CF_OTHER_ITEM = 5;
    static final int ID_SF_SORT_BY_GRP = 6;
    static final int ID_SF_SORT_BY_RELEVANCE = 7;
    static final int ID_SF_SORT_BY_VIEWS = 8;
    static final int ID_SF_SORT_BY_DURATION = 9;
    static final int ID_SF_SORT_BY_DATE = 10;
    static final int ID_SF_KIND_GRP = 11;
    static final int ID_SF_KIND_ALL = 12;
    static final int ID_SF_KIND_LIVE = 13;
    static final int ID_SF_KIND_VOD_VIDEOS = 14;

    @Override
    protected void init() {
        addItem(ID_SF_SORT_BY_RELEVANCE, LibraryStringIds.SEARCH_FILTERS_RELEVANCE);
        addItem(ID_SF_SORT_BY_VIEWS, LibraryStringIds.SEARCH_FILTERS_VIEWS);
        addItem(ID_SF_SORT_BY_DURATION, LibraryStringIds.SEARCH_FILTERS_DURATION);
        addItem(ID_SF_SORT_BY_DATE, LibraryStringIds.SEARCH_FILTERS_DATE);
        addItem(ID_SF_KIND_ALL, LibraryStringIds.SEARCH_FILTERS_ALL);
        addItem(ID_SF_KIND_LIVE, LibraryStringIds.SEARCH_FILTERS_LIVE);
        addItem(ID_SF_KIND_VOD_VIDEOS, LibraryStringIds.SEARCH_FILTERS_VIDEOS);

        final FilterContainer allSortFilters = new FilterContainer(new FilterGroup[]{
                groupsFactory.createFilterGroup(ID_SF_SORT_BY_GRP,
                        LibraryStringIds.SEARCH_FILTERS_SORT_BY, true,
                        ID_SF_SORT_BY_RELEVANCE, null,
                        ID_SF_SORT_BY_RELEVANCE, ID_SF_SORT_BY_VIEWS,
                        ID_SF_SORT_BY_DURATION, ID_SF_SORT_BY_DATE),
                groupsFactory.createFilterGroup(ID_SF_KIND_GRP,
                        LibraryStringIds.SEARCH_FILTERS_FEATURES, true,
                        ID_SF_KIND_ALL, null,
                        ID_SF_KIND_ALL, ID_SF_KIND_LIVE, ID_SF_KIND_VOD_VIDEOS),
        });

        addItem(ID_CF_MAIN_VIDEOS, LibraryStringIds.SEARCH_FILTERS_VIDEOS);
        addItem(ID_CF_MAIN_CHANNELS, LibraryStringIds.SEARCH_FILTERS_CHANNELS);
        addItem(ID_CF_MAIN_PLAYLISTS, LibraryStringIds.SEARCH_FILTERS_PLAYLISTS);
        addContentFilterGroup(groupsFactory.createFilterGroup(ID_CF_MAIN_GRP, null, true,
                ID_CF_MAIN_VIDEOS, allSortFilters,
                ID_CF_MAIN_VIDEOS, ID_CF_MAIN_CHANNELS, ID_CF_MAIN_PLAYLISTS));

        addItem(ID_CF_OTHER_ITEM, LibraryStringIds.SEARCH_FILTERS_ALL);
        addContentFilterGroup(groupsFactory.createFilterGroup(ID_CF_OTHER_GRP, null, false,
                ITEM_IDENTIFIER_UNKNOWN, null,
                ID_CF_OTHER_ITEM));

        addContentFilterSortVariant(ID_CF_MAIN_VIDEOS, allSortFilters);
    }

    private void addItem(final int identifier, final LibraryStringIds nameId) {
        groupsFactory.addFilterItem(new FilterItem(identifier, nameId));
    }

    /** A search query handler factory that only carries the {@link FakeSearchFilters}. */
    static final class QueryHandlerFactory extends SearchQueryHandlerFactory {
        QueryHandlerFactory() {
            super(new FakeSearchFilters());
        }

        @Override
        public String getUrl(final String query,
                             @NonNull final List<FilterItem> selectedContentFilter,
                             @Nullable final List<FilterItem> selectedSortFilter) {
            return query;
        }
    }
}
