package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.Collections;

import javax.annotation.Nonnull;

import static org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleStreamLinkHandlerFactory.BASE_URL;


@SuppressWarnings({"checkstyle:FinalLocalVariable", "checkstyle:FinalParameters"})
public class RumbleTrendingShortsExtractor extends KioskExtractor<StreamInfoItem> {

    public static final String KIOSK_SHORTS = "Shorts";

    private JsonArray videos;

    public RumbleTrendingShortsExtractor(final StreamingService streamingService,
                                         final ListLinkHandler listLinkHandler,
                                         final String kioskId) {
        super(streamingService, listLinkHandler, kioskId);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        try {
            String responseData = RumbleParsingHelper.fetchResponse(downloader,
                            createEndpoint(0),
                            Collections.emptyMap(),
                            NewPipe.getPreferredLocalization())
                    .responseBody();
            parseVideosFromResponse(responseData);
        } catch (final JsonParserException e) {
            e.printStackTrace();
            throw new ParsingException("Could not parse Rumble featured API response", e);
        }
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
        return extractItems(videos, new Page(createEndpoint(0), "0"));
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        try {
            String responseBody = RumbleParsingHelper.fetchResponse(getDownloader(), page.getUrl())
                    .responseBody();
            parseVideosFromResponse(responseBody);
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse Rumble shorts API response", e);
        }

        return extractItems(videos, page);
    }

    private InfoItemsPage<StreamInfoItem> extractItems(
            final JsonArray shorts,
            Page page
    ) {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());

        for (Object obj : shorts) {
            JsonObject relatedItem = (JsonObject) obj;

            if ("video".equals(relatedItem.getString("object_type"))) {
                collector.commit(new RumbleShortInfoItemExtractor(relatedItem));
            }
        }

        return new InfoItemsPage<>(collector, shorts.isEmpty() ? null : getNextPageFrom(page));
    }

    /**
     * Advances by ten from the current page's offset.
     */
    private Page getNextPageFrom(Page page) {
        int offset = page.getId() != null ? Integer.parseInt(page.getId()) : 0;
        return createPage(offset);
    }

    @Nonnull
    private Page createPage(int offset) {
        offset += 10;
        return new Page(
                createEndpoint(offset),
                String.valueOf(offset)
        );
    }

    private void parseVideosFromResponse(String responseData)
            throws ParsingException, JsonParserException {
        JsonObject root = JsonParser.object().from(responseData);

        JsonObject data = root.getObject("data");
        if (data == null) {
            throw new ParsingException("Missing data object");
        }
        videos = data.getArray("items");
        if (videos == null) {
            throw new ParsingException("Missing items/videos object");
        }
    }

    private String createEndpoint(int offset) {
        return BASE_URL
                + "/service.php?name=shorts.feed"
                + "&offset=" + offset
                + "&limit=10"
                + "&api=7"
                + "&options=video.full%2Cvideo.related_video";
    }
}
