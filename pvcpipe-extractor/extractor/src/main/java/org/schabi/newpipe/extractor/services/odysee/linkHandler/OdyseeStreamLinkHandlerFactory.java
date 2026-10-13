package org.schabi.newpipe.extractor.services.odysee.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;

public final class OdyseeStreamLinkHandlerFactory extends LinkHandlerFactory {

    private static final OdyseeStreamLinkHandlerFactory INSTANCE =
            new OdyseeStreamLinkHandlerFactory();

    private OdyseeStreamLinkHandlerFactory() {
    }

    public static OdyseeStreamLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        final OdyseeUrl parsed = OdyseeUrl.parse(url);
        if (parsed.getType() != OdyseeUrl.Type.STREAM) {
            throw new ParsingException("Not an Odysee video URL: " + url);
        }
        return parsed.getId();
    }

    @Override
    public String getUrl(final String id) {
        return OdyseeUrl.toWebUrl(id);
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
