package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.jsoup.nodes.Document;
import org.schabi.newpipe.extractor.Collector;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.List;

/**
 * shared code for {@link RumbleTrendingExtractor} and {@link RumbleChannelExtractor}
 */
public class RumbleCommonCodeTrendingAndChannel {
    private final int serviceId;
    private final RumbleCommonCodeTrendingAndSearching rumbleCommonCodeTrendingAndSearching;

    public RumbleCommonCodeTrendingAndChannel(final int serviceId,
                                              final RumbleItemsExtractorImpl itemsExtractor) {
        this.serviceId = serviceId;
        this.rumbleCommonCodeTrendingAndSearching =
                new RumbleCommonCodeTrendingAndSearching(itemsExtractor);
    }

    public ListExtractor.InfoItemsPage<InfoItem> extractAndGetInfoItemsFromPage(
            final Document doc)
            throws IOException, ExtractionException {
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(serviceId);
        final Page nextPage = getNextPage(doc, collector);
        return new ListExtractor.InfoItemsPage<>(collector, nextPage);
    }

    public ListExtractor.InfoItemsPage<StreamInfoItem> extractAndGetStreamInfoItemsFromPage(
            final Document doc)
            throws IOException, ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(serviceId);
        final Page nextPage = getNextPage(doc, collector);
        return new ListExtractor.InfoItemsPage<>(collector, nextPage);
    }

    private Page getNextPage(
            final Document doc,
            final Collector collector) throws ParsingException {
        final List<StreamInfoItemExtractor> infoItemsList =
                rumbleCommonCodeTrendingAndSearching.getSearchOrTrendingResultsItemList(doc);

        for (final StreamInfoItemExtractor infoItemExtractor : infoItemsList) {
            collector.commit(infoItemExtractor);
        }

        return rumbleCommonCodeTrendingAndSearching
                .getNewPageIfThereAreMoreThanOnePageResults(infoItemsList.size(), doc);
    }
}
