package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;

import java.util.List;

/**
 * common code shared between {@link RumbleSearchExtractor} and {@link RumbleTrendingExtractor}
 */
public class RumbleCommonCodeTrendingAndSearching {

    private final RumbleItemsExtractorImpl itemsExtractor;

    public RumbleCommonCodeTrendingAndSearching(final RumbleItemsExtractorImpl itemsExtractor1) {
        itemsExtractor = itemsExtractor1;
    }

    public Page getNewPageIfThereAreMoreThanOnePageResults(final int numberOfCollectedItems,
                                                           final Document doc) {
        if (numberOfCollectedItems <= 0) {
            return null;
        }
        final Element nextLink = doc.selectFirst("link[rel=next]");
        if (nextLink == null || nextLink.attr("href").isEmpty()) {
            return null;
        }
        final String nextUrl = nextLink.absUrl("href");
        return nextUrl.isEmpty() ? null : new Page(nextUrl);
    }

    @SuppressWarnings("checkstyle:InvalidJavadocPosition")
    public List<StreamInfoItemExtractor> getSearchOrTrendingResultsItemList(final Document doc)
            throws ParsingException {
        return itemsExtractor.extractStreamItems(doc);
    }

    protected String getClassValue(final Element element,
                                   final String className,
                                   final String attr) {
        return element.getElementsByClass(className).first().attr(attr);
    }
}
