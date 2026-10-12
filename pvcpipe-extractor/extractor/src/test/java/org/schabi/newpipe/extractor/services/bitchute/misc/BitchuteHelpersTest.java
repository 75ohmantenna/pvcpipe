package org.schabi.newpipe.extractor.services.bitchute.misc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("offline")
class BitchuteHelpersTest {
    private static final String COVER =
            "https://static-3.bitchute.com/live/cover_images/rvqe8YQRCOsL/RR0jWMfCRXJc_640x360.jpg";
    private static final String CHANNEL =
            "https://static-3.bitchute.com/live/channel_images/rvqe8YQRCOsL/oP6TYYxbdVkFoEskr6iaLY9U";

    @Test
    void coverImageTakesItsSizeFromTheFileName() {
        assertImage(COVER, 640, 360, Image.ResolutionLevel.MEDIUM);
        assertImage("https://cdn.example/cover_images/a/b_1280x720.webp",
                1280, 720, Image.ResolutionLevel.HIGH);
    }

    @Test
    void channelImageRenditionsAreMediumResolution() {
        assertImage(CHANNEL + "_small.jpg",
                Image.WIDTH_UNKNOWN, Image.HEIGHT_UNKNOWN, Image.ResolutionLevel.MEDIUM);
        assertImage(CHANNEL + "_large.jpg",
                Image.WIDTH_UNKNOWN, Image.HEIGHT_UNKNOWN, Image.ResolutionLevel.MEDIUM);
    }

    @Test
    void unrecognisedUrlsKeepAnUnknownSize() {
        for (final String url : new String[] {
                "https://cdn.example/video.jpg",
                CHANNEL + "_original.jpg",
                "https://cdn.example/cover_images/a/b_0x360.jpg",
                "https://cdn.example/cover_images/a/b_640x360.jpg?v=2",
                "https://cdn.example/cover_images/small_large.jpg"}) {
            assertImage(url,
                    Image.WIDTH_UNKNOWN, Image.HEIGHT_UNKNOWN, Image.ResolutionLevel.UNKNOWN);
        }
        assertNull(BitchuteHelpers.toImage(null).getUrl());
    }

    private static void assertImage(final String url, final int width, final int height,
                                    final Image.ResolutionLevel level) {
        final Image image = BitchuteHelpers.toImage(url);
        assertEquals(url, image.getUrl());
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        assertEquals(level, image.getEstimatedResolutionLevel());
    }
}
