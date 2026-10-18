package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.UnsupportedTabException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.BaseSearchFilters;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;
import org.schabi.newpipe.extractor.utils.Utils;

import java.net.URI;

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
            final String path = URI.create(url).getPath();
            final boolean videos = path.endsWith("/videos") || path.endsWith("/videos/")
                    || path.endsWith("/about") || path.endsWith("/about/");
            final FilterItem tab = videos ? ChannelTabs.VIDEOS : ChannelTabs.LIVESTREAMS;
            final List<FilterItem> filters = List.of(tab);
            return new ListLinkHandler(url, getUrl(id, filters, List.of()), id, filters, List.of());
        }

        @Override
        public String getUrl(final String id, @Nonnull final List<FilterItem> content,
                             @Nullable final List<FilterItem> sort) throws ParsingException {
            final FilterItem tab = content.isEmpty() ? ChannelTabs.VIDEOS : content.get(0);
            if (!tabs().containsKey(tab)) {
                throw new UnsupportedTabException(tab);
            }
            return KickUrl.BASE + "/" + KickUrl.slug(id) + tabs().get(tab);
        }

        @Override
        public boolean onAcceptUrl(final String url) throws ParsingException {
            return CHANNEL.onAcceptUrl(url);
        }
    };
    public static final SearchQueryHandlerFactory SEARCH = new SearchQueryHandlerFactory(
            new ChannelFilters()) {
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
        TABS = Collections.unmodifiableMap(tabs);
    }

    private KickLinkHandlerFactory() {
    }

    public static Map<FilterItem, String> tabs() {
        return TABS;
    }

    private static final class ChannelFilters extends BaseSearchFilters {
        @Override
        protected void init() {
            groupsFactory.addFilterItem(
                    new FilterItem(1, LibraryStringIds.SEARCH_FILTERS_CHANNELS));
            addContentFilterGroup(groupsFactory.createFilterGroup(0, null, true, 1, null, 1));
        }

        @Override
        public String evaluateSelectedFilters(@Nullable final String query) {
            return "";
        }
    }
}
