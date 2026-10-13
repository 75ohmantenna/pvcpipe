package org.schabi.newpipe.extractor.services.odysee.extractors;

import static org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeKioskLinkHandlerFactory.FEATURED;
import static org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeKioskLinkHandlerFactory.LIVE;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * The browse pages of Odysee: what its homepage features, what is trending across the whole
 * site, and who is broadcasting.
 */
public class OdyseeKioskExtractor extends KioskExtractor<StreamInfoItem> {

    private static final String FEATURED_CATEGORY = "PRIMARY_CONTENT";
    private static final int FEATURED_DEFAULT_DAYS = 60;
    private static final long SECONDS_PER_DAY = 24 * 60 * 60L;

    public OdyseeKioskExtractor(final StreamingService streamingService,
                                final ListLinkHandler linkHandler,
                                final String kioskId) {
        super(streamingService, linkHandler, kioskId);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // Each page is a request of its own.
    }

    @Nonnull
    @Override
    public String getName() {
        return getId();
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        return getPage(1);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        return getPage(OdyseeItems.pageNumber(page, "kiosk"));
    }

    private InfoItemsPage<StreamInfoItem> getPage(final int pageNumber)
            throws IOException, ExtractionException {
        final StreamInfoItemsCollector items = new StreamInfoItemsCollector(getServiceId());
        if (LIVE.equals(getId())) {
            // For a livestream the number shown is how many are watching it now.
            final Map<String, Long> viewers = liveViewers();
            OdyseeItems.commitLivestreams(items, OdyseeApi.claimsByIds(viewers.keySet()),
                    viewers);
            return new InfoItemsPage<>(items, null);
        }

        final JsonObject params = OdyseeItems.mediaSearch(pageNumber);
        params.put("order_by", OdyseeApi.array(List.of("trending_group", "trending_mixed")));
        // A visitor who is not signed in cannot play what has to be paid for.
        params.put("not_tags", OdyseeApi.array(List.of(OdyseeClaim.TAG_MATURE,
                OdyseeClaim.TAG_UNLISTED, OdyseeClaim.TAG_SCHEDULED_HIDDEN,
                OdyseeClaim.TAG_MEMBERS_ONLY, OdyseeClaim.TAG_PURCHASE,
                OdyseeClaim.TAG_RENTAL)));
        params.put("fee_amount", "<=0");
        if (FEATURED.equals(getId())) {
            addFeaturedChannels(params);
        }

        final List<JsonObject> claims = OdyseeClaim.list(OdyseeApi.claimSearch(params));
        OdyseeItems.commitStreams(items, claims);
        return new InfoItemsPage<>(items, OdyseeItems.hasNextPage(pageNumber, claims.size())
                ? new Page(getUrl(), String.valueOf(pageNumber + 1)) : null);
    }

    /**
     * The website's homepage defines its featured row as recent uploads of a list of channels
     * that Odysee's staff maintains.
     */
    private void addFeaturedChannels(final JsonObject params)
            throws IOException, ExtractionException {
        final JsonObject category = OdyseeApi.homepageCategory(FEATURED_CATEGORY);
        if (category.getArray("channelIds").isEmpty()) {
            throw new ParsingException("Odysee's homepage no longer lists featured channels");
        }
        params.put("channel_ids", category.getArray("channelIds"));
        params.put("limit_claims_per_channel", 1);
        final long days = category.getLong("daysOfContent", FEATURED_DEFAULT_DAYS);
        params.put("release_time",
                ">" + (System.currentTimeMillis() / 1000 - days * SECONDS_PER_DAY));
    }

    /**
     * @return the claims that are on air with their number of viewers, the most watched first
     */
    private Map<String, Long> liveViewers() throws IOException, ExtractionException {
        final List<JsonObject> live = new ArrayList<>();
        for (final JsonObject channel : OdyseeClaim.list(OdyseeApi.livestreams())) {
            if (channel.getBoolean("Live")
                    && !channel.getObject("ActiveClaim").getString("ClaimID", "").isEmpty()) {
                live.add(channel);
            }
        }
        live.sort(Comparator.comparingLong(
                (JsonObject channel) -> channel.getLong("ViewerCount", 0)).reversed());
        final Map<String, Long> viewers = new LinkedHashMap<>();
        for (final JsonObject channel : live) {
            if (viewers.size() == OdyseeApi.MAX_PAGE_SIZE) {
                break;
            }
            viewers.putIfAbsent(channel.getObject("ActiveClaim").getString("ClaimID"),
                    channel.getLong("ViewerCount", 0));
        }
        return viewers;
    }
}
