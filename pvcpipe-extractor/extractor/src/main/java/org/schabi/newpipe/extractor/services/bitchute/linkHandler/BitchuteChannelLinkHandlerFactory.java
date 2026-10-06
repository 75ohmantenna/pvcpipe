package org.schabi.newpipe.extractor.services.bitchute.linkHandler;

import org.schabi.newpipe.extractor.search.filter.FilterItem;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteConstants;
import org.schabi.newpipe.extractor.utils.Utils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BitchuteChannelLinkHandlerFactory extends ListLinkHandlerFactory {

    // The SPA reserves these root routes; all other single segments can be channel slugs.
    private static final Set<String> RESERVED_ROOT_PATHS = Set.of(
            "1776", "accounts", "affiliate", "all", "analytics", "bitseek", "careers",
            "category", "channel", "channelsetting", "channels", "chat", "comments",
            "communication", "content", "feedback", "founder", "fresh", "hashtag",
            "interface", "live", "livenow", "logout", "manage", "manage_comment",
            "memberpicked", "membership", "monetization", "notifications", "personalinfo",
            "playlist", "popchat", "popular", "profile", "profilecontent", "referrals",
            "search", "settings", "shorts", "socialmedia", "subscribed", "subscriptions",
            "subtitles", "suggested", "trending", "ukregulation", "update_playlist",
            "update_video", "upload_video", "video", "vpn", "api", "embed", "torrent");

    private static final BitchuteChannelLinkHandlerFactory INSTANCE =
            new BitchuteChannelLinkHandlerFactory();

    public static BitchuteChannelLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getId(final String urlString) throws ParsingException, IllegalArgumentException {
        final URL url;
        try {
            url = Utils.stringToURL(urlString);
        } catch (final MalformedURLException e) {
            throw new ParsingException("The given URL is not valid", e);
        }

        if (!Utils.isHTTP(url) || !Set.of("www.bitchute.com", "bitchute.com", "old.bitchute.com")
                .contains(url.getHost().toLowerCase(Locale.ROOT))) {
            throw new ParsingException("URL is not hosted by BitChute: " + urlString);
        }

        String path = url.getPath();

        if (!path.isEmpty()) {
            //remove leading "/"
            path = path.substring(1);
        }

        try {
            final String[] splitPath = path.split("/", 0);
            if (splitPath[0].equalsIgnoreCase("channel")) {
                return validateId(splitPath[1]);
            }
            if (splitPath.length == 1
                    && !RESERVED_ROOT_PATHS.contains(splitPath[0].toLowerCase(Locale.ROOT))) {
                return validateId(splitPath[0]);
            }
        } catch (final ArrayIndexOutOfBoundsException e) {
            throw new ParsingException("Error getting ID");
        }
        throw new ParsingException("Error url not suitable: " + urlString);
    }

    private String validateId(final String id) throws ParsingException {
        if (id != null && !".".equals(id) && !"..".equals(id)
                && id.matches("[a-zA-Z0-9._~-]+")) {
            return id;
        } else {
            throw new ParsingException("Id is not suitable: " + id);
        }
    }

    @Override
    public String getUrl(final String id, final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter)
            throws ParsingException {
        return BitchuteConstants.BASE_URL_CHANNEL + "/" + validateId(id);
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
