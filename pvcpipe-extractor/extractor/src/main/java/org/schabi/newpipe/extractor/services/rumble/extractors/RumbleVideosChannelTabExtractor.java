package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.jsoup.nodes.Document;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;

import java.io.IOException;

import javax.annotation.Nonnull;

public class RumbleVideosChannelTabExtractor extends ChannelTabExtractor {

    private RumbleCommonCodeTrendingAndChannel sharedTrendingAndChannelCode;
    private Document doc;

    protected RumbleVideosChannelTabExtractor(
            @Nonnull final StreamingService service,
            @Nonnull final ListLinkHandler linkHandler) {
        super(service, linkHandler);

        sharedTrendingAndChannelCode =
                new RumbleCommonCodeTrendingAndChannel(getServiceId(), null);
    }

    @Override
    public ListExtractor.InfoItemsPage<InfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (null == page) {
            return null;
        }

        doc = RumbleParsingHelper.fetchParseValidate(getDownloader(), page.getUrl());
        return sharedTrendingAndChannelCode.extractAndGetInfoItemsFromPage(doc);
    }

    @Nonnull
    @Override
    public ListExtractor.InfoItemsPage<InfoItem> getInitialPage()
            throws IOException, ExtractionException {
        return sharedTrendingAndChannelCode.extractAndGetInfoItemsFromPage(doc);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        doc = RumbleParsingHelper.fetchParseValidate(downloader, getUrl());
    }
}
