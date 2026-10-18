package org.schabi.newpipe.extractor.services.kick.extractors;

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
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickLinkHandlerFactory;

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
        for (final Object value : KickApi.search(getSearchString(), 50)) {
            if (value instanceof JsonObject && !((JsonObject) value).getBoolean("is_banned")) {
                collector.commit(new ChannelItem((JsonObject) value));
            }
        }
        return new InfoItemsPage<>(collector, null);
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
