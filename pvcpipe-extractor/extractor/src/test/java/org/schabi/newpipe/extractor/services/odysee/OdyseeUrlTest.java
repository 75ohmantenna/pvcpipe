package org.schabi.newpipe.extractor.services.odysee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Odysee;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.StreamingService.LinkType;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;

@Tag("offline")
class OdyseeUrlTest {

    private static final String CLAIM_ID = "e7d93d772bd87e2b62d5ab993c1c3ced86ebb396";

    @Test
    void readsVideoAddresses() throws Exception {
        assertStream("@Mantega:1/First-day-LBRY:1", "https://odysee.com/@Mantega:1/First-day-LBRY:1");
        assertStream("@Mantega:1/First-day-LBRY:1", "https://www.odysee.com/@Mantega:1/First-day-LBRY:1");
        assertStream("@Mantega:1/First-day-LBRY:1", "https://lbry.tv/@Mantega:1/First-day-LBRY:1");
        assertStream("@Mantega:1/First-day-LBRY:1", "http://open.lbry.com/@Mantega:1/First-day-LBRY:1");
        assertStream("@Mantega:1/First-day-LBRY:1",
                "https://odysee.com/@Mantega:1/First-day-LBRY:1?r=abc&t=90");
        // without claim ids, and without a channel
        assertStream("@LBRYFoundation/Episode-1", "https://odysee.com/@LBRYFoundation/Episode-1");
        assertStream("Episode-1:e7", "https://odysee.com/Episode-1:e7");
        assertStream("Episode-1:" + CLAIM_ID, "https://odysee.com/Episode-1:" + CLAIM_ID);
        // claim ids are hexadecimal, so their case does not matter
        assertStream("Episode-1:e7", "https://odysee.com/Episode-1:E7");
    }

    @Test
    void readsEmbedAndDownloadAddresses() throws Exception {
        assertStream("Episode-1:" + CLAIM_ID, "https://odysee.com/$/embed/Episode-1/" + CLAIM_ID);
        assertStream("Episode-1:" + CLAIM_ID,
                "https://odysee.com/$/download/Episode-1/" + CLAIM_ID);
        assertStream("@Mantega:1/First-day-LBRY:1",
                "https://odysee.com/$/embed/@Mantega:1/First-day-LBRY:1");
        assertStream("Episode-1:e7", "https://odysee.com/$/embed/Episode-1:e7");
    }

    @Test
    void readsNamesWithPunctuationAndOtherScripts() throws Exception {
        assertStream("@ScammerRevolts:b0/I-SYSKEY'D-THE-SAME-SCAMMERS-3-TIMES!:b",
                "https://odysee.com/@ScammerRevolts:b0/I-SYSKEY'D-THE-SAME-SCAMMERS-3-TIMES!:b");
        assertStream("@lacajadepandora:a/TRUMP-ESTÁ-BIEN-(720p):1",
                "https://lbry.tv/@lacajadepandora:a/TRUMP-EST%C3%81-BIEN-(720p):1");
        // a plus sign in a path is a plus sign, not a space
        assertStream("@a:1/c++:2", "https://odysee.com/@a:1/c++:2");
        // LBRY forbids an opening square bracket in a name and allows a closing one
        assertStream("@a:1/take-2]:2", "https://odysee.com/@a:1/take-2%5D:2");
        assertEquals("https://odysee.com/@a:1/take-2%5D:2",
                Odysee.getStreamLHFactory().fromId("@a:1/take-2]:2").getUrl());
        assertEquals(LinkType.NONE, Odysee.getLinkTypeByUrl("https://odysee.com/@a:1/take%5B2:2"));
    }

    @Test
    void readsChannelAndPlaylistAddresses() throws Exception {
        assertChannel("@Odysee:8", "https://odysee.com/@Odysee:8");
        assertChannel("@Odysee:8", "https://odysee.com/@Odysee:8/");
        assertChannel("@Odysee:8", "https://odysee.com/@Odysee:8?view=playlists");
        assertChannel("@Odysee", "https://odysee.com/@Odysee");
        assertChannel("@Odysee:80d2590ad04e36fb1d077a9b9e3a8bba76defdf8",
                "https://odysee.com/@Odysee:80d2590ad04e36fb1d077a9b9e3a8bba76defdf8");

        for (final String route : new String[]{"playlist", "list"}) {
            final String url = "https://odysee.com/$/" + route + "/" + CLAIM_ID;
            assertEquals(LinkType.PLAYLIST, Odysee.getLinkTypeByUrl(url));
            assertEquals(CLAIM_ID, Odysee.getPlaylistLHFactory().fromUrl(url).getId());
        }
        assertEquals("https://odysee.com/$/playlist/" + CLAIM_ID,
                Odysee.getPlaylistLHFactory().fromId(CLAIM_ID).getUrl());
    }

    @Test
    void rejectsWhatIsNotAVideoChannelOrPlaylist() throws Exception {
        for (final String url : new String[]{
                "https://odysee.com/",
                "https://odysee.com/$/settings",
                "https://odysee.com/$/search?q=linux",
                "https://odysee.com/$/embed",
                // the private lists of a signed-in user are not claims
                "https://odysee.com/$/playlist/watchlater",
                "https://odysee.com/$/playlist/938fb11d-215f-4d1c-ad64-723954df2184",
                "https://odysee.com/@a:1/b:2/c:3",
                "https://odysee.com/@a:1/@b:2",
                "https://odysee.com/@a:xyz",
                "https://example.com/@Odysee:8",
                "https://odysee.com.example.org/@Odysee:8",
                "ftp://odysee.com/@Odysee:8"}) {
            assertEquals(LinkType.NONE, Odysee.getLinkTypeByUrl(url), url);
            assertThrows(ParsingException.class, () -> OdyseeUrl.parse(url), url);
        }
    }

    @Test
    void writesAddressesThatReadBackToTheSameId() throws Exception {
        for (final String id : new String[]{
                "@Mantega:1/First-day-LBRY:1",
                "@ScammerRevolts:b0/I-SYSKEY'D-THE-SAME-SCAMMERS-3-TIMES!:b",
                "@_V!K..vo..vo:c/ВЕЧЕР-В-ХАТУ-🔥:2",
                "@a:1/c++:2",
                "Episode-1:e7"}) {
            final String url = Odysee.getStreamLHFactory().fromId(id).getUrl();
            assertTrue(url.matches("[\\x21-\\x7e]+"), url);
            assertEquals(id, Odysee.getStreamLHFactory().fromUrl(url).getId());
        }
        assertEquals("https://odysee.com/@_V!K..vo..vo:c/%D0%92-%F0%9F%94%A5:2",
                OdyseeUrl.toWebUrl("@_V!K..vo..vo:c/В-🔥:2"));
    }

    @Test
    void translatesBetweenWebsiteAndApiAddresses() {
        assertEquals("lbry://@Mantega#1/First-day-LBRY#1",
                OdyseeUrl.toLbryUri("@Mantega:1/First-day-LBRY:1"));
        assertEquals("lbry://Episode-1", OdyseeUrl.toLbryUri("Episode-1"));
        assertEquals("https://odysee.com/@Mantega:1/First-day-LBRY:1",
                OdyseeUrl.lbryUriToWebUrl("lbry://@Mantega#1/First-day-LBRY#1"));
        assertEquals("https://odysee.com/@Odysee:80d2590ad04e36fb1d077a9b9e3a8bba76defdf8",
                OdyseeUrl.lbryUriToWebUrl(
                        "lbry://@Odysee#80d2590ad04e36fb1d077a9b9e3a8bba76defdf8"));
    }

    @Test
    void odyseeAddressesBelongToOdyseeAlone() throws Exception {
        final String[] odyseeUrls = {
                "https://odysee.com/@Odysee:8",
                "https://odysee.com/@Odysee:8/a-video:e",
                "https://odysee.com/$/playlist/" + CLAIM_ID};
        for (final String url : odyseeUrls) {
            assertEquals(Odysee.getServiceId(), NewPipe.getServiceByUrl(url).getServiceId(), url);
            for (final StreamingService service : ServiceList.all()) {
                if (service != Odysee) {
                    assertEquals(LinkType.NONE, service.getLinkTypeByUrl(url),
                            service.getServiceInfo().getName() + " " + url);
                }
            }
        }
        for (final String url : new String[]{
                "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                "https://www.youtube.com/@NASA",
                "https://soundcloud.com/forss/flickermood",
                "https://www.bitchute.com/video/UGlrF9o9b-Qx/",
                "https://rumble.com/v5a7yh0-spacex-starship-test-flight-5-update.html"}) {
            assertEquals(LinkType.NONE, Odysee.getLinkTypeByUrl(url), url);
            assertFalse(NewPipe.getServiceByUrl(url) == Odysee, url);
        }
    }

    private static void assertStream(final String expectedId, final String url) throws Exception {
        assertEquals(LinkType.STREAM, Odysee.getLinkTypeByUrl(url), url);
        final LinkHandler handler = Odysee.getStreamLHFactory().fromUrl(url);
        assertEquals(expectedId, handler.getId(), url);
        assertEquals(expectedId, Odysee.getCommentsLHFactory().fromUrl(url).getId(), url);
        assertEquals(url, handler.getOriginalUrl());
    }

    private static void assertChannel(final String expectedId, final String url) throws Exception {
        assertEquals(LinkType.CHANNEL, Odysee.getLinkTypeByUrl(url), url);
        assertEquals(expectedId, Odysee.getChannelLHFactory().fromUrl(url).getId(), url);
        assertEquals("https://odysee.com/" + expectedId,
                Odysee.getChannelLHFactory().fromUrl(url).getUrl(), url);
    }
}
