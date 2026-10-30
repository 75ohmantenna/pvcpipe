package org.schabi.newpipe.extractor.services.kick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Kick;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.CHANNEL_API;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.CHANNEL_URL;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.CURRENT_UUID;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.MASTER_URL;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.kick.extractors.KickClipExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickClipInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;

@Tag("offline")
class KickClipExtractorTest {
    private static final String CLIP_ID = "clip_Case-Preserved_123";
    private static final String CLIP_URL = "https://kick.com/example/clips/" + CLIP_ID;
    private static final String CLIP_API = "https://kick.com/api/v2/clips/" + CLIP_ID;
    private static final String MP4 = "https://clips.example/video.mp4?signature=keep.m3u8";

    private KickFixtures downloader;
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;

    @BeforeEach
    void setUp() {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        downloader = new KickFixtures();
        NewPipe.init(downloader);
    }

    @AfterEach
    void restoreDownloader() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://kick.com/EXAMPLE/clips/clip_Case-Preserved_123",
            "https://www.kick.com/EXAMPLE/?clip=clip_Case-Preserved_123&ref=share",
            "https://kick.com/example?%63lip=clip_Case-Preserved_123"})
    void canonicalFormsExtractTheSameClip(final String url) throws Exception {
        fixture(clip());
        assertEquals("example/clips/" + CLIP_ID, Kick.getStreamLHFactory().fromUrl(url).getId());
        assertEquals(CLIP_URL, Kick.getStreamLHFactory().fromUrl(url).getUrl());
        assertEquals(StreamingService.LinkType.STREAM, Kick.getLinkTypeByUrl(url));
        assertFalse(Kick.getChannelLHFactory().acceptUrl(url));
        final StreamExtractor extractor = extract(url);
        assertInstanceOf(KickClipExtractor.class, extractor);
        assertEquals(CLIP_URL, extractor.getUrl());
        assertEquals(MP4, extractor.getVideoStreams().get(0).getContent());
        assertEquals(List.of(CLIP_API, CHANNEL_API),
                downloader.requests.stream().map(r -> r.url()).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://kick.com/example?clip", "https://kick.com/example?clip=",
            "https://kick.com/example?clip=clip_A&clip=clip_B",
            "https://kick.com/example?clip=clip_A&%43LIP=clip_A",
            "https://kick.com/example?clip=clip_A&live=123",
            "https://kick.com/example?clipId=clip_A",
            "https://kick.com/example?clip=clip_A&clipId=clip_A",
            "https://kick.com/example?clip=not-a-clip",
            "https://kick.com/example?clip=clip_",
            "https://kick.com/example?clip=clip_A%26live%3D123",
            "https://kick.com/example/clips/clip_A?clip=clip_A",
            "https://kick.com/example/clips/clip_A?live=123",
            "https://kick.com/example/clips?clip=clip_A",
            "https://kick.com/video/" + CURRENT_UUID + "?clip=clip_A",
            "https://kick.com/example/videos/" + CURRENT_UUID + "?clip=clip_A",
            "https://kick.com/example/videos/" + CURRENT_UUID + "?live=123",
            "https://kick.com/example?clip=%", "https://kick.com/example?clip=%FF",
            "https://kick.com/example?ref=%C3%28", "https://kick.com/example?%FF=ignored",
            "https://kick.com/example/clips/clip_A%2FB",
            "https://kick.com/example%2Fclips%2Fclip_A",
            "https://kick.com/example/clips//clip_A", "https://kick.com/example/../clips/clip_A",
            "https://kick.com:443/example?clip=clip_A",
            "https://user@kick.com/example?clip=clip_A",
            "https://kick.com.evil/example?clip=clip_A"})
    void unsafeSelectorsAndPathsFailBeforeAnyRequest(final String url) throws Exception {
        assertThrows(ParsingException.class, () -> KickUrl.streamId(url));
        assertFalse(Kick.getStreamLHFactory().acceptUrl(url));
        assertFalse(Kick.getChannelLHFactory().acceptUrl(url));
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    void encodedQueryValuesCannotCreateClipOrLiveSelectors() throws Exception {
        assertEquals("example", KickUrl.streamId("https://kick.com/example?ref=x%26clip%3Dclip_A"));
        assertEquals("example", KickUrl.streamId("https://kick.com/example?ref=x%26live%3D123"));
        assertEquals(0, KickUrl.liveTime("https://kick.com/example?ref=x%26live%3D123"));
        assertEquals("example?live=123", KickUrl.streamId("https://kick.com/example?live=123"));
        assertEquals("example", KickUrl.channelId("https://kick.com/example/clips"));
        assertEquals("example", KickUrl.channelId("https://kick.com/example/clips/"));
        assertEquals(CHANNEL_URL, Kick.getChannelLHFactory()
                .fromUrl("https://kick.com/example/clips").getUrl());
        assertTrue(KickUrl.isClip("example/clips/" + CLIP_ID));
        assertFalse(KickUrl.isVod("example/clips/" + CLIP_ID));
        assertFalse(KickUrl.isVod("example/anything"));
        assertFalse(KickUrl.isVod("example/videos/not-a-uuid"));
        assertTrue(KickUrl.isVod("video/" + CURRENT_UUID));
        assertTrue(KickUrl.isVod("example/videos/" + CURRENT_UUID));
        assertEquals(25, KickUrl.vodTime("https://kick.com/example/videos/" + CURRENT_UUID
                + "?%74=25&ref=share"));
    }

    @Test
    void directMp4UsesBroadcastUploaderAndNeverFetchesMediaAsText() throws Exception {
        fixture(clip());
        final StreamInfo info = StreamInfo.getInfo(Kick, CLIP_URL);
        assertEquals(StreamType.VIDEO_STREAM, info.getStreamType());
        assertEquals("Clip title", info.getName());
        assertEquals(13, info.getDuration());
        assertEquals(81, info.getViewCount());
        assertEquals(7, info.getLikeCount());
        assertEquals(18, info.getAgeLimit());
        assertEquals("Games", info.getCategory());
        assertEquals("Example", info.getUploaderName());
        assertEquals(CHANNEL_URL, info.getUploaderUrl());
        assertEquals(73, info.getUploaderSubscriberCount());
        assertTrue(info.isUploaderVerified());
        assertEquals("Channel biography", info.getDescription().getContent());
        assertEquals("https://images.example/avatar.jpg", info.getUploaderAvatars().get(0).getUrl());
        assertEquals("https://images.example/clip.jpg", info.getThumbnails().get(0).getUrl());
        assertNotNull(info.getUploadDate());
        assertEquals("", info.getHlsUrl());
        assertEquals(1, info.getVideoStreams().size());
        assertEquals(DeliveryMethod.PROGRESSIVE_HTTP,
                info.getVideoStreams().get(0).getDeliveryMethod());
        assertEquals(MediaFormat.MPEG_4, info.getVideoStreams().get(0).getFormat());
        assertFalse(info.getVideoStreams().get(0).isVideoOnly());
        assertTrue(info.getAudioStreams().isEmpty());
        assertTrue(info.getVideoOnlyStreams().isEmpty());
        assertTrue(info.getSubtitles().isEmpty());
        assertEquals(1, downloader.requests.stream().filter(r -> r.url().equals(CHANNEL_API)).count());
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().equals(MP4)));
    }

    @Test
    void hlsIsParsedOnceAndMediaPrecedenceIsDeterministic() throws Exception {
        final JsonObject clip = clip();
        clip.put("playback_url", MASTER_URL);
        clip.put("video_url", "https://ignored.example/video.mp4");
        fixture(clip);
        downloader.on(MASTER_URL, "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"French\",LANGUAGE=\"fr\","
                + "URI=\"audio.m3u8\"\n"
                + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"English\",LANGUAGE=\"en\","
                + "URI=\"text.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,FRAME-RATE=59.94,"
                + "CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"a\",SUBTITLES=\"s\"\n"
                + "video.m3u8?token=keep\n");
        final StreamExtractor extractor = extract(CLIP_URL);
        assertEquals(MASTER_URL, extractor.getHlsUrl());
        assertTrue(extractor.getVideoStreams().isEmpty());
        assertEquals(1, extractor.getVideoOnlyStreams().size());
        assertEquals("720p60", extractor.getVideoOnlyStreams().get(0).getResolution());
        assertEquals(1, extractor.getAudioStreams().size());
        assertEquals(MASTER_URL, extractor.getAudioStreams().get(0).getManifestUrl());
        assertEquals(1, extractor.getSubtitlesDefault().size());
        assertEquals(extractor.getSubtitlesDefault(), extractor.getSubtitles(MediaFormat.VTT));
        assertTrue(extractor.getSubtitles(MediaFormat.SRT).isEmpty());
        assertEquals("en", extractor.getSubtitlesDefault().get(0).getLanguageTag());
        assertEquals(1, downloader.requests.stream().filter(r -> r.url().equals(MASTER_URL)).count());
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().equals(MP4)));
    }

    @Test
    void emptySourcesFallThroughToVideoUrlAndCountersUseAliases() throws Exception {
        final JsonObject clip = clip();
        clip.put("playback_url", "  ");
        clip.put("clip_url", "");
        clip.put("video_url", MP4);
        clip.remove("views_count");
        clip.put("view_count", 19);
        clip.remove("likes_count");
        clip.put("likes", 3);
        fixture(clip);
        final StreamExtractor extractor = extract(CLIP_URL);
        assertEquals(MP4, extractor.getVideoStreams().get(0).getContent());
        assertEquals(19, extractor.getViewCount());
        assertEquals(3, extractor.getLikeCount());
        clip.remove("view_count");
        clip.put("views", 11);
        final KickClipInfoItemExtractor item = new KickClipInfoItemExtractor(clip,
                KickFixtures.channelData(false));
        assertEquals(11, item.getViewCount());
        assertEquals(13, item.getDuration());
        assertEquals(CLIP_URL, item.getUrl());
        assertEquals(CHANNEL_URL, item.getUploaderUrl());
        assertEquals("Example", item.getUploaderName());
        assertEquals(StreamType.VIDEO_STREAM, item.getStreamType());
        assertEquals(2, downloader.requests.size());
    }

    @Test
    void returnedClipAndChannelIdentityMustMatchTheRequest() {
        final JsonObject clip = clip();
        clip.put("id", "clip_Other");
        fixture(clip);
        assertThrows(ParsingException.class, () -> extract(CLIP_URL));
        assertEquals(1, downloader.requests.size());
        clip.put("id", CLIP_ID);
        clip.getObject("channel").put("slug", "other");
        fixture(clip);
        assertThrows(ParsingException.class, () -> extract(CLIP_URL));
        assertEquals(2, downloader.requests.size());
        clip.getObject("channel").put("slug", "example");
        clip.getObject("channel").put("id", 43);
        fixture(clip);
        assertThrows(ParsingException.class, () -> extract(CLIP_URL));
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().equals(MP4)));
    }

    @Test
    void privateSubscriberDeletedAndMissingMediaStayDistinct() {
        final JsonObject clip = clip();
        clip.put("privacy", "private");
        fixture(clip);
        assertThrows(PrivateContentException.class, () -> extract(CLIP_URL));
        clip.put("privacy", "public");
        clip.put("status", "sub_only");
        fixture(clip);
        assertThrows(PaidContentException.class, () -> extract(CLIP_URL));
        clip.put("status", "public");
        clip.put("deleted_at", "2026-10-20T00:00:00Z");
        fixture(clip);
        assertThrows(ContentNotAvailableException.class, () -> extract(CLIP_URL));
        clip.remove("deleted_at");
        clip.remove("clip_url");
        fixture(clip);
        assertThrows(ContentNotAvailableException.class, () -> extract(CLIP_URL));
        assertEquals(4, downloader.requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://clips.example/video.mp4", "https://clips.example/file.webm",
            "https://clips.example/file?name=video.mp4", "https://clips.example/file?name=video.m3u8"})
    void unsupportedNonemptySourceIsNotGuessedOrReplaced(final String source) {
        final JsonObject clip = clip();
        clip.put("playback_url", source);
        fixture(clip);
        assertThrows(ParsingException.class, () -> extract(CLIP_URL));
        assertEquals(1, downloader.requests.size());
    }

    @Test
    void missingClipResourceIsUnavailableAndMalformedIdentityIsParsingFailure() {
        downloader.on(CLIP_API, "{\"clip\":{}}");
        assertThrows(ContentNotAvailableException.class, () -> extract(CLIP_URL));
        downloader.status(CLIP_API, 404, "{}");
        assertThrows(ContentNotAvailableException.class, () -> extract(CLIP_URL));
        final JsonObject clip = clip();
        clip.remove("channel");
        fixture(clip);
        assertThrows(ParsingException.class, () -> extract(CLIP_URL));
    }

    private StreamExtractor extract(final String url) throws Exception {
        final StreamExtractor extractor = Kick.getStreamExtractor(url);
        extractor.fetchPage();
        return extractor;
    }

    private void fixture(final JsonObject clip) {
        downloader.channel(false).on(CLIP_API, "{\"clip\":" + JsonWriter.string(clip) + "}");
    }

    private static JsonObject clip() {
        return KickFixtures.json("{\"id\":\"" + CLIP_ID + "\",\"title\":\"Clip title\","
                + "\"duration\":13,\"created_at\":\"2026-10-18T12:00:00Z\",\"is_mature\":true,"
                + "\"views_count\":81,\"likes_count\":7,\"privacy\":\"public\","
                + "\"thumbnail_url\":\"https://images.example/clip.jpg\","
                + "\"clip_url\":\"" + MP4 + "\",\"category\":{\"name\":\"Games\"},"
                + "\"creator\":{\"username\":\"Not the broadcaster\"},"
                + "\"channel\":{\"id\":42,\"slug\":\"example\",\"username\":\"Stale name\"}}");
    }
}
