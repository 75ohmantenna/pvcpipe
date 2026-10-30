package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.search.filter.BaseSearchFilters;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;

import javax.annotation.Nullable;

public final class KickSearchFilters extends BaseSearchFilters {
    public static final int GROUP = 0;
    public static final int CHANNELS = 1;
    public static final int ALL = 2;
    public static final int LIVE = 3;

    @Override
    protected void init() {
        groupsFactory.addFilterItem(new FilterItem(ALL, LibraryStringIds.SEARCH_FILTERS_ALL));
        groupsFactory.addFilterItem(new FilterItem(LIVE, LibraryStringIds.SEARCH_FILTERS_LIVE));
        groupsFactory.addFilterItem(new FilterItem(CHANNELS,
                LibraryStringIds.SEARCH_FILTERS_CHANNELS));
        addContentFilterGroup(groupsFactory.createFilterGroup(
                GROUP, null, true, ALL, null, ALL, LIVE, CHANNELS));
    }

    @Override
    public String evaluateSelectedFilters(@Nullable final String query) {
        return "";
    }
}
