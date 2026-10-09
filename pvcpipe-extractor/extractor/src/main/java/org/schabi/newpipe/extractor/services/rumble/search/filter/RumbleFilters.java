// Created by evermind-zz 2022, licensed GNU GPL version 3 or later

package org.schabi.newpipe.extractor.services.rumble.search.filter;

import org.schabi.newpipe.extractor.search.filter.BaseSearchFilters;
import org.schabi.newpipe.extractor.search.filter.FilterContainer;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;
import org.schabi.newpipe.extractor.search.filter.QuerySortFilterItem;

public final class RumbleFilters extends BaseSearchFilters {

    /**
     * The rumble searchpage has a kind of 'all' (channels and videos)
     * search feature. Nevertheless the found channels are more or
     * less like a preview and a link to the channel search.
     * There is no backend-based search-all feature with one result page, and implementing one
     * client-side would require combining multiple result sets.
     */
    public static final int ID_CF_MAIN_GRP = 0;
    public static final int ID_CF_MAIN_VIDEOS = 1;
    public static final int ID_CF_MAIN_CHANNELS = 2;
    public static final int ID_SF_SORT_BY_GRP = 3;
    public static final int ID_SF_SORT_BY_RELEVANCE = 4;
    public static final int ID_SF_SORT_BY_MOST_RECENT = 5;
    public static final int ID_SF_SORT_BY_RUMBLES = 6;
    public static final int ID_SF_SORT_BY_VIEWS = 7;
    public static final int ID_SF_UPLOAD_DATE_GRP = 8;
    public static final int ID_SF_UPLOAD_DATE_ALL = 9;
    public static final int ID_SF_UPLOAD_DATE_TODAY = 10;
    public static final int ID_SF_UPLOAD_DATE_LAST_WEEK = 11;
    public static final int ID_SF_UPLOAD_DATE_LAST_MONTH = 12;
    public static final int ID_SF_UPLOAD_DATE_LAST_YEAR = 13;
    public static final int ID_SF_DURATION_GRP = 14;
    public static final int ID_SF_DURATION_ALL = 15;
    public static final int ID_SF_DURATION_LONG = 16;
    public static final int ID_SF_DURATION_SHORT = 17;

    private static final String SEARCH_VIDEOS_URL = "https://rumble.com/search/video?q=";
    private static final String SEARCH_CHANNEL_URL = "https://rumble.com/search/channel?q=";

    @Override
    protected void init() {
        /* sort filters */
        /* 'Sort by' filter items */
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_SORT_BY_RELEVANCE,
                LibraryStringIds.SEARCH_FILTERS_RELEVANCE, ""));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_SORT_BY_MOST_RECENT,
                LibraryStringIds.SEARCH_FILTERS_MOST_RECENT, "sort=date"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_SORT_BY_RUMBLES,
                LibraryStringIds.SEARCH_FILTERS_RUMBLES, "sort=rumbles"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_SORT_BY_VIEWS,
                LibraryStringIds.SEARCH_FILTERS_VIEWS, "sort=views"));

        /* 'Date' filter items */
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_UPLOAD_DATE_ALL,
                LibraryStringIds.SEARCH_FILTERS_ALL, ""));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_UPLOAD_DATE_TODAY,
                LibraryStringIds.SEARCH_FILTERS_TODAY, "date=today"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_UPLOAD_DATE_LAST_WEEK,
                LibraryStringIds.SEARCH_FILTERS_PAST_WEEK, "date=this-week"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_UPLOAD_DATE_LAST_MONTH,
                LibraryStringIds.SEARCH_FILTERS_PAST_MONTH, "date=this-month"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_UPLOAD_DATE_LAST_YEAR,
                LibraryStringIds.SEARCH_FILTERS_LAST_YEAR, "date=this-year"));


        /* 'Duration' filter items */
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DURATION_ALL,
                LibraryStringIds.SEARCH_FILTERS_ALL, ""));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DURATION_LONG,
                LibraryStringIds.SEARCH_FILTERS_LONG, "duration=long"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DURATION_SHORT, LibraryStringIds.SEARCH_FILTERS_SHORT, "duration=short"));


        final FilterContainer allSortFilters = new FilterContainer(new FilterGroup[]{
                groupsFactory.createFilterGroup(ID_SF_SORT_BY_GRP,
                        LibraryStringIds.SEARCH_FILTERS_SORT_BY, true,
                        ID_SF_SORT_BY_RELEVANCE, null,
                        ID_SF_SORT_BY_RELEVANCE, ID_SF_SORT_BY_MOST_RECENT,
                        ID_SF_SORT_BY_RUMBLES, ID_SF_SORT_BY_VIEWS),
                groupsFactory.createFilterGroup(ID_SF_UPLOAD_DATE_GRP,
                        LibraryStringIds.SEARCH_FILTERS_UPLOAD_DATE, true,
                        ID_SF_UPLOAD_DATE_ALL, null,
                        ID_SF_UPLOAD_DATE_ALL, ID_SF_UPLOAD_DATE_TODAY,
                        ID_SF_UPLOAD_DATE_LAST_WEEK, ID_SF_UPLOAD_DATE_LAST_MONTH,
                        ID_SF_UPLOAD_DATE_LAST_YEAR),
                groupsFactory.createFilterGroup(ID_SF_DURATION_GRP,
                        LibraryStringIds.SEARCH_FILTERS_DURATION, true,
                        ID_SF_DURATION_ALL, null,
                        ID_SF_DURATION_ALL, ID_SF_DURATION_LONG, ID_SF_DURATION_SHORT)
        });


        /* content filters */
        groupsFactory.addFilterItem(new RumbleContentFilterItem(
                ID_CF_MAIN_VIDEOS, LibraryStringIds.SEARCH_FILTERS_VIDEOS, SEARCH_VIDEOS_URL));
        groupsFactory.addFilterItem(new RumbleContentFilterItem(
                ID_CF_MAIN_CHANNELS, LibraryStringIds.SEARCH_FILTERS_CHANNELS, SEARCH_CHANNEL_URL));

        /* content filter groups */
        addContentFilterGroup(groupsFactory.createFilterGroup(ID_CF_MAIN_GRP, null, true,
                ID_CF_MAIN_VIDEOS, new FilterItem[]{
                        groupsFactory.getFilterForId(ID_CF_MAIN_VIDEOS),
                        groupsFactory.getFilterForId(ID_CF_MAIN_CHANNELS),
                }, allSortFilters));
        addContentFilterSortVariant(ID_CF_MAIN_VIDEOS, allSortFilters);
    }

    @Override
    public String evaluateSelectedSortFilters() {
        return QuerySortFilterItem.evaluate(selectedSortFilter);
    }

    @Override
    public String evaluateSelectedContentFilters() {
        if (selectedContentFilter != null && !selectedContentFilter.isEmpty()) {
            final RumbleContentFilterItem contentItem =
                    (RumbleContentFilterItem) selectedContentFilter.get(0);
            if (contentItem != null) {
                return contentItem.urlEndpoint;
            }
        }
        return SEARCH_VIDEOS_URL; // default to video url if no content filter is given
    }

    public static class RumbleContentFilterItem extends FilterItem {
        private final String urlEndpoint;

        public RumbleContentFilterItem(final int identifier,
                                       final LibraryStringIds nameId,
                                       final String urlEndpoint) {
            super(identifier, nameId);
            this.urlEndpoint = urlEndpoint;
        }
    }
}
