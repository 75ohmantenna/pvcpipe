package org.schabi.newpipe.extractor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In Turkish the upper case of "i" is "İ" and the lower case of "I" is "ı". Code that changes
 * the case of a host name or an address with the language of the device stops recognising
 * them on a phone set to Turkish.
 */
@Tag("offline")
class TurkishLocaleTest {
    private Locale previous;

    @BeforeEach
    void switchToTurkish() {
        previous = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
    }

    @AfterEach
    void switchBack() {
        Locale.setDefault(previous);
    }

    @Test
    void theLanguageOfTheDeviceReallyChangesTheCase() {
        // what the fixed code must not depend on
        assertEquals("MUS\u0130C", "music".toUpperCase());
        assertEquals("\u0131", "I".toLowerCase());
    }

    @Test
    void youtubeHostsWithAnIAreStillRecognised() throws Exception {
        final YoutubeStreamLinkHandlerFactory factory =
                YoutubeStreamLinkHandlerFactory.getInstance();
        assertEquals("jZViOEv90dI",
                factory.getId("https://music.youtube.com/watch?v=jZViOEv90dI"));
        assertEquals("jZViOEv90dI",
                factory.getId("https://www.youtube-nocookie.com/embed/jZViOEv90dI"));
        assertEquals("jZViOEv90dI",
                factory.getId("https://www.youtube.com/watch?v=jZViOEv90dI"));
    }

    @Test
    void soundcloudAddressesWithAnUpperCaseIAreStillRecognised() throws Exception {
        assertTrue(SoundcloudStreamLinkHandlerFactory.getInstance()
                .acceptUrl("https://soundcloud.com/IMusic/I-LIKE-IT"));
        assertTrue(SoundcloudChannelLinkHandlerFactory.getInstance()
                .acceptUrl("https://soundcloud.com/IMusic"));
    }

    @Test
    void addressChecksAndNumberWordsDoNotFollowTheDevice() throws Exception {
        Utils.checkUrl(Pattern.compile("^https://example\\.com/[a-z]+$"),
                "HTTPS://EXAMPLE.COM/INDEX");
        assertEquals(1_500, Utils.mixedNumberWordToLong("1.5k"));
        assertEquals(2_000_000, Utils.mixedNumberWordToLong("2M"));
    }
}
