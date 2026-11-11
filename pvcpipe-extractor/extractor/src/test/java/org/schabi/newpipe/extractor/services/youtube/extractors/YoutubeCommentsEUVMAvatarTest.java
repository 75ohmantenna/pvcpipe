package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class YoutubeCommentsEUVMAvatarTest {
    @Test
    void normalSourcesTakePrecedenceOverFallback() throws Exception {
        final var extractor = extractor("{\"avatar\":{\"image\":{\"sources\":[{"
                + "\"url\":\"https://example.com/normal\",\"height\":48,\"width\":64}]}},"
                + "\"author\":{\"avatarThumbnailUrl\":\"https://example.com/fallback=s88\"}}");
        final Image image = extractor.getUploaderAvatars().get(0);
        assertEquals("https://example.com/normal", image.getUrl());
        assertEquals(48, image.getHeight());
        assertEquals(64, image.getWidth());
    }

    @Test
    void authorFallbackProvidesSquareDimensionsAndThumbnails() throws Exception {
        final String url = "https://example.com/avatar=s176-c-k-c0x00ffffff-no-rj";
        final var extractor = extractor("{\"author\":{\"avatarThumbnailUrl\":\"" + url + "\"}}");
        final List<Image> images = extractor.getUploaderAvatars();
        assertEquals(1, images.size());
        assertEquals(url, images.get(0).getUrl());
        assertEquals(176, images.get(0).getHeight());
        assertEquals(176, images.get(0).getWidth());
        assertEquals(Image.ResolutionLevel.MEDIUM, images.get(0).getEstimatedResolutionLevel());
        assertEquals(url, extractor.getThumbnails().get(0).getUrl());
    }

    @Test
    void emptySourcesAllowFallbackAndTerminalSize() throws Exception {
        final var extractor = extractor("{\"avatar\":{\"image\":{\"sources\":[]}},"
                + "\"author\":{\"avatarThumbnailUrl\":\"https://example.com/avatar=s88\"}}");
        assertEquals(88, extractor.getUploaderAvatars().get(0).getHeight());
    }

    @Test
    void unusableSourcesDoNotSuppressAValidFallback() throws Exception {
        for (final String sources : List.of("[{}]", "[{\"url\":\"\"}]",
                "[{\"url\":null}]", "[null,42,{}]")) {
            final String url = "https://example.com/avatar=s88";
            final var extractor = extractor("{\"avatar\":{\"image\":{\"sources\":"
                    + sources + "}},\"author\":{\"avatarThumbnailUrl\":\"" + url + "\"}}");
            assertEquals(1, extractor.getUploaderAvatars().size());
            assertEquals(url, extractor.getUploaderAvatars().get(0).getUrl());
        }
    }

    @Test
    void invalidOrMissingSizesKeepTheAvatarWithUnknownDimensions() throws Exception {
        for (final String suffix : List.of("", "=s", "=snope", "=s-2", "=s0", "=s48oops",
                "=s999999999999999999999999999999")) {
            final String url = "https://example.com/avatar" + suffix;
            final var extractor = extractor("{\"author\":{\"avatarThumbnailUrl\":\""
                    + url + "\"}}");
            final Image image = extractor.getUploaderAvatars().get(0);
            assertEquals(url, image.getUrl());
            assertEquals(Image.HEIGHT_UNKNOWN, image.getHeight(), suffix);
            assertEquals(Image.WIDTH_UNKNOWN, image.getWidth(), suffix);
            assertEquals(Image.ResolutionLevel.UNKNOWN, image.getEstimatedResolutionLevel());
        }
    }

    @Test
    void missingNullOrEmptyAvatarUrlsProduceNoImages() throws Exception {
        for (final String payload : List.of("{}", "{\"author\":{}}",
                "{\"author\":{\"avatarThumbnailUrl\":null}}",
                "{\"author\":{\"avatarThumbnailUrl\":\"\"}}")) {
            assertTrue(extractor(payload).getUploaderAvatars().isEmpty());
        }
    }

    private static YoutubeCommentsEUVMInfoItemExtractor extractor(final String payload)
            throws Exception {
        return new YoutubeCommentsEUVMInfoItemExtractor(new JsonObject(), null,
                JsonParser.object().from(payload), new JsonObject(),
                "https://www.youtube.com/watch?v=fixtureVid1",
                TimeAgoPatternsManager.getTimeAgoParserFor(Localization.DEFAULT));
    }
}
