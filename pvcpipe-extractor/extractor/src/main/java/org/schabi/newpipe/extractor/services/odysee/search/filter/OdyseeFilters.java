package org.schabi.newpipe.extractor.services.odysee.search.filter;

import org.schabi.newpipe.extractor.search.filter.BaseSearchFilters;
import org.schabi.newpipe.extractor.search.filter.FilterContainer;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;
import org.schabi.newpipe.extractor.search.filter.QuerySortFilterItem;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class OdyseeFilters extends BaseSearchFilters {

    public static final int ID_CF_MAIN_GRP = 0;
    public static final int ID_CF_MAIN_ALL = 1;
    public static final int ID_CF_MAIN_VIDEOS = 2;
    public static final int ID_CF_MAIN_TRACKS = 3;
    public static final int ID_CF_MAIN_CHANNELS = 4;
    public static final int ID_SF_SORT_BY_GRP = 5;
    public static final int ID_SF_SORT_BY_RELEVANCE = 6;
    public static final int ID_SF_SORT_BY_NEWEST = 7;
    public static final int ID_SF_SORT_BY_OLDEST = 8;
    public static final int ID_SF_DATE_GRP = 9;
    public static final int ID_SF_DATE_ALL = 10;
    public static final int ID_SF_DATE_TODAY = 11;
    public static final int ID_SF_DATE_THIS_WEEK = 12;
    public static final int ID_SF_DATE_THIS_MONTH = 13;
    public static final int ID_SF_DATE_THIS_YEAR = 14;
    public static final int ID_SF_DURATION_GRP = 15;
    public static final int ID_SF_DURATION_ALL = 16;
    public static final int ID_SF_DURATION_SHORT = 17;
    public static final int ID_SF_DURATION_MEDIUM = 18;
    public static final int ID_SF_DURATION_LONG = 19;

    private static final String QUERY_ALL = "claimType=file,channel";
    private static final String QUERY_VIDEOS = "claimType=file&mediaType=video";
    private static final String QUERY_TRACKS = "claimType=file&mediaType=audio";
    private static final String QUERY_CHANNELS = "claimType=channel";

    /**
     * @param contentFilter the content filter of a search, of which the first one counts
     * @return the search parameters that select that kind of result
     */
    @Nonnull
    public static String contentQuery(@Nullable final List<FilterItem> contentFilter) {
        if (contentFilter == null || contentFilter.isEmpty()) {
            return QUERY_ALL;
        }
        switch (contentFilter.get(0).getIdentifier()) {
            case ID_CF_MAIN_VIDEOS:
                return QUERY_VIDEOS;
            case ID_CF_MAIN_TRACKS:
                return QUERY_TRACKS;
            case ID_CF_MAIN_CHANNELS:
                return QUERY_CHANNELS;
            default:
                return QUERY_ALL;
        }
    }

    /**
     * Odysee's search returns nothing for channels when it is also asked to sort or to filter
     * by date or length.
     *
     * @param contentFilter the content filter of a search, of which the first one counts
     * @return whether the sort filters apply to that kind of result
     */
    public static boolean hasSortFilters(@Nullable final List<FilterItem> contentFilter) {
        return contentFilter == null || contentFilter.isEmpty()
                || contentFilter.get(0).getIdentifier() != ID_CF_MAIN_CHANNELS;
    }

    /**
     * @param contentFilter the content filter of a search, of which the first one counts
     * @param sortFilter    the sort filters of that search
     * @return the search parameters for the sort filters, each with a leading ampersand
     */
    @Nonnull
    public static String sortQuery(@Nullable final List<FilterItem> contentFilter,
                                   @Nullable final List<FilterItem> sortFilter) {
        return hasSortFilters(contentFilter) ? QuerySortFilterItem.evaluate(sortFilter) : "";
    }

    @Override
    public String evaluateSelectedContentFilters() {
        return "&" + contentQuery(selectedContentFilter);
    }

    @Override
    public String evaluateSelectedSortFilters() {
        return sortQuery(selectedContentFilter, selectedSortFilter);
    }

    @Override
    public String evaluateSelectedFilters(@Nullable final String searchString) {
        return evaluateSelectedContentFilters() + evaluateSelectedSortFilters();
    }

    @Override
    protected void init() {
        /* content filters */
        groupsFactory.addFilterItem(new FilterItem(
                ID_CF_MAIN_ALL, LibraryStringIds.SEARCH_FILTERS_ALL));
        groupsFactory.addFilterItem(new FilterItem(
                ID_CF_MAIN_VIDEOS, LibraryStringIds.SEARCH_FILTERS_VIDEOS));
        groupsFactory.addFilterItem(new FilterItem(
                ID_CF_MAIN_TRACKS, LibraryStringIds.SEARCH_FILTERS_TRACKS));
        groupsFactory.addFilterItem(new FilterItem(
                ID_CF_MAIN_CHANNELS, LibraryStringIds.SEARCH_FILTERS_CHANNELS));

        /* 'Sort by' filter items */
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_SORT_BY_RELEVANCE, LibraryStringIds.SEARCH_FILTERS_RELEVANCE, ""));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_SORT_BY_NEWEST, LibraryStringIds.SEARCH_FILTERS_NEWEST_FIRST,
                "sort_by=release_time"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_SORT_BY_OLDEST, LibraryStringIds.SEARCH_FILTERS_OLDEST_FIRST,
                "sort_by=%5Erelease_time"));

        /* 'Upload date' filter items */
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DATE_ALL, LibraryStringIds.SEARCH_FILTERS_ANY_TIME, ""));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DATE_TODAY, LibraryStringIds.SEARCH_FILTERS_TODAY, "time_filter=today"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DATE_THIS_WEEK, LibraryStringIds.SEARCH_FILTERS_THIS_WEEK,
                "time_filter=thisweek"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DATE_THIS_MONTH, LibraryStringIds.SEARCH_FILTERS_THIS_MONTH,
                "time_filter=thismonth"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DATE_THIS_YEAR, LibraryStringIds.SEARCH_FILTERS_THIS_YEAR,
                "time_filter=thisyear"));

        /* 'Duration' filter items */
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DURATION_ALL, LibraryStringIds.SEARCH_FILTERS_ALL, ""));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DURATION_SHORT, LibraryStringIds.SEARCH_FILTERS_UNDER_4_MIN,
                "max_duration=240"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DURATION_MEDIUM, LibraryStringIds.SEARCH_FILTERS_4_20_MIN,
                "min_duration=240&max_duration=1200"));
        groupsFactory.addFilterItem(new QuerySortFilterItem(
                ID_SF_DURATION_LONG, LibraryStringIds.SEARCH_FILTERS_OVER_20_MIN,
                "min_duration=1200"));

        final FilterContainer allSortFilters = new FilterContainer(new FilterGroup[]{
                groupsFactory.createFilterGroup(ID_SF_SORT_BY_GRP,
                        LibraryStringIds.SEARCH_FILTERS_SORT_BY, true,
                        ID_SF_SORT_BY_RELEVANCE, null,
                        ID_SF_SORT_BY_RELEVANCE, ID_SF_SORT_BY_NEWEST, ID_SF_SORT_BY_OLDEST),
                groupsFactory.createFilterGroup(ID_SF_DATE_GRP,
                        LibraryStringIds.SEARCH_FILTERS_UPLOAD_DATE, true,
                        ID_SF_DATE_ALL, null,
                        ID_SF_DATE_ALL, ID_SF_DATE_TODAY, ID_SF_DATE_THIS_WEEK,
                        ID_SF_DATE_THIS_MONTH, ID_SF_DATE_THIS_YEAR),
                groupsFactory.createFilterGroup(ID_SF_DURATION_GRP,
                        LibraryStringIds.SEARCH_FILTERS_DURATION, true,
                        ID_SF_DURATION_ALL, null,
                        ID_SF_DURATION_ALL, ID_SF_DURATION_SHORT, ID_SF_DURATION_MEDIUM,
                        ID_SF_DURATION_LONG),
        });

        /* content filter groups */
        addContentFilterGroup(groupsFactory.createFilterGroup(ID_CF_MAIN_GRP, null, true,
                ID_CF_MAIN_ALL, allSortFilters,
                ID_CF_MAIN_ALL, ID_CF_MAIN_VIDEOS, ID_CF_MAIN_TRACKS, ID_CF_MAIN_CHANNELS));
        addContentFilterSortVariant(ID_CF_MAIN_ALL, allSortFilters);
        addContentFilterSortVariant(ID_CF_MAIN_VIDEOS, allSortFilters);
        addContentFilterSortVariant(ID_CF_MAIN_TRACKS, allSortFilters);
    }
}
