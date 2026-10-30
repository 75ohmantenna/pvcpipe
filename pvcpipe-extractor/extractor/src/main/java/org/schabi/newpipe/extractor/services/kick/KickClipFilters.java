package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;

import java.util.List;

import javax.annotation.Nullable;

/** Stable clip sort/range choices, carried in a channel tab's sort filters. */
public final class KickClipFilters {
    public static final FilterItem SORT_VIEWS =
            new FilterItem(100, LibraryStringIds.SEARCH_FILTERS_VIEWS);
    public static final FilterItem SORT_DATE =
            new FilterItem(101, LibraryStringIds.SEARCH_FILTERS_UPLOAD_DATE);
    public static final FilterItem TIME_DAY =
            new FilterItem(102, LibraryStringIds.SEARCH_FILTERS_PAST_DAY);
    public static final FilterItem TIME_WEEK =
            new FilterItem(103, LibraryStringIds.SEARCH_FILTERS_PAST_WEEK);
    public static final FilterItem TIME_MONTH =
            new FilterItem(104, LibraryStringIds.SEARCH_FILTERS_PAST_MONTH);
    public static final FilterItem TIME_ALL =
            new FilterItem(105, LibraryStringIds.SEARCH_FILTERS_ANY_TIME);

    private KickClipFilters() {
    }

    public static List<FilterItem> fromValues(@Nullable final String sort,
                                              @Nullable final String range)
            throws ParsingException {
        final FilterItem sortFilter;
        if (sort == null || "date".equals(sort)) {
            sortFilter = SORT_DATE;
        } else if ("views".equals(sort)) {
            sortFilter = SORT_VIEWS;
        } else {
            throw new ParsingException("Unknown Kick clip sort order");
        }
        final FilterItem timeFilter;
        if (range == null || "all".equals(range)) {
            timeFilter = TIME_ALL;
        } else if ("day".equals(range)) {
            timeFilter = TIME_DAY;
        } else if ("week".equals(range)) {
            timeFilter = TIME_WEEK;
        } else if ("month".equals(range)) {
            timeFilter = TIME_MONTH;
        } else {
            throw new ParsingException("Unknown Kick clip time range");
        }
        return List.of(sortFilter, timeFilter);
    }

    public static String sort(@Nullable final List<FilterItem> filters) throws ParsingException {
        return value(filters, true);
    }

    public static String time(@Nullable final List<FilterItem> filters) throws ParsingException {
        return value(filters, false);
    }

    private static String value(@Nullable final List<FilterItem> filters, final boolean sortValue)
            throws ParsingException {
        String sort = null;
        String range = null;
        if (filters != null) {
            for (final FilterItem filter : filters) {
                if (filter == null) {
                    throw new ParsingException("Invalid Kick clip filter");
                }
                switch (filter.getIdentifier()) {
                    case 100:
                    case 101:
                        if (sort != null) {
                            throw new ParsingException("Conflicting Kick clip sort orders");
                        }
                        sort = filter.getIdentifier() == 100 ? "views" : "date";
                        break;
                    case 102:
                    case 103:
                    case 104:
                    case 105:
                        if (range != null) {
                            throw new ParsingException("Conflicting Kick clip time ranges");
                        }
                        switch (filter.getIdentifier()) {
                            case 102:
                                range = "day";
                                break;
                            case 103:
                                range = "week";
                                break;
                            case 104:
                                range = "month";
                                break;
                            default:
                                range = "all";
                                break;
                        }
                        break;
                    default:
                        throw new ParsingException("Unknown Kick clip filter");
                }
            }
        }
        return sortValue ? (sort == null ? "date" : sort) : (range == null ? "all" : range);
    }
}
