package org.schabi.newpipe.extractor.services.rumble.linkHandler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.downloader.DownloaderTestImpl;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.services.rumble.extractors.RumbleShortsStreamExtractor;
import org.schabi.newpipe.extractor.services.rumble.extractors.RumbleStreamExtractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for {@link RumbleStreamLinkHandlerFactory}
 */
@SuppressWarnings({"checkstyle:LineLength", "checkstyle:InvalidJavadocPosition"})
@Tag("offline")
public class RumbleStreamLinkHandlerFactoryTest {
    private static RumbleStreamLinkHandlerFactory linkHandler;

    @BeforeAll
    public static void setUp() {
        linkHandler = RumbleStreamLinkHandlerFactory.getInstance();
        NewPipe.init(DownloaderTestImpl.getInstance());
    }

    @Test
    public void getId() throws Exception {
        final String correctIdExpectSuccess = "vdofb67";
        final String tooShortIdExpectError = "vdof";
        final String noIdExpectError = "";


        final String[] baseUrls = {"https://rumble.com/"};

        /** {@value correctIdExpectSuccess} */
        for (final String baseUrl : baseUrls) {
            final String testUrl = baseUrl + correctIdExpectSuccess;
            assertEquals(correctIdExpectSuccess, linkHandler.fromUrl(testUrl).getId());

        }

        /** {@value tooShortIdExpectError} */
        for (final String baseUrl : baseUrls) {
            final String testUrl = baseUrl + tooShortIdExpectError;

            final ParsingException what = assertThrows(ParsingException.class,
                    () -> linkHandler.fromUrl(testUrl).getId());
            assertTrue(what instanceof ParsingException);
        }

        /** {@value noIdExpectError} */
        for (final String baseUrl : baseUrls) {
            final String testUrl = baseUrl + noIdExpectError;

            final ParsingException what = assertThrows(ParsingException.class,
                    () -> linkHandler.fromUrl(testUrl).getId());
            assertTrue(what instanceof ParsingException);
        }

        final String[] invalidVideoUrls = {
                "https://pumble.com",
                "https://sumble.com/vdofb7",
                "https://sumble.com/vd_ofb7",
                "https://rumble.com",
                "https://rumble.com/",
                "https://rumble.com/category/v23",
                "https://rumble.com/category/v23/",
                "https://rumble.com/user/Vlemx",
                "https://rumble.com/c/Vlemx",
                "https://rumble.com/user/vmpradio",
                "https://rumble.com/videos",
                "https://rumble.com/videos/",
                "https://rumble.com/videos-featured.html",
                "https://rumble.com/videos?sort=views&date=today",
        };

        for (final String invalidVideoUrl : invalidVideoUrls) {
            assertThrows(ParsingException.class, () -> linkHandler.getId(invalidVideoUrl),
                    "This URL is invalid: " + invalidVideoUrl);
        }
    }

    @Test
    public void getUrl() throws Exception {
        final String inputVideoUrl = "https://rumble.com/vdofb7";
        //final String inputEmbedUrl = "https://www.rumble.com/embed/8gwdyYJ8BUk/";
        final String inputId = "vdofb7";

        final String expectedUrl = "https://rumble.com/vdofb7";

        assertEquals(expectedUrl,
                linkHandler.fromId(inputId).getUrl());
        assertEquals(expectedUrl,
                linkHandler.fromUrl(inputVideoUrl).getUrl());
        //assertEquals(expectedUrl,
        //        linkHandler.fromUrl(inputEmbedUrl).getUrl());
    }

    @Test
    public void testAcceptUrl() throws ParsingException {
        final String validShortVideoUrl = "https://rumble.com/vdofb7";
        final String validLongVideoUrl = "https://rumble.com/vg1hkl-youtube-ceo-wins-major-award-and-you-wont-believe-for-what.html";
        final String validWwwVideoUrl = "https://www.rumble.com/vdofb7";

        assertTrue(linkHandler.acceptUrl(validShortVideoUrl));
        assertTrue(linkHandler.acceptUrl(validLongVideoUrl));
        assertTrue(linkHandler.acceptUrl(validWwwVideoUrl));
        assertThrows(ParsingException.class,
                () -> linkHandler.fromUrl("https://notrumble.com/vdofb7"));
    }

    @Test
    public void preservesShortsCanonicalUrlWithoutSharedState() throws Exception {
        assertEquals("https://rumble.com/shorts/v6abcde",
                linkHandler.fromUrl("https://rumble.com/shorts/v6abcde-title.html").getUrl());
        assertEquals("https://rumble.com/vdofb7", linkHandler.fromId("vdofb7").getUrl());
    }

    @Test
    public void acceptsEmbedUrlsAndUsesTheFinalIdSegment() throws Exception {
        assertEquals("https://rumble.com/embed/v5pv5f",
                linkHandler.fromUrl("https://rumble.com/embed/v5pv5f/").getUrl());
        assertEquals("v5pv5f",
                linkHandler.fromUrl("https://www.rumble.com/embed/ufe9n.v5pv5f").getId());
    }

    @Test
    public void canonicalPathDeterminesExtractorType() throws Exception {
        final String[][] cases = {
                {"https://rumble.com/vdofb7?next=/shorts/v6abcde",
                        "https://rumble.com/vdofb7", "watch"},
                {"https://rumble.com/vdofb7#/shorts/v6abcde",
                        "https://rumble.com/vdofb7", "watch"},
                {"https://rumble.com/shorts/v6abcde-title.html?next=/v12345",
                        "https://rumble.com/shorts/v6abcde", "shorts"},
                {"https://rumble.com/shorts/v6abcde#section",
                        "https://rumble.com/shorts/v6abcde", "shorts"},
                {"https://www.rumble.com/vdofb7-title.html#section",
                        "https://rumble.com/vdofb7", "watch"},
                {"https://rumble.com/embed/ufe9n.v5pv5f/?next=/shorts/v6abcde",
                        "https://rumble.com/embed/v5pv5f", "watch"},
                {"https://rumble.com/embed/v5pv5f/#section",
                        "https://rumble.com/embed/v5pv5f", "watch"}
        };
        for (final String[] testCase : cases) {
            final String input = testCase[0];
            assertTrue(linkHandler.acceptUrl(input), input);
            final LinkHandler handler = linkHandler.fromUrl(input);
            assertEquals(testCase[1], handler.getUrl(), input);
            if ("shorts".equals(testCase[2])) {
                assertInstanceOf(RumbleShortsStreamExtractor.class,
                        ServiceList.Rumble.getStreamExtractor(handler), input);
            } else {
                assertInstanceOf(RumbleStreamExtractor.class,
                        ServiceList.Rumble.getStreamExtractor(handler), input);
            }
        }
    }
}
