package org.schabi.newpipe.extractor.services.kick;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.Image;

@Tag("offline")
class KickImagesTest {
    @ParameterizedTest
    @CsvSource({"160,LOW", "360,MEDIUM", "480,MEDIUM", "720,HIGH", "1080,HIGH"})
    void liveThumbnailPathsIdentifyHeightButNotWidth(final int height,
                                                    final Image.ResolutionLevel quality) {
        final Image image = image("https://images.kick.com/video_thumbnails/channel/broadcast/"
                + height + ".webp?token=kept");
        assertEquals(height, image.getHeight());
        assertEquals(Image.WIDTH_UNKNOWN, image.getWidth());
        assertEquals(quality, image.getEstimatedResolutionLevel());
    }

    @Test
    void avatarConversionsDoNotInventSquareDimensions() {
        final String base = "https://files.kick.com/images/user/42/profile_image/conversion/avatar-";
        final Image full = image(base + "fullsize.webp");
        assertEquals(350, full.getWidth());
        assertEquals(Image.HEIGHT_UNKNOWN, full.getHeight());
        final Image medium = image(base + "medium.webp");
        assertEquals(150, medium.getWidth());
        assertEquals(Image.HEIGHT_UNKNOWN, medium.getHeight());
    }

    @Test
    void currentVodPreviewHasAnEstimatedQualityWithoutInventedDimensions() {
        final Image image = image("https://web.kick.com/api/v1/videos/"
                + "11111111-1111-4111-8111-111111111111/thumbnails/1234.jpeg?cwt=kept");
        assertEquals(Image.ResolutionLevel.HIGH, image.getEstimatedResolutionLevel());
        assertEquals(Image.WIDTH_UNKNOWN, image.getWidth());
        assertEquals(Image.HEIGHT_UNKNOWN, image.getHeight());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://images.kick.com.evil/video_thumbnails/channel/broadcast/720.webp",
            "https://images.kick.com/video_thumbnails/channel/broadcast/original.webp?size=720",
            "https://images.kick.com/video_thumbnails/channel/broadcast/999999999999999.webp",
            "https://files.kick.com/images/user/42/profile_image/original",
            "https://files.kick.com/images/user/42/banner_image/conversion/avatar-fullsize.webp",
            "https://web.kick.com.evil/api/v1/videos/id/thumbnails/1234.jpeg"
    })
    void unrelatedImagesKeepHonestUnknownDimensions(final String url) {
        final Image image = image(url);
        assertEquals(Image.WIDTH_UNKNOWN, image.getWidth());
        assertEquals(Image.HEIGHT_UNKNOWN, image.getHeight());
        assertEquals(Image.ResolutionLevel.UNKNOWN, image.getEstimatedResolutionLevel());
    }

    private static Image image(final String url) {
        return KickApi.images(url).get(0);
    }
}
