package org.schabi.newpipe.extractor.services.bitchute.linkHandler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.downloader.DownloaderTestImpl;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for {@link BitchuteStreamLinkHandlerFactory}
 */
@SuppressWarnings("checkstyle:InvalidJavadocPosition")
@Tag("offline")
public class BitchuteStreamLinkHandlerFactoryTest {
    private static BitchuteStreamLinkHandlerFactory linkHandler;

    @BeforeAll
    public static void setUp() {
        linkHandler = BitchuteStreamLinkHandlerFactory.getInstance();
        NewPipe.init(DownloaderTestImpl.getInstance());
    }

    @Test
    public void getId() throws Exception {
        final String correctIdExpectSuccess = "m4iEhq4L1qU";
        final String tooShortIdExpectError = "uHV_f80jP2";
        final String noIdExpectError = "";

        final String[] baseUrls = {
                "https://www.bitchute.com/video/",
                "https://www.bitchute.com/embed/",
                "https://old.bitchute.com/video/"
        };

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
    }

    @Test
    public void getUrl() throws Exception {
        final String inputVideoUrl = "https://www.bitchute.com/video/8gwdyYJ8BUk/";
        final String inputEmbedUrl = "https://www.bitchute.com/embed/8gwdyYJ8BUk/";
        final String inputId = "8gwdyYJ8BUk";

        final String expectedUrl = "https://www.bitchute.com/video/8gwdyYJ8BUk";

        assertEquals(expectedUrl,
                linkHandler.fromId(inputId).getUrl());
        assertEquals(expectedUrl,
                linkHandler.fromUrl(inputVideoUrl).getUrl());
        assertEquals(expectedUrl,
                linkHandler.fromUrl(inputEmbedUrl).getUrl());
    }

    @Test
    public void testAcceptUrl() throws ParsingException {
        final String inputVideoUrl = "https://www.bitchute.com/video/8gwdyYJ8BUk/";
        final String inputEmbedUrl = "https://www.bitchute.com/embed/8gwdyYJ8BUk/";

        assertTrue(linkHandler.acceptUrl(inputVideoUrl));
        assertTrue(linkHandler.acceptUrl(inputEmbedUrl));
    }

    @Test
    public void acceptsTorrentUrlsAndRejectsLookalikeHosts() throws Exception {
        assertEquals("szoMrox2JEI", linkHandler.fromUrl(
                "https://www.bitchute.com/torrent/Zee5BE49045h/szoMrox2JEI.webtorrent")
                .getId());
        assertThrows(ParsingException.class, () -> linkHandler.fromUrl(
                "https://www.bitchute.com/torrent/Zee5BE49045h/video.webtorrent"));
        assertThrows(ParsingException.class,
                () -> linkHandler.fromUrl("https://notbitchute.com/video/8gwdyYJ8BUk/"));
        assertThrows(ParsingException.class,
                () -> linkHandler.fromUrl("ftp://www.bitchute.com/video/8gwdyYJ8BUk/"));
    }

    @Test
    public void acceptsUppercaseHostInTurkishLocale() throws Exception {
        final Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("8gwdyYJ8BUk", linkHandler.fromUrl(
                    "https://WWW.BITCHUTE.COM/video/8gwdyYJ8BUk/").getId());
        } finally {
            Locale.setDefault(originalLocale);
        }
    }
}
