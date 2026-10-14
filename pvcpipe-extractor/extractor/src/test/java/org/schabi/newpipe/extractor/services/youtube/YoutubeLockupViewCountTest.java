package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("offline")
class YoutubeLockupViewCountTest {
    private static YoutubeStreamInfoItemLockupExtractor item(final String parts) throws Exception {
        final JsonObject data = JsonParser.object().from("{\"metadata\":{\"lockupMetadataViewModel\":"
                + "{\"metadata\":{\"contentMetadataViewModel\":{\"metadataRows\":["
                + "{\"metadataParts\":[" + parts + "]}]}}}}}");
        return new YoutubeStreamInfoItemLockupExtractor(data, null) {
            @Override
            public StreamType getStreamType() {
                return StreamType.VIDEO_STREAM;
            }
        };
    }

    private static String text(final String value) {
        return "{\"text\":{\"content\":\"" + value + "\"}}";
    }

    @Test
    void collaborationCreditsDoNotBecomeViews() throws Exception {
        final var item = item(text("Example creator") + "," + text("and 3 more")
                + ",{\"text\":{\"content\":\"2.5K\"},"
                + "\"leadingIcon\":{\"name\":\"PLAY_ARROW_OUTLINED\"}}," + text("2 days ago"));
        assertEquals(2500, item.getViewCount());
        assertEquals("2 days ago", item.getTextualUploadDate());
    }

    @Test
    void oldLayoutStillWorks() throws Exception {
        assertEquals(1200, item(text("1.2K views") + "," + text("2 days ago")).getViewCount());
    }

    @Test
    void unknownIconsDoNotOverrideTheCount() throws Exception {
        assertEquals(1200, item(text("1.2K views")
                + ",{\"text\":{\"content\":\"7\"},\"leadingIcon\":{\"name\":\"OTHER\"}},"
                + text("2 days ago")).getViewCount());
    }

    @Test
    void emptyIconCountFallsBackToLegacyLayout() throws Exception {
        assertEquals(1200, item(text("1.2K views")
                + ",{\"text\":{\"content\":\"\"},\"leadingIcon\":{\"name\":\"PLAY_ARROW_OUTLINED\"}},"
                + text("2 days ago")).getViewCount());
    }
}
