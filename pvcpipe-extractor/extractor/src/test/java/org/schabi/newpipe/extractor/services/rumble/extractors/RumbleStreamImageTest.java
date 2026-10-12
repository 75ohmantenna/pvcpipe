package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.StreamExtractor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleStreamImageTest {
    private static final String COVER =
            "https://cdn.example/video/s8/1/a/b/c/d/abcd.qR4e-small.jpg";
    private static final String PORTRAIT =
            "https://cdn.example/video/s8/1/a/b/c/d/abcd.aiEB-small.jpg";
    private static final String AVATAR =
            "https://cdn.example/video/z8/a/b/c/d/abcd.baa-Example.png";
    private static final String AVATAR_ID = "0123456789abcdef0123456789abcdef";

    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;

    @BeforeEach
    void saveExtractorConfiguration() {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
    }

    @AfterEach
    void restoreExtractorConfiguration() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @Test
    void coverImageTakesItsSizeFromTheThumbnailList() throws Exception {
        final Image thumbnail = thumbnail("\"i\":\"" + COVER + "\",\"t\":["
                + "{\"i\":\"" + PORTRAIT + "\",\"w\":720,\"h\":1280},"
                + "{\"i\":\"" + COVER + "\",\"w\":1280,\"h\":720}]");

        assertImage(thumbnail, COVER, 1280, 720, Image.ResolutionLevel.HIGH);
    }

    @Test
    void coverImageMissingFromTheThumbnailListKeepsAnUnknownSize() throws Exception {
        final String cover = "\"i\":\"" + COVER + "\"";
        for (final String thumbnails : new String[] {
                "",
                ",\"t\":null",
                ",\"t\":{\"i\":\"" + COVER + "\",\"w\":1280,\"h\":720}",
                ",\"t\":[null,7,\"" + COVER + "\",[],{\"w\":1280,\"h\":720}]",
                ",\"t\":[{\"i\":\"" + PORTRAIT + "\",\"w\":720,\"h\":1280}]"}) {
            assertImage(thumbnail(cover + thumbnails), COVER,
                    Image.WIDTH_UNKNOWN, Image.HEIGHT_UNKNOWN, Image.ResolutionLevel.UNKNOWN);
        }
    }

    @Test
    void unusableSizesAreReportedAsUnknown() throws Exception {
        final String cover = "\"i\":\"" + COVER + "\",\"t\":[{\"i\":\"" + COVER + "\"";
        for (final String size : new String[] {
                "", ",\"w\":0,\"h\":0", ",\"w\":-5,\"h\":-5", ",\"w\":\"1280\",\"h\":\"720\"",
                ",\"w\":null,\"h\":null"}) {
            assertImage(thumbnail(cover + size + "}]"), COVER,
                    Image.WIDTH_UNKNOWN, Image.HEIGHT_UNKNOWN, Image.ResolutionLevel.UNKNOWN);
        }
        // a width alone must not produce a resolution estimate
        assertImage(thumbnail(cover + ",\"w\":1280}]"), COVER,
                1280, Image.HEIGHT_UNKNOWN, Image.ResolutionLevel.UNKNOWN);
    }

    @Test
    void missingCoverImageDoesNotTakeASizeFromTheThumbnailList() throws Exception {
        assertImage(thumbnail("\"t\":[{\"w\":1280,\"h\":720}]"), null,
                Image.WIDTH_UNKNOWN, Image.HEIGHT_UNKNOWN, Image.ResolutionLevel.UNKNOWN);
    }

    @Test
    void uploaderAvatarIsLowResolution() throws Exception {
        final List<Image> avatars = avatars("https://rumble.com/vimage1-with-avatar.html",
                "user-image--img user-image--img--id-" + AVATAR_ID);

        assertEquals(1, avatars.size());
        assertImage(avatars.get(0), AVATAR,
                Image.WIDTH_UNKNOWN, Image.HEIGHT_UNKNOWN, Image.ResolutionLevel.LOW);
    }

    @Test
    void letterAvatarGivesNoImage() throws Exception {
        assertTrue(avatars("https://rumble.com/vimage2-with-letter.html",
                "user-image--letter").isEmpty());
    }

    private static void assertImage(final Image image, final String url, final int width,
                                    final int height, final Image.ResolutionLevel level) {
        assertEquals(url, image.getUrl());
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        assertEquals(level, image.getEstimatedResolutionLevel());
    }

    private static Image thumbnail(final String embedFields) throws Exception {
        NewPipe.init(new FixtureDownloader(embedFields, null));
        final StreamExtractor extractor =
                Rumble.getStreamExtractor("https://rumble.com/embed/vtest/");
        extractor.fetchPage();
        final List<Image> thumbnails = extractor.getThumbnails();
        assertEquals(1, thumbnails.size());
        return thumbnails.get(0);
    }

    private static List<Image> avatars(final String url, final String avatarClass)
            throws Exception {
        NewPipe.init(new FixtureDownloader("\"i\":\"" + COVER + "\"", "<html><head><style>"
                + ".user-image--img--id-" + AVATAR_ID + " {\n\tbackground-image: url("
                + AVATAR + ");\n}</style></head><body>"
                + "<script src=\"https://rumble.com/embed/vtest/\"></script>"
                + "<a class=\"media-by--a\" href=\"/c/Example\">"
                + "<i class='user-image " + avatarClass + " media-user-image'></i></a>"
                + "</body></html>"));
        final StreamExtractor extractor = Rumble.getStreamExtractor(url);
        extractor.fetchPage();
        return extractor.getUploaderAvatars();
    }

    private static final class FixtureDownloader extends Downloader {
        private final String embedFields;
        private final String watchPage;

        private FixtureDownloader(final String embedFields, final String watchPage) {
            this.embedFields = embedFields;
            this.watchPage = watchPage;
        }

        @Override
        public Response execute(@Nonnull final Request request) throws IOException {
            final String body;
            if (request.url().startsWith("https://rumble.com/embedJS/")) {
                body = "{\"ua\":{},\"cc\":{}," + embedFields + "}";
            } else if (watchPage != null && request.url().startsWith("https://rumble.com/v")) {
                body = watchPage;
            } else {
                throw new IOException("No fixture for " + request.url());
            }
            return new Response(200, "OK", Map.of(), body, request.url());
        }
    }
}
