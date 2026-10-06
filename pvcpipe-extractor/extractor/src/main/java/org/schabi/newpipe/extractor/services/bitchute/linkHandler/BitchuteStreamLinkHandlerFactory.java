package org.schabi.newpipe.extractor.services.bitchute.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteConstants;
import org.schabi.newpipe.extractor.utils.Utils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import java.util.Set;

public class BitchuteStreamLinkHandlerFactory extends LinkHandlerFactory {

    private static final Set<String> SUPPORTED_HOSTS = Set.of(
            "bitchute.com", "www.bitchute.com", "old.bitchute.com");

    private static final BitchuteStreamLinkHandlerFactory INSTANCE =
            new BitchuteStreamLinkHandlerFactory();

    public static BitchuteStreamLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    private static String assertId(final String id) throws ParsingException {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{11,}")) {
            throw new ParsingException("Given string is not a Bitchute Video ID");
        }
        return id;
    }

    @Override
    public String getUrl(final String id) {
        return BitchuteConstants.BASE_URL_VIDEO + "/" + id;
    }

    @Override
    public String getId(final String urlString) throws ParsingException, IllegalArgumentException {
        final URL url;
        try {
            url = Utils.stringToURL(urlString);
        } catch (final MalformedURLException e) {
            throw new ParsingException("The given URL is not valid", e);
        }

        if (!Utils.isHTTP(url)
                || !SUPPORTED_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))) {
            throw new ParsingException("URL is not hosted by BitChute: " + urlString);
        }

        final String[] pathSegments = url.getPath().split("/");
        if (pathSegments.length == 5 && "api".equals(pathSegments[1])
                && "beta9".equals(pathSegments[2])
                && ("embed".equals(pathSegments[3]) || "selfembed".equals(pathSegments[3]))) {
            return assertId(pathSegments[4]);
        }
        if (pathSegments.length >= 3
                && ("video".equalsIgnoreCase(pathSegments[1])
                || "embed".equalsIgnoreCase(pathSegments[1]))) {
            return assertId(pathSegments[2]);
        }
        if (pathSegments.length >= 4 && "torrent".equalsIgnoreCase(pathSegments[1])) {
            return assertId(pathSegments[2]);
        }
        throw new ParsingException("Unsupported BitChute video URL: " + urlString);
    }

    @Override
    public boolean onAcceptUrl(final String url) throws ParsingException {
        try {
            getId(url);
            return true;
        } catch (final ParsingException e) {
            return false;
        }
    }
}
