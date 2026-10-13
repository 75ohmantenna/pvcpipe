package org.schabi.newpipe.extractor.services.odysee.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class OdyseeCommentsLinkHandlerFactory extends ListLinkHandlerFactory {

    private static final OdyseeCommentsLinkHandlerFactory INSTANCE =
            new OdyseeCommentsLinkHandlerFactory();

    private OdyseeCommentsLinkHandlerFactory() {
    }

    public static OdyseeCommentsLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        return OdyseeStreamLinkHandlerFactory.getInstance().getId(url);
    }

    @Override
    public String getUrl(final String id,
                         @Nonnull final List<FilterItem> contentFilter,
                         @Nullable final List<FilterItem> sortFilter) {
        return OdyseeStreamLinkHandlerFactory.getInstance().getUrl(id);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return OdyseeStreamLinkHandlerFactory.getInstance().onAcceptUrl(url);
    }
}
