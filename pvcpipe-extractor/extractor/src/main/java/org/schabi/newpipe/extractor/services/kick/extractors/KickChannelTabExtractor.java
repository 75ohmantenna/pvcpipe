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
import org.schabi.newpipe.extractor.utils.PvcPipeExtractorUtils;

import java.io.IOException;

import javax.annotation.Nonnull;

public final class KickChannelTabExtractor extends ChannelTabExtractor {
    private JsonObject channel;

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
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        final int tab = getChannelTabType().getIdentifier();
        if (tab == ChannelTabs.ID_LIVESTREAMS) {
            final JsonObject live = channel.getObject("livestream");
            if (live != null && !live.isEmpty() && live.getBoolean("is_live", true)) {
                collector.commit(new KickStreamInfoItemExtractor(live, channel, true));
            }
        } else if (tab == ChannelTabs.ID_VIDEOS) {
            // This endpoint returns the channel's whole retained VOD list, with no cursor.
            final JsonArray videos = KickApi.videos(channel);
            for (final Object value : videos) {
                if (value instanceof JsonObject) {
                    final JsonObject stream = (JsonObject) value;
                    if (!stream.getBoolean("is_live") && !stream.getBoolean("is_private")
                            && !stream.getBoolean("is_pruned")
                            && stream.get("deleted_at") == null
                            && !"private".equals(stream.getString("status"))) {
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
    public InfoItemsPage<InfoItem> getPage(final Page page) throws ParsingException {
        throw new ParsingException("Kick channel tabs have no continuation pages");
    }
}
