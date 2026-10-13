package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;
import org.schabi.newpipe.extractor.utils.PvcPipeExtractorUtils;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

public class OdyseeChannelTabExtractor extends ChannelTabExtractor {

    private JsonObject channel;

    public OdyseeChannelTabExtractor(final StreamingService service,
                                     final ListLinkHandler linkHandler) {
        super(service, linkHandler);
        if (linkHandler instanceof PvcPipeExtractorUtils.CustomTabListLinkHandler) {
            final Object channelData =
                    ((PvcPipeExtractorUtils.CustomTabListLinkHandler<?>) linkHandler).channelData;
            if (channelData instanceof JsonObject) {
                channel = (JsonObject) channelData;
            }
        }
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        if (channel == null) {
            channel = OdyseeApi.resolveCached(OdyseeUrl.toLbryUri(getId()));
        }
        if (!OdyseeClaim.isChannel(channel)) {
            throw new ContentNotSupportedException("This Odysee address is not a channel");
        }
    }

    @Nonnull
    @Override
    public InfoItemsPage<InfoItem> getInitialPage() throws IOException, ExtractionException {
        return getPage(1);
    }

    @Override
    public InfoItemsPage<InfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (page == null || page.getId() == null) {
            throw new ParsingException("Invalid Odysee channel page");
        }
        try {
            return getPage(Integer.parseInt(page.getId()));
        } catch (final NumberFormatException e) {
            throw new ParsingException("Invalid Odysee channel page: " + page.getId(), e);
        }
    }

    private InfoItemsPage<InfoItem> getPage(final int pageNumber)
            throws IOException, ExtractionException {
        if (channel == null) {
            fetchPage();
        }
        final JsonObject params = OdyseeItems.mediaSearch(pageNumber);
        switch (getChannelTabType().getIdentifier()) {
            case ChannelTabs.ID_LIVESTREAMS:
                // A livestream page has no file until its recording is published.
                params.remove("has_source");
                params.remove("stream_types");
                params.put("has_no_source", true);
                break;
            case ChannelTabs.ID_PLAYLISTS:
                params.remove("has_source");
                params.remove("stream_types");
                params.put("claim_type", OdyseeApi.array(List.of("collection")));
                break;
            default:
                break;
        }
        params.put("channel_ids", OdyseeApi.array(List.of(channel.getString("claim_id"))));
        params.put("not_tags", OdyseeApi.array(
                List.of(OdyseeClaim.TAG_UNLISTED, OdyseeClaim.TAG_SCHEDULED_HIDDEN)));
        params.put("order_by", OdyseeApi.array(List.of("release_time")));

        final List<JsonObject> claims = OdyseeClaim.list(OdyseeApi.claimSearch(params));
        final MultiInfoItemsCollector items = new MultiInfoItemsCollector(getServiceId());
        OdyseeItems.commitAll(items, claims);
        return new InfoItemsPage<>(items, OdyseeItems.hasNextPage(pageNumber, claims.size())
                ? new Page(getUrl(), String.valueOf(pageNumber + 1)) : null);
    }
}
