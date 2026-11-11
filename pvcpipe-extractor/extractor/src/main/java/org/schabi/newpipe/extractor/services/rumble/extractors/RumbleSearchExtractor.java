package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.services.rumble.search.filter.RumbleFilters;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

public class RumbleSearchExtractor extends SearchExtractor {
    RumbleCommonCodeTrendingAndSearching rumbleCommonCodeTrendingAndSearching;
    private Document doc;
    private JsonObject apiData;
    private String apiUrl;

    public RumbleSearchExtractor(final StreamingService service,
                                 final SearchQueryHandler linkHandler) {
        super(service, linkHandler);
        rumbleCommonCodeTrendingAndSearching = new RumbleCommonCodeTrendingAndSearching(
                new RumbleSearchTrendingItemsExtractorImpl());
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        doc = null;
        apiData = null;
        apiUrl = getApiSearchUrl();
        if (apiUrl == null) {
            doc = RumbleParsingHelper.fetchParseValidate(downloader, getUrl());
        } else {
            apiData = RumbleApi.fetchData(downloader, apiUrl, getExtractorLocalization());
        }
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
    public List<MetaInfo> getMetaInfo() throws ParsingException {
        return Collections.emptyList();
    }

    @Nonnull
    @Override
    public InfoItemsPage<InfoItem> getInitialPage() throws IOException, ExtractionException {
        if (apiData != null) {
            return extractApiPage(apiData, apiUrl, null);
        }
        if (doc == null) {
            throw new ParsingException("Rumble search has not been fetched");
        }
        return extractAndGetInfoItemsFromPage();
    }

    private InfoItemsPage<InfoItem> extractAndGetInfoItemsFromPage()
            throws ExtractionException {
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());

        final int searchType = getSearchType();
        final int infoItemsListSize;

        if (searchType == RumbleFilters.ID_CF_MAIN_VIDEOS) {
            final List<StreamInfoItemExtractor> infoItemsList =
                    rumbleCommonCodeTrendingAndSearching
                            .getSearchOrTrendingResultsItemList(doc);
            for (final StreamInfoItemExtractor infoItemExtractor : infoItemsList) {
                collector.commit(infoItemExtractor);
            }
            infoItemsListSize = infoItemsList.size();
        } else if (searchType == RumbleFilters.ID_CF_MAIN_CHANNELS) {
            final List<ChannelInfoItemExtractor> infoItemsList =
                    extractChannelsFromSearchResult();
            for (final ChannelInfoItemExtractor infoItemExtractor : infoItemsList) {
                collector.commit(infoItemExtractor);
            }
            // every channel the page lists counts, also one that could not be read: a page
            // of unreadable channels still has a next page
            infoItemsListSize = infoItemsList.size();
        } else {
            throw new ParsingException("Unsupported Rumble search content filter");
        }

        final Page nextPage =
                rumbleCommonCodeTrendingAndSearching.getNewPageIfThereAreMoreThanOnePageResults(
                        infoItemsListSize, doc);

        return new InfoItemsPage<>(collector, nextPage);
    }

    private int getSearchType() throws ParsingException {
        final List<FilterItem> content = getLinkHandler().getContentFilters();
        if (isNullOrEmpty(content)) {
            return RumbleFilters.ID_CF_MAIN_VIDEOS;
        }
        if (content.size() != 1 || content.get(0) == null) {
            throw new ParsingException("Invalid Rumble search content filters");
        }
        final int type = content.get(0).getIdentifier();
        if (type != RumbleFilters.ID_CF_MAIN_VIDEOS
                && type != RumbleFilters.ID_CF_MAIN_CHANNELS) {
            throw new ParsingException("Unsupported Rumble search content filter: " + type);
        }
        return type;
    }

    private String getApiSearchUrl() throws ParsingException {
        final int type = getSearchType();
        int sort = RumbleFilters.ID_SF_SORT_BY_RELEVANCE;
        int date = RumbleFilters.ID_SF_UPLOAD_DATE_ALL;
        int duration = RumbleFilters.ID_SF_DURATION_ALL;
        final List<FilterItem> filters = getLinkHandler().getSortFilter();
        if (filters != null) {
            for (final FilterItem filter : filters) {
                if (filter == null) {
                    throw new ParsingException("Invalid Rumble search filter");
                }
                final int id = filter.getIdentifier();
                if (id >= RumbleFilters.ID_SF_SORT_BY_RELEVANCE
                        && id <= RumbleFilters.ID_SF_SORT_BY_VIEWS) {
                    sort = id;
                } else if (id >= RumbleFilters.ID_SF_UPLOAD_DATE_ALL
                        && id <= RumbleFilters.ID_SF_UPLOAD_DATE_LAST_YEAR) {
                    date = id;
                } else if (id >= RumbleFilters.ID_SF_DURATION_ALL
                        && id <= RumbleFilters.ID_SF_DURATION_SHORT) {
                    duration = id;
                } else {
                    throw new ParsingException("Unsupported Rumble search filter: " + id);
                }
            }
        }
        if (date != RumbleFilters.ID_SF_UPLOAD_DATE_ALL
                || type == RumbleFilters.ID_CF_MAIN_CHANNELS
                && (sort != RumbleFilters.ID_SF_SORT_BY_RELEVANCE
                || duration != RumbleFilters.ID_SF_DURATION_ALL)) {
            return null;
        }
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("api", "8");
        parameters.put("query", getSearchString());
        if (type == RumbleFilters.ID_CF_MAIN_VIDEOS) {
            parameters.put("options", "video.full");
            switch (sort) {
                case RumbleFilters.ID_SF_SORT_BY_MOST_RECENT:
                    parameters.put("sort", "date");
                    break;
                case RumbleFilters.ID_SF_SORT_BY_RUMBLES:
                    parameters.put("sort", "rumbles");
                    break;
                case RumbleFilters.ID_SF_SORT_BY_VIEWS:
                    parameters.put("sort", "views");
                    break;
                default:
                    break;
            }
            if (duration != RumbleFilters.ID_SF_DURATION_ALL) {
                parameters.put("duration",
                        duration == RumbleFilters.ID_SF_DURATION_LONG ? "long" : "short");
            }
        }
        parameters.put("offset", "0");
        parameters.put("limit", "20");
        return RumbleApi.serviceUrl(type == RumbleFilters.ID_CF_MAIN_VIDEOS
                ? "media.search" : "video_collection.search", parameters);
    }

    private InfoItemsPage<InfoItem> extractApiPage(final JsonObject data, final String url,
                                                  @Nullable final List<String> previousCreatorUrls)
            throws ParsingException {
        final Map<String, String> parameters = queryParameters(url);
        final String name = parameters.remove("name");
        final boolean videos = "media.search".equals(name);
        if (!videos && !"video_collection.search".equals(name)) {
            throw new ParsingException("Unsupported Rumble search API page");
        }
        final int offset = apiOffset(parameters);
        final JsonArray items = JsonUtils.getArray(data, "items");
        final boolean hasMore = videos && JsonUtils.getBoolean(data, "has_more");
        if (hasMore && items.isEmpty()) {
            throw new ParsingException("Rumble search has_more without items");
        }
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        for (final Object raw : items) {
            if (!(raw instanceof JsonObject)) {
                collector.commit(new UnreadableChannel(
                        new ParsingException("Invalid Rumble search item")));
                continue;
            }
            final JsonObject item = (JsonObject) raw;
            if (videos) {
                if (item.get("object_type") == null
                        || "video".equals(item.get("object_type"))) {
                    collector.commit(new RumbleJsonStreamInfoItemExtractor(item));
                }
            } else {
                collector.commit(new RumbleJsonChannelInfoItemExtractor(item));
            }
        }
        final List<String> creatorUrls;
        if (videos || !collector.getErrors().isEmpty()
                || collector.getItems().size() != items.size()) {
            creatorUrls = null;
        } else {
            final TreeSet<String> identities = new TreeSet<>();
            for (final InfoItem creator : collector.getItems()) {
                identities.add(creator.getUrl());
            }
            creatorUrls = new ArrayList<>(identities);
            // Some creator searches ignore the advanced offset and repeat the entire page.
            // Carry identities in Page so this also works with a fresh extractor instance.
            // Only infer repetition from fully readable pages, not partial overlap.
            if (!creatorUrls.isEmpty() && creatorUrls.equals(previousCreatorUrls)) {
                return new InfoItemsPage<>(Collections.emptyList(), null, collector.getErrors());
            }
        }
        final long nextOffset = (long) offset + items.size();
        final boolean continuation = videos ? hasMore : !items.isEmpty() && nextOffset < 10000;
        Page next = null;
        if (continuation) {
            parameters.put("offset", Long.toString(nextOffset));
            next = new Page(RumbleApi.serviceUrl(name, parameters), Long.toString(nextOffset),
                    creatorUrls, null, null);
        }
        return new InfoItemsPage<>(collector, next);
    }

    private static Map<String, String> queryParameters(final String url) throws ParsingException {
        final Map<String, String> parameters = new LinkedHashMap<>();
        try {
            final String query = URI.create(url).getRawQuery();
            if (query != null) {
                for (final String pair : query.split("&")) {
                    final String[] parts = pair.split("=", 2);
                    parameters.put(Utils.decodeUrlUtf8(parts[0]),
                            parts.length == 2 ? Utils.decodeUrlUtf8(parts[1]) : "");
                }
            }
        } catch (final IllegalArgumentException e) {
            throw new ParsingException("Invalid Rumble search page URL", e);
        }
        return parameters;
    }

    private static int apiOffset(final Map<String, String> parameters) throws ParsingException {
        try {
            final int offset = Integer.parseInt(parameters.get("offset"));
            if (offset < 0) {
                throw new NumberFormatException("Offset out of range");
            }
            return offset;
        } catch (final NumberFormatException e) {
            throw new ParsingException("Invalid Rumble search page offset", e);
        }
    }

    private List<ChannelInfoItemExtractor> extractChannelsFromSearchResult() {
        final Elements elements = doc.select("article");
        final List<ChannelInfoItemExtractor> extractors = new ArrayList<>();

        for (final Element element : elements) {
            if (element.selectFirst("div[class*=media-subscribe-and-notify], "
                    + "a[href*='/c/']:has(h3), a[href*='/user/']:has(h3)") == null) {
                continue;
            }
            try {
                extractors.add(new RumbleChannelSearchInfoItemExtractor(element, doc));
            } catch (final ParsingException e) {
                extractors.add(new UnreadableChannel(e));
            } catch (final RuntimeException e) {
                extractors.add(new UnreadableChannel(
                        new ParsingException("Could not read a channel of the search", e)));
            }
        }

        return extractors;
    }

    /**
     * Stands in for a channel whose markup could not be read. The channel extractor reads
     * everything when it is created, so its failure would otherwise end the whole page. The
     * collector asks this one for its values, gets the error, records it and goes on to the
     * next channel.
     */
    private static final class UnreadableChannel implements ChannelInfoItemExtractor {
        private final ParsingException error;

        private UnreadableChannel(final ParsingException error) {
            this.error = error;
        }

        @Override
        public String getName() throws ParsingException {
            throw error;
        }

        @Override
        public String getUrl() throws ParsingException {
            throw error;
        }

        @Nonnull
        @Override
        public List<Image> getThumbnails() throws ParsingException {
            throw error;
        }

        @Override
        public String getDescription() throws ParsingException {
            throw error;
        }

        @Override
        public long getSubscriberCount() throws ParsingException {
            throw error;
        }

        @Override
        public long getStreamCount() throws ParsingException {
            throw error;
        }

        @Override
        public boolean isVerified() throws ParsingException {
            throw error;
        }
    }

    @Override
    public InfoItemsPage<InfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        doc = null;
        apiData = null;
        apiUrl = null;
        if (page == null || isNullOrEmpty(page.getUrl())) {
            throw new ParsingException("Invalid Rumble search page");
        }
        final Map<String, String> parameters = queryParameters(page.getUrl());
        if ("media.search".equals(parameters.get("name"))
                || "video_collection.search".equals(parameters.get("name"))) {
            final int offset = apiOffset(parameters);
            if ("video_collection.search".equals(parameters.get("name")) && offset > 10000) {
                throw new ParsingException("Rumble creator search offset exceeds 10000");
            }
            return extractApiPage(RumbleApi.fetchData(getDownloader(), page.getUrl(),
                    getExtractorLocalization()), page.getUrl(), page.getIds());
        }
        if ("/service.php".equals(URI.create(page.getUrl()).getPath())) {
            throw new ParsingException("Unsupported Rumble search API page");
        }
        getApiSearchUrl();
        doc = RumbleParsingHelper.fetchParseValidate(getDownloader(), page.getUrl());
        return extractAndGetInfoItemsFromPage();
    }
}
