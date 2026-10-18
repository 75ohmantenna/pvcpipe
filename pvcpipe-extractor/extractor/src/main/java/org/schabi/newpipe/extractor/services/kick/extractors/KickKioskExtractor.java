package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;

import javax.annotation.Nonnull;

public final class KickKioskExtractor extends KioskExtractor<StreamInfoItem> {
    public KickKioskExtractor(final StreamingService service, final ListLinkHandler handler,
                              final String kioskId) {
        super(service, handler, kioskId);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // Featured is a single server-maintained list of live broadcasts.
    }

    @Nonnull
    @Override
    public String getName() {
        return getId();
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        final JsonObject data = KickApi.child(KickApi.object(KickApi.FEATURED), "data");
        if (!(data.get("livestreams") instanceof JsonArray)) {
            throw new ParsingException("Kick featured response has no livestream list");
        }
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        for (final Object value : data.getArray("livestreams")) {
            if (value instanceof JsonObject) {
                final JsonObject live = (JsonObject) value;
                final JsonObject channel = KickApi.child(live, "channel");
                if (!channel.getBoolean("is_banned") && live.getBoolean("is_live", true)) {
                    collector.commit(new KickStreamInfoItemExtractor(live, channel, true));
                }
            }
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page) throws ParsingException {
        throw new ParsingException("Kick featured has no continuation pages");
    }
}
