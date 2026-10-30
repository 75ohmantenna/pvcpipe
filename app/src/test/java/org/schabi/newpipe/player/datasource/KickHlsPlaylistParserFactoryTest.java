package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.net.Uri;

import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class KickHlsPlaylistParserFactoryTest {
    private static final String BASE = "https://media.example/path/";
    private static final String VIDEO = BASE + "high.m3u8?token=A%2FB&part=2";
    private static final String MASTER = "#EXTM3U\n"
            + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",LANGUAGE=\"en\","
            + "DEFAULT=YES,AUTOSELECT=YES,URI=\"en.m3u8\"\n"
            + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"French\",LANGUAGE=\"fr\","
            + "AUTOSELECT=YES,URI=\"fr.m3u8\"\n"
            + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"other\",NAME=\"Other\",LANGUAGE=\"de\","
            + "URI=\"other-audio.m3u8\"\n"
            + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"English\",LANGUAGE=\"en\","
            + "URI=\"text/en.m3u8\"\n"
            + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"French\",LANGUAGE=\"fr\","
            + "URI=\"text/fr.m3u8\"\n"
            + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"other\",NAME=\"Other\",LANGUAGE=\"de\","
            + "URI=\"other-text.m3u8\"\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,"
            + "CODECS=\"avc1.42e01e,mp4a.40.2\",AUDIO=\"a\",SUBTITLES=\"s\"\n"
            + "high.m3u8?token=A%2FB&part=2\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,"
            + "CODECS=\"avc1.42e01e,mp4a.40.2\",AUDIO=\"other\",SUBTITLES=\"other\"\n"
            + "low.m3u8\n";
    private static final String MEDIA = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n"
            + "#EXTINF:2,\nsegment.ts\n#EXT-X-ENDLIST\n";

    @Test
    public void selectedVideoKeepsOnlyItsAssociatedAudioAndSubtitleGroups() throws Exception {
        final HlsMultivariantPlaylist selected = master(new KickHlsPlaylistParserFactory(VIDEO,
                null).createPlaylistParser());

        assertEquals(1, selected.variants.size());
        assertEquals(VIDEO, selected.variants.get(0).url.toString());
        assertEquals(2, selected.audios.size());
        assertEquals(2, selected.subtitles.size());
        assertEquals("en", selected.subtitles.get(0).format.language);
        assertEquals("fr", selected.subtitles.get(1).format.language);
        assertEquals(BASE + "text/fr.m3u8", selected.subtitles.get(1).url.toString());
    }

    @Test
    public void explicitCompatibleAudioRetainsCaptionsAndOnlyThatAudio() throws Exception {
        final HlsMultivariantPlaylist selected = master(new KickHlsPlaylistParserFactory(VIDEO,
                BASE + "fr.m3u8").createPlaylistParser());

        assertEquals(1, selected.audios.size());
        assertEquals("fr", selected.audios.get(0).format.language);
        assertEquals(2, selected.subtitles.size());
    }

    @Test
    public void bothParserEntryPointsFilterMasters() throws Exception {
        final KickHlsPlaylistParserFactory factory = new KickHlsPlaylistParserFactory(VIDEO, null);
        final HlsMultivariantPlaylist selected = master(factory.createPlaylistParser());
        final HlsMultivariantPlaylist refreshed = master(
                factory.createPlaylistParser(selected, null));

        assertEquals(1, refreshed.variants.size());
        assertEquals(2, refreshed.audios.size());
        assertEquals(2, refreshed.subtitles.size());
    }

    @Test
    public void missingVideoOrIncompatibleAudioNeverReturnsUnrestrictedMaster() throws Exception {
        missing(new KickHlsPlaylistParserFactory(BASE + "gone.m3u8", null));
        missing(new KickHlsPlaylistParserFactory(VIDEO.replace("A%2FB", "changed"), null));
        missing(new KickHlsPlaylistParserFactory(VIDEO, BASE + "other-audio.m3u8"));
        missing(new KickHlsPlaylistParserFactory(VIDEO, BASE + "gone.m3u8"));
    }

    @Test
    public void mediaPlaylistsAreDelegatedWithoutSelectionValidation() throws Exception {
        final KickHlsPlaylistParserFactory factory = new KickHlsPlaylistParserFactory(VIDEO,
                BASE + "fr.m3u8");
        final HlsMultivariantPlaylist selected = master(factory.createPlaylistParser());
        for (final ParsingLoadable.Parser<HlsPlaylist> parser : java.util.List.of(
                factory.createPlaylistParser(), factory.createPlaylistParser(selected, null))) {
            final HlsPlaylist result = parse(parser, MEDIA);
            assertTrue(result instanceof HlsMediaPlaylist);
            final HlsMediaPlaylist media = (HlsMediaPlaylist) result;
            assertEquals(1, media.segments.size());
            assertEquals("segment.ts", media.segments.get(0).url);
            assertEquals(2000000L, media.durationUs);
            assertTrue(media.hasEndTag);
        }
    }

    private static void missing(final KickHlsPlaylistParserFactory factory) throws Exception {
        try {
            master(factory.createPlaylistParser());
            fail("Missing selected rendition must fail");
        } catch (final IOException error) {
            assertEquals("Selected Kick HLS rendition is no longer available", error.getMessage());
        }
    }

    private static HlsMultivariantPlaylist master(final ParsingLoadable.Parser<HlsPlaylist> parser)
            throws IOException {
        return (HlsMultivariantPlaylist) parse(parser, MASTER);
    }

    private static HlsPlaylist parse(final ParsingLoadable.Parser<HlsPlaylist> parser,
                                     final String manifest) throws IOException {
        return parser.parse(Uri.parse(BASE + "master.m3u8"),
                new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
    }
}
