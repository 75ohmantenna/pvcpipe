package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.UnsupportedTabException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickClipFilters;
import org.schabi.newpipe.extractor.services.kick.KickLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.PvcPipeExtractorUtils;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

public final class KickChannelTabExtractor extends ChannelTabExtractor {
    private JsonObject channel;
    private final Set<String> consumedClipCursors = new HashSet<>();

    public KickChannelTabExtractor(final StreamingService service, final ListLinkHandler handler) {
        super(service, handler);
        if (handler instanceof PvcPipeExtractorUtils.CustomTabListLinkHandler) {
            final Object data = ((PvcPipeExtractorUtils.CustomTabListLinkHandler<?>) handler)
                    .channelData;
            if (data instanceof JsonObject) {
                channel = (JsonObject) data;
            }
        }
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        // A retained Live-tab handler contains only a snapshot of the original channel load.
        if (channel == null || getChannelTabType().getIdentifier() == ChannelTabs.ID_LIVESTREAMS) {
            channel = KickApi.channel(getId());
        }
    }

    @Nonnull
    @Override
    public InfoItemsPage<InfoItem> getInitialPage() throws IOException, ExtractionException {
        if (channel == null) {
            fetchPage();
        }
        final int tab = getChannelTabType().getIdentifier();
        if (tab == ChannelTabs.ID_CLIPS) {
            return clipPage(null);
        }
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        if (tab == ChannelTabs.ID_LIVESTREAMS) {
            final JsonObject live = channel.getObject("livestream");
            if (live != null && !live.isEmpty() && live.getBoolean("is_live", true)
                    && KickApi.listable(live)) {
                collector.commit(new KickStreamInfoItemExtractor(live, channel, true));
            }
        } else if (tab == ChannelTabs.ID_VIDEOS) {
            // This endpoint returns the channel's whole retained VOD list, with no cursor.
            final JsonArray videos = KickApi.videos(channel);
            for (final Object value : videos) {
                if (value instanceof JsonObject) {
                    final JsonObject stream = (JsonObject) value;
                    if (!stream.getBoolean("is_live") && KickApi.listable(stream)) {
                        collector.commit(new KickStreamInfoItemExtractor(stream, channel, false));
                    }
                }
            }
        } else {
            throw new UnsupportedTabException(getChannelTabType());
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    public InfoItemsPage<InfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (getChannelTabType().getIdentifier() != ChannelTabs.ID_CLIPS) {
            throw new ParsingException("Kick Videos and Live tabs have no continuation pages");
        }
        // A Page is only an opaque cursor scoped to this exact channel/filter selection.
        // Never fetch its URL or trust a caller-supplied API endpoint.
        if (page == null || !clipTabUrl().equals(page.getUrl())
                || page.getId() == null || page.getId().isBlank()) {
            throw new ParsingException("Invalid Kick clips continuation page");
        }
        // The app and play queue construct a fresh extractor for every continuation.
        if (page.getIds() != null) {
            consumedClipCursors.addAll(page.getIds());
        }
        if (consumedClipCursors.contains(page.getId())) {
            return new InfoItemsPage<>(new MultiInfoItemsCollector(getServiceId()), null);
        }
        if (channel == null) {
            fetchPage();
        }
        return clipPage(page.getId());
    }

    private String clipTabUrl() throws ParsingException {
        return KickLinkHandlerFactory.TAB.getUrl(getId(), getLinkHandler().getContentFilters(),
                getLinkHandler().getSortFilter());
    }

    private InfoItemsPage<InfoItem> clipPage(final String cursor)
            throws IOException, ExtractionException {
        final String sort = KickClipFilters.sort(getLinkHandler().getSortFilter());
        final String time = KickClipFilters.time(getLinkHandler().getSortFilter());
        final JsonObject data = KickApi.clips(channel.getLong("id", 0), sort, time, cursor);
        if (cursor != null) {
            consumedClipCursors.add(cursor);
        }
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        for (final Object value : data.getArray("clips")) {
            if (value instanceof JsonObject) {
                final JsonObject clip = (JsonObject) value;
                final JsonObject owner = clip.getObject("channel");
                if (KickApi.listable(channel) && KickApi.listable(clip)
                        && (owner == null || KickApi.listable(owner))) {
                    collector.commit(new KickClipInfoItemExtractor(clip, channel));
                }
            }
        }
        final String nextCursor = data.getString("cursor");
        final Page next = nextCursor == null || nextCursor.isBlank()
                || consumedClipCursors.contains(nextCursor)
                ? null : new Page(clipTabUrl(), nextCursor,
                        List.copyOf(consumedClipCursors), null, null);
        return new InfoItemsPage<>(collector, next);
    }
}
