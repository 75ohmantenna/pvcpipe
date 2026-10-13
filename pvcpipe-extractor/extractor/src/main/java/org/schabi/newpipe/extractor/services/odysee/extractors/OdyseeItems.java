package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.InfoItemsCollector;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Turns claims into list items and builds the claim searches the lists are made from.
 */
final class OdyseeItems {

    static final int PAGE_SIZE = 20;
    static final long UNKNOWN_COUNT = -1;

    private OdyseeItems() {
    }

    /**
     * Adds claims to a list as videos, channels and playlists. A repost counts as the claim
     * it reposts. Claims that are none of these, such as documents and images, are left out,
     * and so are livestream pages: most of them belong to a broadcast that is over.
     */
    static void commitAll(@Nonnull final MultiInfoItemsCollector items,
                          @Nonnull final List<JsonObject> listedClaims) {
        final List<JsonObject> claims = unwrapped(listedClaims);
        final Map<String, Long> views = OdyseeApi.viewCounts(idsOf(claims, true));
        final Map<String, Long> followers = OdyseeApi.followerCounts(idsOf(claims, false));
        for (final JsonObject claim : claims) {
            final String claimId = claim.getString("claim_id", "");
            if (isListedFile(claim)) {
                items.commit(new OdyseeStreamInfoItemExtractor(claim,
                        views.getOrDefault(claimId, UNKNOWN_COUNT)));
            } else if (OdyseeClaim.isChannel(claim)) {
                items.commit(new OdyseeChannelInfoItemExtractor(claim,
                        followers.getOrDefault(claimId, UNKNOWN_COUNT)));
            } else if (OdyseeClaim.isPlaylist(claim)) {
                items.commit(new OdyseePlaylistInfoItemExtractor(claim));
            }
        }
    }

    /**
     * Adds the videos and audio files among the claims to a list of videos.
     */
    static void commitStreams(@Nonnull final StreamInfoItemsCollector items,
                              @Nonnull final List<JsonObject> listedClaims) {
        final List<JsonObject> claims = unwrapped(listedClaims);
        final Map<String, Long> views = OdyseeApi.viewCounts(idsOf(claims, true));
        for (final JsonObject claim : claims) {
            if (isListedFile(claim)) {
                items.commit(new OdyseeStreamInfoItemExtractor(claim,
                        views.getOrDefault(claim.getString("claim_id", ""), UNKNOWN_COUNT)));
            }
        }
    }

    /**
     * Adds livestreams that are on air to a list.
     *
     * @param viewers how many are watching each of them, by claim id. A claim that is not in
     *                this map is not on air and is left out.
     */
    static void commitLivestreams(
            @Nonnull final InfoItemsCollector<?, ? super StreamInfoItemExtractor> items,
            @Nonnull final List<JsonObject> listedClaims,
            @Nonnull final Map<String, Long> viewers) {
        for (final JsonObject claim : unwrapped(listedClaims)) {
            final Long watching = viewers.get(claim.getString("claim_id", ""));
            if (watching != null && OdyseeClaim.isLivestream(claim) && isShown(claim)) {
                items.commit(new OdyseeStreamInfoItemExtractor(claim, watching));
            }
        }
    }

    /**
     * @param page a page that an Odysee list handed out, whose id is a number
     * @param what the kind of list, for the error message
     * @return that number
     */
    static int pageNumber(@Nullable final Page page, @Nonnull final String what)
            throws ParsingException {
        if (page == null || page.getId() == null) {
            throw new ParsingException("Invalid Odysee " + what + " page");
        }
        try {
            return Integer.parseInt(page.getId());
        } catch (final NumberFormatException e) {
            throw new ParsingException("Invalid Odysee " + what + " page: " + page.getId(), e);
        }
    }

    private static List<JsonObject> unwrapped(final List<JsonObject> listedClaims) {
        final List<JsonObject> claims = new ArrayList<>();
        for (final JsonObject listedClaim : listedClaims) {
            claims.add(OdyseeClaim.unwrapRepost(listedClaim));
        }
        return claims;
    }

    // The claims whose counts are worth asking for: the videos that will be listed, or the
    // channels.
    private static List<String> idsOf(final List<JsonObject> claims, final boolean streams) {
        final List<String> ids = new ArrayList<>();
        for (final JsonObject claim : claims) {
            if (streams ? isListedFile(claim) : OdyseeClaim.isChannel(claim)) {
                ids.add(claim.getString("claim_id", ""));
            }
        }
        return ids;
    }

    private static boolean isListedFile(final JsonObject claim) {
        return OdyseeClaim.isPlayable(claim) && !OdyseeClaim.isLivestream(claim)
                && isShown(claim);
    }

    // A visitor who is not signed in cannot play what has to be paid for. Such videos are
    // listed, marked as paid, unless the user chose to hide them.
    private static boolean isShown(final JsonObject claim) {
        return OdyseeClaim.availability(claim) == ContentAvailability.AVAILABLE
                || !OdyseeSettings.getInstance().isSettingEnabled(OdyseeSettings.HIDE_PAID_STREAMS);
    }

    /**
     * @param page the page to ask for, starting at 1
     * @return the parameters of a claim search for videos and audio that have a file
     */
    @Nonnull
    static JsonObject mediaSearch(final int page) {
        final JsonObject params = new JsonObject();
        params.put("claim_type", OdyseeApi.array(List.of("stream")));
        params.put("stream_types", OdyseeApi.array(List.of("video", "audio")));
        params.put("has_source", true);
        params.put("page", page);
        params.put("page_size", PAGE_SIZE);
        return params;
    }

    /**
     * @param page      the page that was just read, starting at 1
     * @param itemCount how many claims it held
     * @return whether the claim search has a further page
     */
    static boolean hasNextPage(final int page, final int itemCount) {
        return itemCount >= PAGE_SIZE && (page + 1) * PAGE_SIZE <= OdyseeApi.MAX_SEARCH_RESULTS;
    }
}
