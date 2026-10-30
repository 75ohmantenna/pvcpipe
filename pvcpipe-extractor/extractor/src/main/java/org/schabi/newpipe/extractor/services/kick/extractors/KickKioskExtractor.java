package org.schabi.newpipe.extractor.services.kick.extractors;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;

public final class KickKioskExtractor extends KioskExtractor<StreamInfoItem> {
    private static final int LIMIT = 24;
    private final Set<String> consumedCursors = new HashSet<>();

    public KickKioskExtractor(final StreamingService service, final ListLinkHandler handler,
                              final String kioskId) {
        super(service, handler, kioskId);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // Requests are made when their items are requested.
    }

    @Nonnull
    @Override
    public String getName() {
        return getId();
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        return page(null);
    }

    private String language() {
        return getExtractorLocalization().getLocalizationCode().split("-", 2)[0]
                .toLowerCase(Locale.ROOT);
    }

    private String scope() {
        return KickApi.livestreamsUrl(language(), LIMIT, null, null);
    }

    private InfoItemsPage<StreamInfoItem> page(final String after)
            throws IOException, ExtractionException {
        final JsonObject data = KickApi.livestreams(language(), LIMIT, null, after);
        if (after != null) {
            consumedCursors.add(after);
        }
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
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
        final String cursor = KickApi.child(data, "pagination").getString("next_cursor");
        final Page next = cursor == null || cursor.isBlank() || consumedCursors.contains(cursor)
                ? null : new Page(scope(), cursor, List.copyOf(consumedCursors), null, null);
        return new InfoItemsPage<>(collector, next);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (page == null || !scope().equals(page.getUrl())
                || page.getId() == null || page.getId().isBlank()) {
            throw new ParsingException("Invalid Kick discovery continuation page");
        }
        // Continuation state must survive KioskInfo constructing another extractor.
        if (page.getIds() != null) {
            consumedCursors.addAll(page.getIds());
        }
        if (consumedCursors.contains(page.getId())) {
            return new InfoItemsPage<>(new StreamInfoItemsCollector(getServiceId()), null);
        }
        return page(page.getId());
    }
}
