package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MetaInfo;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.kick.KickSearchFilters;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

public final class KickSearchExtractor extends SearchExtractor {
    public KickSearchExtractor(final StreamingService service, final SearchQueryHandler handler) {
        super(service, handler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // The one public search request is made when its items are requested.
    }

    @Nonnull
    @Override
    public String getSearchSuggestion() {
        return "";
    }

    @Override
    public boolean isCorrectedSearch() {
        return false;
    }

    @Nonnull
    @Override
    public List<MetaInfo> getMetaInfo() {
        return List.of();
    }

    @Nonnull
    @Override
    public InfoItemsPage<InfoItem> getInitialPage() throws IOException, ExtractionException {
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        final String query = getSearchString();
        if (query == null || query.isBlank()) {
            return new InfoItemsPage<>(collector, null);
        }
        final List<FilterItem> filters = getLinkHandler().getContentFilters();
        final int filter = filters.isEmpty()
                ? KickSearchFilters.ALL : filters.get(0).getIdentifier();
        if (filter == KickSearchFilters.CHANNELS) {
            collectChannels(KickApi.search(query, 50), collector);
        } else if (filter == KickSearchFilters.ALL || filter == KickSearchFilters.LIVE) {
            final JsonObject data = KickApi.enrichedSearch(query);
            if (!(data.get("livestreams") instanceof JsonArray)
                    || filter == KickSearchFilters.ALL
                    && !(data.get("channels") instanceof JsonArray)) {
                throw new ParsingException("Kick search response has no requested result list");
            }
            for (final Object value : data.getArray("livestreams")) {
                if (value instanceof JsonObject) {
                    final JsonObject live = (JsonObject) value;
                    final JsonObject channel = KickApi.child(live, "channel");
                    if (KickApi.listable(channel) && KickApi.listable(live)
                            && live.getBoolean("is_live", true)) {
                        collector.commit(new KickStreamInfoItemExtractor(live, channel, true));
                    }
                }
            }
            if (filter == KickSearchFilters.ALL) {
                collectChannels(data.getArray("channels"), collector);
            }
        } else {
            throw new ParsingException("Unknown Kick search filter");
        }
        return new InfoItemsPage<>(collector, null);
    }

    private static void collectChannels(final JsonArray channels,
                                        final MultiInfoItemsCollector collector) {
        for (final Object value : channels) {
            if (value instanceof JsonObject && !((JsonObject) value).getBoolean("is_banned")) {
                collector.commit(new ChannelItem((JsonObject) value));
            }
        }
    }

    @Override
    public InfoItemsPage<InfoItem> getPage(final Page page) throws ParsingException {
        throw new ParsingException("Kick search does not expose continuation pages");
    }

    private static final class ChannelItem implements ChannelInfoItemExtractor {
        private final JsonObject channel;

        ChannelItem(final JsonObject channel) {
            this.channel = channel;
        }

        @Override
        public String getUrl() throws ParsingException {
            return KickLinkHandlerFactory.CHANNEL.getUrl(channel.getString("slug", ""));
        }

        @Override
        public String getName() {
            return KickApi.name(channel);
        }

        @Override
        public List<Image> getThumbnails() {
            return KickApi.images(KickApi.avatar(channel));
        }

        @Override
        public String getDescription() {
            return KickApi.biography(channel);
        }

        @Override
        public long getSubscriberCount() {
            return KickApi.followers(channel);
        }

        @Override
        public long getStreamCount() {
            return -1;
        }

        @Override
        public boolean isVerified() {
            return KickApi.verified(channel);
        }
    }
}
