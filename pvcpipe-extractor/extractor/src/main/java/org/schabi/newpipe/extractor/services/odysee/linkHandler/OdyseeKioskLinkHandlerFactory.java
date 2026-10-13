package org.schabi.newpipe.extractor.services.odysee.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OdyseeKioskLinkHandlerFactory extends ListLinkHandlerFactory {

    public static final String FEATURED = "Featured";
    public static final String TRENDING = "Trending";
    public static final String LIVE = "live";

    // The website addresses of the pages the kiosks are made from.
    private static final Map<String, String> ID2URL = new LinkedHashMap<>();

    static {
        ID2URL.put(FEATURED, OdyseeUrl.BASE_URL + "/$/featured");
        ID2URL.put(TRENDING, OdyseeUrl.BASE_URL + "/$/wildwest");
        ID2URL.put(LIVE, OdyseeUrl.BASE_URL + "/$/livenow");
    }

    private static final OdyseeKioskLinkHandlerFactory INSTANCE =
            new OdyseeKioskLinkHandlerFactory();

    private OdyseeKioskLinkHandlerFactory() {
    }

    public static OdyseeKioskLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        for (final Map.Entry<String, String> kiosk : ID2URL.entrySet()) {
            if (kiosk.getValue().equals(url)) {
                return kiosk.getKey();
            }
        }
        throw new ParsingException("Not an Odysee kiosk URL: " + url);
    }

    @Override
    public String getUrl(final String id, final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        final String url = ID2URL.get(id);
        if (url == null) {
            throw new ParsingException("Not an Odysee kiosk: " + id);
        }
        return url;
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return ID2URL.containsValue(url);
    }
}
