package org.schabi.newpipe.extractor.services.odysee.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;

import java.util.List;

public final class OdyseePlaylistLinkHandlerFactory extends ListLinkHandlerFactory {

    private static final OdyseePlaylistLinkHandlerFactory INSTANCE =
            new OdyseePlaylistLinkHandlerFactory();

    private OdyseePlaylistLinkHandlerFactory() {
    }

    public static OdyseePlaylistLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        final OdyseeUrl parsed = OdyseeUrl.parse(url);
        if (parsed.getType() != OdyseeUrl.Type.PLAYLIST) {
            throw new ParsingException("Not an Odysee playlist URL: " + url);
        }
        return parsed.getId();
    }

    @Override
    public String getUrl(final String id, final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) {
        return OdyseeUrl.playlistUrl(id);
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
}
