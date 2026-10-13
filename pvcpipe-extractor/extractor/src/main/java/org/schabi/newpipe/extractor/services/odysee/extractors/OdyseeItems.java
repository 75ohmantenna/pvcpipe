package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

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
     * it reposts. Claims that are none of these, such as documents and images, are left out.
     */
    static void commitAll(@Nonnull final MultiInfoItemsCollector items,
                          @Nonnull final List<JsonObject> listedClaims) {
        final List<JsonObject> claims = unwrapped(listedClaims);
        final Map<String, Long> views = OdyseeApi.viewCounts(idsOf(claims, true));
        final Map<String, Long> followers = OdyseeApi.followerCounts(idsOf(claims, false));
        for (final JsonObject claim : claims) {
            final String claimId = claim.getString("claim_id", "");
            if (isListedStream(claim)) {
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
     * Adds the claims that a player can play to a list of videos.
     */
    static void commitStreams(@Nonnull final StreamInfoItemsCollector items,
                              @Nonnull final List<JsonObject> listedClaims) {
        final List<JsonObject> claims = unwrapped(listedClaims);
        commitStreams(items, claims, OdyseeApi.viewCounts(idsOf(claims, true)));
    }

    /**
     * Adds the claims that a player can play to a list of videos.
     *
     * @param viewCounts the number to show as the view count of each claim, by claim id
     */
    static void commitStreams(@Nonnull final StreamInfoItemsCollector items,
                              @Nonnull final List<JsonObject> listedClaims,
                              @Nonnull final Map<String, Long> viewCounts) {
        for (final JsonObject claim : unwrapped(listedClaims)) {
            if (isListedStream(claim)) {
                items.commit(new OdyseeStreamInfoItemExtractor(claim, viewCounts.getOrDefault(
                        claim.getString("claim_id", ""), UNKNOWN_COUNT)));
            }
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
            if (streams ? isListedStream(claim) : OdyseeClaim.isChannel(claim)) {
                ids.add(claim.getString("claim_id", ""));
            }
        }
        return ids;
    }

    // A visitor who is not signed in cannot play what has to be paid for. Such videos are
    // listed, marked as paid, unless the user chose to hide them.
    private static boolean isListedStream(final JsonObject claim) {
        return OdyseeClaim.isPlayable(claim)
                && (OdyseeClaim.availability(claim) == ContentAvailability.AVAILABLE
                || !OdyseeSettings.getInstance()
                .isSettingEnabled(OdyseeSettings.HIDE_PAID_STREAMS));
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
