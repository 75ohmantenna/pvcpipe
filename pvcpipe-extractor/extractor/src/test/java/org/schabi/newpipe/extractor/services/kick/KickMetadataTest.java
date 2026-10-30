package org.schabi.newpipe.extractor.services.kick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Kick;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.*;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.services.kick.settings.KickSettings;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.List;
import java.util.Locale;

@Tag("offline")
class KickMetadataTest {
    private KickFixtures downloader;
    private boolean previousHide;

    @BeforeEach
    void setUp() {
        downloader = new KickFixtures();
        NewPipe.init(downloader);
        previousHide = KickSettings.getInstance().isSettingEnabled(KickSettings.HIDE_PAID_STREAMS);
        KickSettings.getInstance().setting(false, KickSettings.HIDE_PAID_STREAMS);
    }

    @AfterEach
    void restore() {
        KickSettings.getInstance().setting(previousHide, KickSettings.HIDE_PAID_STREAMS);
    }

    @Test
    void detailsExposeDeduplicatedActualImagesCategoryTagsAndValidatedLanguage() throws Exception {
        final JsonObject video = videoData();
        final String thumb = "https://images.kick.com/video_thumbnails/a/b/720.webp";
        video.put("thumbnail", json("{\"src\":\"" + thumb + "\",\"srcSet\":\"" + thumb
                + " 999w, https://images.kick.com/video_thumbnails/a/b/360.webp 123w\"}"));
        video.put("categories", json("{\"values\":[{}, {\"name\":\" Games \"}]}")
                .getArray("values"));
        video.put("tags", json("{\"values\":[\" first \",\"first\",3,\"\",\"second\"]}")
                .getArray("values"));
        video.put("language", "fr-CA");
        downloader.recording().on(VIDEOS_API + "/" + CURRENT_UUID,
                "{\"data\":" + JsonWriter.string(video) + "}");
        final StreamInfo info = StreamInfo.getInfo(Kick, VIDEO_URL);
        assertEquals(List.of(720, 360), info.getThumbnails().stream().map(image -> image.getHeight())
                .collect(java.util.stream.Collectors.toList()));
        assertEquals("Games", info.getCategory());
        assertEquals(List.of("first", "second"), info.getTags());
        assertEquals(Locale.CANADA_FRENCH, info.getLanguageInfo());
        assertEquals("Channel biography", info.getDescription().getContent());
        assertEquals(ContentAvailability.AVAILABLE, info.getContentAvailability());
        video.put("language", "not a locale");
        assertNull(KickApi.language(video));
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void membershipVisibilityDoesNotGrantPlaybackAndNestedLegacyRestrictionsSurvive() throws Exception {
        final JsonObject paid = videoData();
        paid.put("status", "sub_only");
        downloader.channel(false).on(VIDEOS_API, "{\"data\":[" + JsonWriter.string(paid) + "]}");
        final var handler = Kick.getChannelTabLHFactory().fromUrl(CHANNEL_URL);
        final StreamInfoItem visible = (StreamInfoItem) ChannelTabInfo.getInfo(Kick, handler)
                .getRelatedItems().get(0);
        assertEquals(ContentAvailability.MEMBERSHIP, visible.getContentAvailability());
        KickSettings.getInstance().setting(true, KickSettings.HIDE_PAID_STREAMS);
        assertTrue(ChannelTabInfo.getInfo(Kick, handler).getRelatedItems().isEmpty());
        final JsonObject legacy = json("{\"uuid\":\"" + LEGACY_UUID + "\",\"source\":\""
                + MASTER_URL + "\",\"status\":\"public\"}");
        legacy.put("livestream", paid);
        downloader.on("https://kick.com/api/v1/video/" + LEGACY_UUID, JsonWriter.string(legacy));
        assertThrows(PaidContentException.class,
                () -> StreamInfo.getInfo(Kick, "https://kick.com/video/" + LEGACY_UUID));
        assertFalse(downloader.requests.stream().anyMatch(request -> request.url().equals(MASTER_URL)));
    }

    @Test
    void relatedVodsExcludeCurrentAndPrivateAndFailureStaysOptional() throws Exception {
        final JsonObject other = videoData();
        other.put("id", LEGACY_UUID);
        final JsonObject hidden = videoData();
        hidden.put("status", "private");
        downloader.recording().on(VIDEOS_API, "{\"data\":[" + JsonWriter.string(videoData()) + ","
                + JsonWriter.string(other) + "," + JsonWriter.string(hidden) + "]}");
        final StreamInfo info = StreamInfo.getInfo(Kick, VIDEO_URL);
        assertEquals(List.of(CHANNEL_URL + "/" + LEGACY_UUID), info.getRelatedItems().stream()
                .map(item -> item.getUrl()).collect(java.util.stream.Collectors.toList()));
        downloader.status(VIDEOS_API, 503, "{}");
        final StreamInfo usable = StreamInfo.getInfo(Kick, VIDEO_URL);
        assertEquals(MASTER_URL, usable.getHlsUrl());
        assertEquals(2, usable.getVideoStreams().size());
        assertTrue(usable.getErrors().stream().anyMatch(error -> error.getMessage().contains("503")));
    }

    @Test
    void relatedLiveDiscoveryScopesCategoryAndExcludesCurrentChannel() throws Exception {
        final JsonObject channel = channelData(true);
        channel.getObject("livestream").put("category", json("{\"id\":8,\"name\":\"Games\"}"));
        final JsonObject same = json(JsonWriter.string(channel.getObject("livestream")));
        same.put("channel", json("{\"slug\":\"example\"}"));
        final JsonObject other = json(JsonWriter.string(same));
        other.put("channel", json("{\"slug\":\"other\",\"username\":\"Other\"}"));
        downloader.on(CHANNEL_API, JsonWriter.string(channel))
                .on(CHANNEL_API + "/playback-url", "{\"data\":\"" + MASTER_URL + "\"}")
                .on(MASTER_URL, MASTER)
                .on(KickApi.livestreamsUrl("en", 24, 8L, null), "{\"data\":{\"livestreams\":["
                        + JsonWriter.string(same) + "," + JsonWriter.string(other) + "]}}");
        final StreamInfo info = StreamInfo.getInfo(Kick, "https://kick.com/example");
        assertEquals(1, info.getRelatedItems().size());
        assertTrue(info.getRelatedItems().get(0).getUrl().startsWith("https://kick.com/other?live="));
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void clipRelatedUsesSameChannelDefaultRangeAndExcludesCurrentAndPrivate() throws Exception {
        final JsonObject clip = json("{\"id\":\"clip_A\",\"title\":\"Current\","
                + "\"clip_url\":\"https://media.example/clip.mp4\",\"privacy\":\"public\","
                + "\"duration\":30,\"channel\":{\"id\":42,\"slug\":\"example\"}}");
        final JsonObject other = json(JsonWriter.string(clip));
        other.put("id", "clip_B");
        final JsonObject hidden = json(JsonWriter.string(clip));
        hidden.put("id", "clip_C");
        hidden.put("privacy", "private");
        downloader.channel(false).on("https://kick.com/api/v2/clips/clip_A",
                "{\"clip\":" + JsonWriter.string(clip) + "}")
                .on("https://web.kick.com/api/v1/channels/42/clips?sort=date&time=all",
                        "{\"data\":{\"clips\":[" + JsonWriter.string(clip) + ","
                                + JsonWriter.string(other) + "," + JsonWriter.string(hidden)
                                + "]}}");
        final StreamInfo info = StreamInfo.getInfo(Kick, "https://kick.com/example/clips/clip_A");
        assertEquals(List.of("https://kick.com/example/clips/clip_B"), info.getRelatedItems().stream()
                .map(item -> item.getUrl()).collect(java.util.stream.Collectors.toList()));
        assertEquals("https://media.example/clip.mp4", info.getVideoStreams().get(0).getContent());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }
}
