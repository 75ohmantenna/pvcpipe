// Created by evermind-zz 2022, licensed GNU GPL version 3 or later

package org.schabi.newpipe.extractor.search.filter;

import java.util.List;

import javax.annotation.Nullable;

/** A sort filter whose query parameter is appended after the search query. */
public final class QuerySortFilterItem extends FilterItem {
    private final String query;

    public QuerySortFilterItem(final int identifier, final LibraryStringIds nameId,
                               final String query) {
        super(identifier, nameId);
        this.query = query;
    }

    public static String evaluate(@Nullable final List<FilterItem> selectedSortFilter) {
        final StringBuilder sortQuery = new StringBuilder();
        if (selectedSortFilter != null) {
            for (final FilterItem item : selectedSortFilter) {
                final QuerySortFilterItem sortItem = (QuerySortFilterItem) item;
                if (sortItem != null && !sortItem.query.isEmpty()) {
                    sortQuery.append("&").append(sortItem.query);
                }
            }
        }
        return sortQuery.toString();
    }
}
