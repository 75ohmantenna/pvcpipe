package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MetaInfo;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.search.filter.OdyseeFilters;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

public class OdyseeSearchExtractor extends SearchExtractor {

    public OdyseeSearchExtractor(final StreamingService service,
                                 final SearchQueryHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // Each page is a request of its own.
    }

    @Nonnull
    @Override
    public String getSearchSuggestion() {
        return "";
    }

    @Override
    public boolean isCorrectedSearch() {
        return false; // Odysee's search does not correct a query.
    }

    @Nonnull
    @Override
    public List<MetaInfo> getMetaInfo() {
        return List.of();
    }

    @Nonnull
    @Override
    public InfoItemsPage<InfoItem> getInitialPage() throws IOException, ExtractionException {
        return getPage(0);
    }

    @Override
    public InfoItemsPage<InfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        return getPage(OdyseeItems.pageNumber(page, "search"));
    }

    /**
     * The search answers with claim ids in order of relevance. The claims themselves come from
     * a second request, which returns them in no particular order.
     */
    private InfoItemsPage<InfoItem> getPage(final int offset)
            throws IOException, ExtractionException {
        final SearchQueryHandler handler = (SearchQueryHandler) getLinkHandler();
        final JsonArray hits = OdyseeApi.search("s=" + Utils.encodeUrlUtf8(getSearchString())
                + "&size=" + OdyseeItems.PAGE_SIZE + "&from=" + offset + "&nsfw=false"
                + "&" + OdyseeFilters.contentQuery(handler.getContentFilters())
                + OdyseeFilters.sortQuery(handler.getContentFilters(), handler.getSortFilter()));

        // A hit that came twice is listed once.
        final Set<String> claimIds = new LinkedHashSet<>();
        for (final Object hit : hits) {
            if (hit instanceof JsonObject
                    && !Utils.isNullOrEmpty(((JsonObject) hit).getString("claimId"))) {
                claimIds.add(((JsonObject) hit).getString("claimId"));
            }
        }

        final MultiInfoItemsCollector items = new MultiInfoItemsCollector(getServiceId());
        OdyseeItems.commitAll(items, OdyseeApi.claimsByIds(claimIds));
        final int nextOffset = offset + OdyseeItems.PAGE_SIZE;
        return new InfoItemsPage<>(items, hits.size() >= OdyseeItems.PAGE_SIZE
                && nextOffset < OdyseeApi.MAX_SEARCH_RESULTS
                ? new Page(getUrl(), String.valueOf(nextOffset)) : null);
    }
}
