package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.stream.ContentAvailability;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("offline")
class YoutubePaidAvailabilityTest {
    @Test
    void scheduledMembersOnlyVideoKeepsItsRestriction() throws Exception {
        assertEquals(ContentAvailability.MEMBERSHIP, availability(
                "{\"upcomingEventData\":{},\"badges\":[{\"metadataBadgeRenderer\":{"
                        + "\"style\":\"BADGE_STYLE_TYPE_MEMBERS_ONLY\",\"label\":\"Sólo miembros\"}}]}"));
    }

    @Test
    void scheduledPremiumVideoKeepsItsRestriction() throws Exception {
        assertEquals(ContentAvailability.PAID, availability(
                "{\"upcomingEventData\":{},\"badges\":[{\"metadataBadgeRenderer\":{"
                        + "\"label\":\"Premium\"}}]}"));
    }

    @Test
    void unrestrictedPremiereRemainsUpcoming() throws Exception {
        assertEquals(ContentAvailability.UPCOMING, availability("{\"upcomingEventData\":{}}"));
        assertEquals(ContentAvailability.AVAILABLE, availability("{}"));
    }

    @Test
    void lockupMembersOnlyVideoKeepsItsRestriction() throws Exception {
        try (var input = getClass().getResourceAsStream("/mocks/v1/org/schabi/newpipe/extractor/"
                + "services/youtube/youtubestreaminfoitem/lockupviewmodelchannelmembersonly.json")) {
            final var extractor = new YoutubeStreamInfoItemLockupExtractor(
                    JsonParser.object().from(input), null);
            assertEquals(ContentAvailability.MEMBERSHIP, extractor.getContentAvailability());
        }
    }

    private static ContentAvailability availability(final String json) throws Exception {
        return new YoutubeStreamInfoItemExtractor(JsonParser.object().from(json), null)
                .getContentAvailability();
    }
}
