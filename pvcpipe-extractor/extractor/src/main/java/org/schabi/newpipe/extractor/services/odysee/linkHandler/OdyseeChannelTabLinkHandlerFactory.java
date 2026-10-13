package org.schabi.newpipe.extractor.services.odysee.linkHandler;

import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.UnsupportedTabException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class OdyseeChannelTabLinkHandlerFactory extends ListLinkHandlerFactory {

    // In the order the tabs are shown.
    private static final Map<FilterItem, String> TAB2URLSUFFIX;

    static {
        final Map<FilterItem, String> tabs = new LinkedHashMap<>();
        tabs.put(ChannelTabs.VIDEOS, "");
        tabs.put(ChannelTabs.LIVESTREAMS, "?view=live");
        tabs.put(ChannelTabs.PLAYLISTS, "?view=playlists");
        TAB2URLSUFFIX = Collections.unmodifiableMap(tabs);
    }

    private static final OdyseeChannelTabLinkHandlerFactory INSTANCE =
            new OdyseeChannelTabLinkHandlerFactory();

    private OdyseeChannelTabLinkHandlerFactory() {
    }

    public static OdyseeChannelTabLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    @Nonnull
    public static Map<FilterItem, String> getTab2UrlSuffixes() {
        return TAB2URLSUFFIX;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        return OdyseeChannelLinkHandlerFactory.getInstance().getId(url);
    }

    @Override
    public String getUrl(final String id,
                         @Nonnull final List<FilterItem> contentFilter,
                         @Nullable final List<FilterItem> sortFilter) throws ParsingException {
        final FilterItem tab = contentFilter.get(0);
        if (!TAB2URLSUFFIX.containsKey(tab)) {
            throw new UnsupportedTabException(tab);
        }
        return OdyseeChannelLinkHandlerFactory.getInstance().getUrl(id) + TAB2URLSUFFIX.get(tab);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return OdyseeChannelLinkHandlerFactory.getInstance().onAcceptUrl(url);
    }
}
