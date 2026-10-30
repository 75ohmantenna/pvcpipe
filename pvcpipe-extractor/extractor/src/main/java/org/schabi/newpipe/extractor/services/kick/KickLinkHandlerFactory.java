package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.UnsupportedTabException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.utils.Utils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class KickLinkHandlerFactory {
    public static final String FEATURED = "Featured";
    public static final LinkHandlerFactory STREAM = new LinkHandlerFactory() {
        @Override
        public String getId(final String url) throws ParsingException {
            return KickUrl.streamId(url);
        }

        @Override
        public String getUrl(final String id) throws ParsingException {
            return KickUrl.streamUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            try {
                getId(url);
                return true;
            } catch (final ParsingException e) {
                return false;
            }
        }
    };
    public static final ListLinkHandlerFactory CHANNEL = new ListLinkHandlerFactory() {
        @Override
        public String getId(final String url) throws ParsingException {
            return KickUrl.channelId(url);
        }

        @Override
        public String getUrl(final String id, @Nonnull final List<FilterItem> content,
                             @Nullable final List<FilterItem> sort) throws ParsingException {
            return KickUrl.BASE + "/" + KickUrl.slug(id) + "/videos";
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            try {
                getId(url);
                return true;
            } catch (final ParsingException e) {
                return false;
            }
        }
    };
    public static final ListLinkHandlerFactory TAB = new ListLinkHandlerFactory() {
        @Override
        public String getId(final String url) throws ParsingException {
            return CHANNEL.getId(url);
        }

        @Override
        public ListLinkHandler fromUrl(final String url, final String baseUrl)
                throws ParsingException {
            final String id = getId(url);
            final URI uri = URI.create(url);
            final String path = uri.getPath();
            final boolean videos = path.endsWith("/videos") || path.endsWith("/videos/")
                    || path.endsWith("/about") || path.endsWith("/about/");
            final boolean clips = path.endsWith("/clips") || path.endsWith("/clips/");
            final FilterItem tab = clips ? ChannelTabs.CLIPS
                    : videos ? ChannelTabs.VIDEOS : ChannelTabs.LIVESTREAMS;
            final List<FilterItem> filters = List.of(tab);
            final List<FilterItem> sort = clips ? clipFilters(uri) : List.of();
            return new ListLinkHandler(url, getUrl(id, filters, sort), id, filters, sort);
        }

        @Override
        public ListLinkHandler fromQuery(final String id, final List<FilterItem> content,
                                         final List<FilterItem> sort) throws ParsingException {
            final List<FilterItem> tabs = content.isEmpty() ? List.of(ChannelTabs.VIDEOS) : content;
            final List<FilterItem> selectedSort = tabs.get(0).equals(ChannelTabs.CLIPS)
                    ? KickClipFilters.fromValues(KickClipFilters.sort(sort),
                            KickClipFilters.time(sort)) : List.of();
            final String slug = KickUrl.slug(id);
            final String url = getUrl(slug, tabs, selectedSort);
            return new ListLinkHandler(url, url, slug, tabs, selectedSort);
        }

        @Override
        public String getUrl(final String id, @Nonnull final List<FilterItem> content,
                             @Nullable final List<FilterItem> sort) throws ParsingException {
            if (content.size() > 1) {
                throw new ParsingException("Kick channel tab requires one tab choice");
            }
            final FilterItem tab = content.isEmpty() ? ChannelTabs.VIDEOS : content.get(0);
            if (!tabs().containsKey(tab)) {
                throw new UnsupportedTabException(tab);
            }
            if (tab.equals(ChannelTabs.CLIPS)) {
                return KickUrl.BASE + "/" + KickUrl.slug(id) + "/clips?sort="
                        + KickClipFilters.sort(sort) + "&range=" + KickClipFilters.time(sort);
            }
            return KickUrl.BASE + "/" + KickUrl.slug(id) + tabs().get(tab);
        }

        @Override
        public boolean onAcceptUrl(final String url) throws ParsingException {
            try {
                fromUrl(url);
                return true;
            } catch (final ParsingException e) {
                return false;
            }
        }
    };
    public static final SearchQueryHandlerFactory SEARCH = new SearchQueryHandlerFactory(
            new KickSearchFilters()) {
        @Override
        public String getUrl(final String query, @Nonnull final List<FilterItem> content,
                             @Nullable final List<FilterItem> sort) {
            return KickUrl.BASE + "/search?query=" + Utils.encodeUrlUtf8(query);
        }
    };
    public static final ListLinkHandlerFactory KIOSK = new ListLinkHandlerFactory() {
        @Override
        public String getId(final String url) throws ParsingException {
            if (!(KickUrl.BASE + "/").equals(url) && !KickUrl.BASE.equals(url)) {
                throw new ParsingException("Not the Kick featured page");
            }
            return FEATURED;
        }

        @Override
        public String getUrl(final String id, @Nonnull final List<FilterItem> content,
                             @Nullable final List<FilterItem> sort) throws ParsingException {
            if (!FEATURED.equals(id)) {
                throw new ParsingException("Unknown Kick kiosk");
            }
            return KickUrl.BASE + "/";
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return KickUrl.BASE.equals(url) || (KickUrl.BASE + "/").equals(url);
        }
    };
    private static final Map<FilterItem, String> TABS;

    static {
        final Map<FilterItem, String> tabs = new LinkedHashMap<>();
        tabs.put(ChannelTabs.VIDEOS, "/videos");
        tabs.put(ChannelTabs.LIVESTREAMS, "");
        tabs.put(ChannelTabs.CLIPS, "/clips?sort=date&range=all");
        TABS = Collections.unmodifiableMap(tabs);
    }

    private KickLinkHandlerFactory() {
    }

    public static Map<FilterItem, String> tabs() {
        return TABS;
    }

    private static List<FilterItem> clipFilters(final URI uri) throws ParsingException {
        String sort = null;
        String range = null;
        final String query = uri.getRawQuery();
        if (query != null) {
            for (final String pair : query.split("&", -1)) {
                final int equals = pair.indexOf('=');
                final String name;
                final String value;
                try {
                    name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals),
                            StandardCharsets.UTF_8);
                    value = URLDecoder.decode(equals < 0 ? "" : pair.substring(equals + 1),
                            StandardCharsets.UTF_8);
                } catch (final IllegalArgumentException e) {
                    throw new ParsingException("Invalid Kick clip filter encoding", e);
                }
                if (name.indexOf('\uFFFD') >= 0 || value.indexOf('\uFFFD') >= 0) {
                    throw new ParsingException("Invalid Kick clip filter encoding");
                }
                if ("sort".equals(name)) {
                    if (sort != null) {
                        throw new ParsingException("Duplicate Kick clip sort order");
                    }
                    sort = "view".equals(value) ? "views" : value;
                } else if ("range".equals(name)) {
                    if (range != null) {
                        throw new ParsingException("Duplicate Kick clip time range");
                    }
                    range = value;
                }
            }
        }
        return KickClipFilters.fromValues(sort, range);
    }

}
