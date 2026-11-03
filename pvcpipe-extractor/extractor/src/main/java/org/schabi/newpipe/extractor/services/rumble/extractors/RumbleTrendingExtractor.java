package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.jsoup.nodes.Document;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleTrendingLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;

public class RumbleTrendingExtractor extends KioskExtractor<StreamInfoItem> {

    private final RumbleCommonCodeTrendingAndChannel sharedTrendingAndChannelCode;
    private Document doc;
    private JsonObject initialLiveData;

    public RumbleTrendingExtractor(final StreamingService service,
                                   final ListLinkHandler linkHandler,
                                   final String kioskId) {
        super(service, linkHandler, kioskId);

        final RumbleItemsExtractorImpl itemsExtractor;
        if (RumbleTrendingLinkHandlerFactory.EDITOR_PICKS.equals(kioskId)) {
            itemsExtractor = new RumbleEditorPicksItemsExtractorImpl();
        } else {
            itemsExtractor = new RumbleSearchTrendingItemsExtractorImpl();
        }
        sharedTrendingAndChannelCode = new RumbleCommonCodeTrendingAndChannel(
                getServiceId(), itemsExtractor);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        doc = null;
        initialLiveData = null;
        if (RumbleTrendingLinkHandlerFactory.LIVE.equals(getId())) {
            final JsonObject data = RumbleApi.fetchData(downloader, createLiveEndpoint(0),
                    getExtractorLocalization());
            JsonUtils.getArray(data, "items");
            initialLiveData = data;
            return;
        }
        doc = RumbleParsingHelper.fetchParseValidate(downloader,
                getUrl(), RumbleParsingHelper.getMinimalHeaders(),
                NewPipe.getPreferredLocalization());
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return getId();
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (!Page.isValid(page) || page.getUrl() == null) {
            throw new ParsingException("Invalid Rumble kiosk page");
        }
        final URL url;
        try {
            url = new URL(page.getUrl());
        } catch (final MalformedURLException e) {
            throw new ParsingException("Invalid Rumble kiosk page", e);
        }
        if ("/service.php".equals(url.getPath())) {
            if (!"video_collection.live".equals(Utils.getQueryValue(url, "name"))) {
                throw new ParsingException("Unknown Rumble kiosk API page");
            }
            final int offset;
            try {
                offset = Integer.parseInt(page.getId());
            } catch (final NumberFormatException e) {
                throw new ParsingException("Invalid Rumble Live offset", e);
            }
            if (offset < 0 || !page.getId().equals(Utils.getQueryValue(url, "offset"))) {
                throw new ParsingException("Invalid Rumble Live continuation");
            }
            final JsonObject data = RumbleApi.fetchData(getDownloader(), page.getUrl(),
                    getExtractorLocalization());
            return extractLiveItems(data, page, offset);
        }

        doc = RumbleParsingHelper.fetchParseValidate(getDownloader(),
                page.getUrl(), RumbleParsingHelper.getMinimalHeaders(),
                NewPipe.getPreferredLocalization());

        return sharedTrendingAndChannelCode.extractAndGetStreamInfoItemsFromPage(doc);
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        if (RumbleTrendingLinkHandlerFactory.LIVE.equals(getId())) {
            if (initialLiveData == null) {
                throw new ParsingException("Rumble Live has not been fetched");
            }
            return extractLiveItems(initialLiveData, new Page(createLiveEndpoint(0), "0"), 0);
        }
        return sharedTrendingAndChannelCode.extractAndGetStreamInfoItemsFromPage(doc);
    }

    private InfoItemsPage<StreamInfoItem> extractLiveItems(final JsonObject data, final Page page,
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
        final Page next = items.isEmpty() ? null
                : new Page(page.getUrl().replaceFirst("([?&]offset=)[0-9]+(?=&|$)",
                        "$1" + nextOffset), String.valueOf(nextOffset));
        return new InfoItemsPage<>(collector, next);
    }

    private static String createLiveEndpoint(final int offset) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("api", "8");
        parameters.put("front", "0");
        parameters.put("options", "video.full");
        parameters.put("offset", String.valueOf(offset));
        parameters.put("limit", "20");
        return RumbleApi.serviceUrl("video_collection.live", parameters);
    }
}
