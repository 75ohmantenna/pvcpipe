package org.schabi.newpipe.extractor.services.bitchute.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteConstants;
import org.schabi.newpipe.extractor.utils.Utils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BitchutePlaylistLinkHandlerFactory extends ListLinkHandlerFactory {
    private static final BitchutePlaylistLinkHandlerFactory INSTANCE =
            new BitchutePlaylistLinkHandlerFactory();
    private static final Set<String> HOSTS = Set.of(
            "bitchute.com", "www.bitchute.com", "old.bitchute.com");

    public static BitchutePlaylistLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    private static String validate(final String id) throws ParsingException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{12}")) {
            throw new ParsingException("Invalid public BitChute playlist id");
        }
        return id;
    }

    @Override
    public String getId(final String address) throws ParsingException {
        try {
            final URL url = Utils.stringToURL(address);
            if (!Utils.isHTTP(url) || !HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))
                    || url.getUserInfo() != null) {
                throw new ParsingException("Not a BitChute playlist URL");
            }
            final String path = url.getPath();
            if (path.matches("/playlist/[A-Za-z0-9_-]{12}/?")) {
                return validate(path.split("/")[2]);
            }
            if ("/video/playlist".equals(path) || "/video/playlist/".equals(path)) {
                return validate(Utils.getQueryValue(url, "playlistId"));
            }
        } catch (final MalformedURLException | RuntimeException e) {
            throw new ParsingException("Invalid BitChute playlist URL", e);
        }
        throw new ParsingException("Not a public BitChute playlist URL");
    }

    @Override
    public String getUrl(final String id, final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        return BitchuteConstants.BASE_URL + "/playlist/" + validate(id) + "/";
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
