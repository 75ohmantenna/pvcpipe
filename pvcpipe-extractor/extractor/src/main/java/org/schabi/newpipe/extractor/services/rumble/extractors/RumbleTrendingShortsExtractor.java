package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javax.annotation.Nonnull;

import static org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleStreamLinkHandlerFactory.BASE_URL;

public class RumbleTrendingShortsExtractor extends KioskExtractor<StreamInfoItem> {

    public static final String KIOSK_SHORTS = "Shorts";

    private JsonObject initialData;

    public RumbleTrendingShortsExtractor(final StreamingService streamingService,
                                         final ListLinkHandler listLinkHandler,
                                         final String kioskId) {
        super(streamingService, listLinkHandler, kioskId);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        initialData = null;
        final JsonObject data = RumbleApi.fetchData(downloader, createEndpoint(0),
                getExtractorLocalization());
        validateData(data);
        initialData = data;
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return KIOSK_SHORTS;
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage()
            throws IOException, ExtractionException {
        if (initialData == null) {
            throw new ParsingException("Rumble Shorts has not been fetched");
        }
        return extractItems(initialData, new Page(createEndpoint(0), "0"), 0);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        final int offset = getOffset(page);
        final JsonObject data = RumbleApi.fetchData(getDownloader(), page.getUrl(),
                getExtractorLocalization());
        validateData(data);
        return extractItems(data, page, offset);
    }

    private static void validateData(final JsonObject data) throws ParsingException {
        final JsonArray items = JsonUtils.getArray(data, "items");
        if (JsonUtils.getBoolean(data, "has_more") && items.isEmpty()) {
            throw new ParsingException("Rumble Shorts has_more with no items");
        }
    }

    private InfoItemsPage<StreamInfoItem> extractItems(final JsonObject data, final Page page,
                                                      final int offset)
            throws ParsingException {
        final JsonArray items = JsonUtils.getArray(data, "items");
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        for (final Object entry : items) {
            if (entry instanceof JsonObject
                    && "video".equals(((JsonObject) entry).getString("object_type"))) {
                collector.commit(new RumbleJsonStreamInfoItemExtractor((JsonObject) entry));
            }
        }
        final int nextOffset = offset + items.size();
        final Page next = JsonUtils.getBoolean(data, "has_more")
                ? new Page(page.getUrl().replaceFirst("([?&]offset=)[0-9]+(?=&|$)",
                        "$1" + nextOffset), String.valueOf(nextOffset)) : null;
        return new InfoItemsPage<>(collector, next);
    }

    private static int getOffset(final Page page) throws ParsingException {
        if (!Page.isValid(page) || page.getUrl() == null || page.getId() == null) {
            throw new ParsingException("Invalid Rumble Shorts page");
        }
        try {
            final URL url = new URL(page.getUrl());
            final int offset = Integer.parseInt(page.getId());
            if (offset < 0 || !"/service.php".equals(url.getPath())
                    || !"shorts.feed".equals(Utils.getQueryValue(url, "name"))
                    || !page.getId().equals(Utils.getQueryValue(url, "offset"))) {
                throw new ParsingException("Invalid Rumble Shorts continuation");
            }
            return offset;
        } catch (final MalformedURLException | IllegalArgumentException e) {
            throw new ParsingException("Invalid Rumble Shorts continuation", e);
        }
    }

    private static String createEndpoint(final int offset) {
        return BASE_URL
                + "/service.php?name=shorts.feed"
                + "&offset=" + offset
                + "&limit=10"
                + "&api=7"
                + "&options=video.full%2Cvideo.related_video";
    }
}
