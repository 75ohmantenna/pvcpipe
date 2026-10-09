package org.schabi.newpipe.extractor.search.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FilterGroupFactoryTest {
    @Test
    void createsGroupFromOrderedIdsIncludingUnknownIds() {
        final FilterGroup.Factory factory = new FilterGroup.Factory();
        final FilterItem first = new FilterItem(1, LibraryStringIds.SEARCH_FILTERS_ALL);
        final FilterItem second = new FilterItem(2, LibraryStringIds.SEARCH_FILTERS_VIDEOS);
        factory.addFilterItem(first);
        factory.addFilterItem(second);

        final FilterGroup group = factory.createFilterGroup(3, null, true, 2, null,
                2, 999, 1, 2);

        assertEquals(3, group.getIdentifier());
        assertEquals(2, group.getDefaultSelectedFilterId());
        assertEquals(4, group.getFilterItems().size());
        assertSame(second, group.getFilterItems().get(0));
        assertNull(group.getFilterItems().get(1));
        assertSame(first, group.getFilterItems().get(2));
        assertSame(second, group.getFilterItems().get(3));
    }
}
