package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.videos.Videos;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("offline")
class BitchuteVideoInfoItemExtractorTest {
    @Test
    void mapsVideoAndChannelMetadata() throws Exception {
        final BitchuteVideoInfoItemExtractor extractor = createExtractor(video());

        assertEquals(StreamType.VIDEO_STREAM, extractor.getStreamType());
        assertFalse(extractor.isAd());
        assertFalse(extractor.isUploaderVerified());
        assertEquals(90, extractor.getDuration());
        assertEquals(99, extractor.getViewCount());
        assertEquals("Example video", extractor.getName());
        assertEquals("https://www.bitchute.com/video/Example0001/", extractor.getUrl());
        assertEquals("Example Channel", extractor.getUploaderName());
        assertEquals("https://www.bitchute.com/channel/example/", extractor.getUploaderUrl());
        assertEquals("2026-09-17T12:30:00+02:00", extractor.getTextualUploadDate());
        assertEquals(Instant.parse("2026-09-17T10:30:00Z"),
                extractor.getUploadDate().getInstant());

        assertEquals(1, extractor.getThumbnails().size());
        final Image thumbnail = extractor.getThumbnails().get(0);
        assertEquals("https://cdn.example/video.jpg", thumbnail.getUrl());
        assertEquals(Image.HEIGHT_UNKNOWN, thumbnail.getHeight());
        assertEquals(Image.WIDTH_UNKNOWN, thumbnail.getWidth());
        assertEquals(Image.ResolutionLevel.UNKNOWN, thumbnail.getEstimatedResolutionLevel());
    }

    @Test
    void acceptsDateOnlyUploadDates() throws Exception {
        final JsonObject video = video();
        video.put("date_published", "2026-09-17");
        assertEquals(Instant.parse("2026-09-17T00:00:00Z"),
                createExtractor(video).getUploadDate().getInstant());
    }

    @Test
    void preservesDurationAndDateParsingErrors() throws Exception {
        final JsonObject video = video();
        video.put("duration", "invalid");
        video.put("date_published", "invalid");
        final BitchuteVideoInfoItemExtractor extractor = createExtractor(video);

        assertThrows(ParsingException.class, extractor::getDuration);
        assertThrows(ParsingException.class, extractor::getUploadDate);
    }

    private static BitchuteVideoInfoItemExtractor createExtractor(final JsonObject video) {
        return new BitchuteVideoInfoItemExtractor(new Videos(video));
    }

    private static JsonObject video() throws Exception {
        return JsonParser.object().from("{\"channel\":{"
                + "\"channel_name\":\"Example Channel\",\"channel_url\":\"/channel/example/\"},"
                + "\"date_published\":\"2026-09-17T12:30:00+02:00\","
                + "\"duration\":\"00:01:30\",\"view_count\":99,"
                + "\"video_name\":\"Example video\",\"video_url\":\"/video/Example0001/\","
                + "\"thumbnail_url\":\"https://cdn.example/video.jpg\"}");
    }
}
