package org.schabi.newpipe.extractor.services.odysee.linkHandler;

import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;
import org.schabi.newpipe.extractor.services.odysee.search.filter.OdyseeFilters;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class OdyseeSearchQueryHandlerFactory extends SearchQueryHandlerFactory {

    private static final String SEARCH_URL = OdyseeUrl.BASE_URL + "/$/search?q=";

    private static final OdyseeSearchQueryHandlerFactory INSTANCE =
            new OdyseeSearchQueryHandlerFactory();

    private OdyseeSearchQueryHandlerFactory() {
        super(new OdyseeFilters());
    }

    public static OdyseeSearchQueryHandlerFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getUrl(final String query,
                         @Nonnull final List<FilterItem> selectedContentFilter,
                         @Nullable final List<FilterItem> selectedSortFilter) {
        // The filters are static shared state, so they are evaluated without being stored.
        return SEARCH_URL + Utils.encodeUrlUtf8(query)
                + "&" + OdyseeFilters.contentQuery(selectedContentFilter)
                + OdyseeFilters.sortQuery(selectedContentFilter, selectedSortFilter);
    }
}
