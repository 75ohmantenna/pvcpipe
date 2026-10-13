package org.schabi.newpipe.extractor.services.odysee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Odysee;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.ok;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.params;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.redirectedTo;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.status;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.CHANNEL_ID;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.FILE_URL;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.HLS_PLAYLIST;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.PLAYLIST_URL;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_ID;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_URI;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_URL;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.items;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.resolved;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.withTags;

import com.grack.nanojson.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Tag("offline")
class OdyseeStreamExtractorTest {

    private static final String REACTIONS = "{\"success\":true,\"error\":null,\"data\":"
            + "{\"others_reactions\":{\"" + VIDEO_ID + "\":{\"dislike\":36,\"like\":213}}}}";

    private OdyseeFakeDownloader downloader;

    @BeforeEach
    void setUp() {
        downloader = new OdyseeFakeDownloader();
        NewPipe.init(downloader);
        OdyseeApi.clearCaches();
    }

    /** Routes for a video that Odysee has transcoded, with likes and one related video. */
    private void serveTranscodedVideo(final JsonObject claim) {
        downloader.onProxy("resolve", resolved(VIDEO_URI, claim))
                .onProxy("get", "{\"streaming_url\":\"" + FILE_URL + "\"}")
                .on(FILE_URL, request -> redirectedTo(PLAYLIST_URL))
                .on(PLAYLIST_URL, HLS_PLAYLIST)
                .on(OdyseeApi.REACTIONS_URL, REACTIONS)
                .on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"" + VIDEO_ID + "\",\"name\":\"a-video\"},"
                        + "{\"claimId\":\"aaaa\",\"name\":\"related\"}]")
                .onProxy("claim_search", items(OdyseeFixtures.video("related", "aaaa", "Related")));
    }

    @Test
    void transcodedVideoOffersHlsRenditionsAndTheUploadedFile() throws Exception {
        serveTranscodedVideo(OdyseeFixtures.video());

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL + "?t=1m30s");

        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
        assertEquals("@Example:8/a-video:e", info.getId());
        assertEquals(VIDEO_URL, info.getUrl());
        assertEquals("A video", info.getName());
        assertEquals(StreamType.VIDEO_STREAM, info.getStreamType());
        assertEquals(413, info.getDuration());
        assertEquals(90, info.getStartPosition());
        assertEquals(Instant.ofEpochSecond(1780000000), info.getUploadDate().getInstant());
        assertEquals(new Description("First line\nSecond line", Description.PLAIN_TEXT),
                info.getDescription());
        assertEquals(-1, info.getViewCount());
        assertEquals(213, info.getLikeCount());
        assertEquals(36, info.getDislikeCount());
        assertEquals(StreamExtractor.NO_AGE_LIMIT, info.getAgeLimit());
        assertEquals("Example Channel", info.getUploaderName());
        assertEquals("https://odysee.com/@Example:8", info.getUploaderUrl());
        assertEquals(List.of("news"), info.getTags());
        assertEquals("", info.getLicence());
        assertEquals(Locale.ENGLISH, info.getLanguageInfo());
        assertEquals(StreamExtractor.Privacy.PUBLIC, info.getPrivacy());
        assertEquals("", info.getHlsUrl());

        final Image thumbnail = info.getThumbnails().get(0);
        assertEquals("https://thumbnails.odycdn.com/optimize/s:390:220/quality:85/plain/"
                + "https://thumbs.example/a-video.webp", thumbnail.getUrl());
        assertEquals(390, thumbnail.getWidth());
        assertEquals(220, thumbnail.getHeight());
        assertEquals(2, info.getThumbnails().size());
        assertEquals(Image.ResolutionLevel.LOW,
                info.getUploaderAvatars().get(0).getEstimatedResolutionLevel());

        final List<VideoStream> streams = info.getVideoStreams();
        assertEquals(3, streams.size());
        assertEquals(DeliveryMethod.HLS, streams.get(0).getDeliveryMethod());
        assertEquals("1080p@4026k", streams.get(0).getResolution());
        assertEquals("https://player.odycdn.com/v6/streams/" + VIDEO_ID + "/786fca06af11/v0.m3u8",
                streams.get(0).getContent());
        assertEquals(streams.get(0).getContent(), streams.get(0).getManifestUrl());
        assertEquals(4026000, streams.get(0).getBitrate());
        assertEquals("360p@655k", streams.get(1).getResolution());
        assertEquals(DeliveryMethod.PROGRESSIVE_HTTP, streams.get(2).getDeliveryMethod());
        assertEquals(FILE_URL, streams.get(2).getContent());
        assertEquals(MediaFormat.MPEG_4, streams.get(2).getFormat());
        // 361921226 bytes in 413 seconds
        assertEquals("1080p@7010k", streams.get(2).getResolution());
        assertTrue(info.getAudioStreams().isEmpty());

        // the video itself is not among its related videos
        assertEquals(1, info.getRelatedItems().size());
        final StreamInfoItem related = (StreamInfoItem) info.getRelatedItems().get(0);
        assertEquals("Related", related.getName());
        assertEquals("https://odysee.com/@Example:8/related:a", related.getUrl());
        assertEquals(413, related.getDuration());
    }

    @Test
    void asksTheApiTheWayTheWebsiteDoes() throws Exception {
        serveTranscodedVideo(OdyseeFixtures.video());

        StreamInfo.getInfo(Odysee, VIDEO_URL);

        final Request resolve = downloader.requestsTo("?m=resolve").get(0);
        assertEquals("POST", resolve.httpMethod());
        assertEquals(List.of("application/json-rpc"), resolve.headers().get("Content-Type"));
        assertEquals(List.of(VIDEO_URI), params(resolve).getArray("urls"));
        final JsonObject get = params(downloader.requestsTo("?m=get").get(0));
        assertEquals("lbry://a-video#" + VIDEO_ID, get.getString("uri"));
        assertFalse(get.getBoolean("save_file"));
        // Odysee refuses a HEAD request for the file unless it names the website as referrer
        final Request head = downloader.requestsTo(FILE_URL).get(0);
        assertEquals("HEAD", head.httpMethod());
        assertEquals(List.of("https://odysee.com/"), head.headers().get("Referer"));
        assertEquals("claim_ids=" + VIDEO_ID, new String(
                downloader.requestsTo(OdyseeApi.REACTIONS_URL).get(0).dataToSend()));
        final String related = downloader.requestsTo(OdyseeApi.SEARCH_URL).get(0).url();
        assertTrue(related.contains("s=A+video&"), related);
        assertTrue(related.endsWith("&related_to=" + VIDEO_ID), related);
        assertEquals(List.of("aaaa"),
                params(downloader.requestsTo("?m=claim_search").get(0)).getArray("claim_ids"));
    }

    @Test
    void videoThatWasNotTranscodedOffersOnlyTheUploadedFile() throws Exception {
        downloader.onProxy("resolve", resolved(VIDEO_URI, OdyseeFixtures.video()))
                .onProxy("get", "{\"streaming_url\":\"" + FILE_URL + "\"}")
                .on(FILE_URL, request -> ok(request, ""))
                .on(OdyseeApi.REACTIONS_URL, REACTIONS)
                .on(OdyseeApi.SEARCH_URL, "[]");

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL);

        assertEquals(1, info.getVideoStreams().size());
        assertEquals(FILE_URL, info.getVideoStreams().get(0).getContent());
        assertEquals(DeliveryMethod.PROGRESSIVE_HTTP,
                info.getVideoStreams().get(0).getDeliveryMethod());
        assertTrue(info.getRelatedItems().isEmpty());
    }

    @Test
    void videoStillOpensWhenTheExtrasFail() throws Exception {
        downloader.onProxy("resolve", resolved(VIDEO_URI, OdyseeFixtures.video()))
                .onProxy("get", "{\"streaming_url\":\"" + FILE_URL + "\"}")
                .on(FILE_URL, request -> status(request, 401))
                .on(OdyseeApi.REACTIONS_URL, request -> status(request, 500))
                .on(OdyseeApi.SEARCH_URL, request -> status(request, 500));

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL);

        assertEquals(1, info.getVideoStreams().size());
        assertEquals(FILE_URL, info.getVideoStreams().get(0).getContent());
        assertEquals(-1, info.getLikeCount());
        assertEquals(-1, info.getDislikeCount());
        assertTrue(info.getRelatedItems().isEmpty());
        // the related videos are the one extra whose failure is reported
        assertEquals(1, info.getErrors().size());
    }

    @Test
    void audioIsOfferedAsAnAudioStream() throws Exception {
        final JsonObject audio = OdyseeFixtures.audio();
        downloader.onProxy("resolve", resolved("lbry://@Example#8/a-podcast#e", audio))
                .onProxy("get", "{\"streaming_url\":\"https://player.odycdn.com/audio.mp4\"}")
                .on(OdyseeApi.REACTIONS_URL, request -> status(request, 500))
                .on(OdyseeApi.SEARCH_URL, "[]");

        final StreamInfo info = StreamInfo.getInfo(Odysee,
                "https://odysee.com/@Example:8/a-podcast:e");

        assertEquals(StreamType.AUDIO_STREAM, info.getStreamType());
        assertEquals(2570, info.getDuration());
        assertTrue(info.getVideoStreams().isEmpty());
        final AudioStream stream = info.getAudioStreams().get(0);
        assertEquals("https://player.odycdn.com/audio.mp4", stream.getContent());
        assertEquals(MediaFormat.MP3, stream.getFormat());
        // 53970000 bytes in 2570 seconds
        assertEquals(168, stream.getAverageBitrate());
        // an audio file is not asked about with HEAD: it has no transcoded renditions
        assertTrue(downloader.requestsTo("audio.mp4").isEmpty());
    }

    @Test
    void livestreamOnAirPlaysItsHlsPlaylist() throws Exception {
        final JsonObject live = OdyseeFixtures.livestream();
        downloader.onProxy("resolve", resolved("lbry://@Example#8/a-livestream#d", live))
                .on(OdyseeApi.LIVESTREAM_URL + "/is_live?channel_claim_id=" + CHANNEL_ID,
                        "{\"success\":true,\"error\":null,\"data\":{\"Live\":true,"
                                + "\"VideoURL\":\"https://cloud.odysee.live/content/x/master.m3u8\","
                                + "\"ActiveClaim\":{\"ClaimID\":\""
                                + live.getString("claim_id") + "\"}}}")
                .on(OdyseeApi.REACTIONS_URL, request -> status(request, 500))
                .on(OdyseeApi.SEARCH_URL, "[]");

        final StreamInfo info = StreamInfo.getInfo(Odysee,
                "https://odysee.com/@Example:8/a-livestream:d");

        assertEquals(StreamType.LIVE_STREAM, info.getStreamType());
        assertEquals("https://cloud.odysee.live/content/x/master.m3u8", info.getHlsUrl());
        assertTrue(info.getVideoStreams().isEmpty());
        assertEquals(0, info.getDuration());
        assertTrue(downloader.requestsTo("?m=get").isEmpty());
    }

    @Test
    void livestreamThatIsNotOnAirIsNotAvailable() {
        final JsonObject live = OdyseeFixtures.livestream();
        downloader.onProxy("resolve", resolved("lbry://@Example#8/a-livestream#d", live));
        final String url = "https://odysee.com/@Example:8/a-livestream:d";

        // off air
        downloader.on(OdyseeApi.LIVESTREAM_URL, "{\"success\":true,\"error\":null,\"data\":"
                + "{\"Live\":false,\"VideoURL\":\"https://cloud.odysee.live/x/master.m3u8\","
                + "\"ActiveClaim\":{\"ClaimID\":\"" + live.getString("claim_id") + "\"}}}");
        assertThrows(ContentNotAvailableException.class, () -> StreamInfo.getInfo(Odysee, url));
    }

    @Test
    void livestreamPageOfAChannelThatBroadcastsSomethingElseIsNotAvailable() {
        downloader.onProxy("resolve", resolved("lbry://@Example#8/a-livestream#d",
                        OdyseeFixtures.livestream()))
                .on(OdyseeApi.LIVESTREAM_URL, "{\"success\":true,\"error\":null,\"data\":"
                        + "{\"Live\":true,\"VideoURL\":\"https://cloud.odysee.live/x/master.m3u8\","
                        + "\"ActiveClaim\":{\"ClaimID\":\"another-claim\"}}}");

        assertThrows(ContentNotAvailableException.class, () -> StreamInfo.getInfo(Odysee,
                "https://odysee.com/@Example:8/a-livestream:d"));
    }

    @Test
    void paidAndMembersOnlyVideosAreReportedWithoutAskingForTheFile() {
        for (final String[] tags : new String[][]{
                {"c:members-only"}, {"c:purchase", "c:purchase:5.00"}, {"c:rental:2.00:86400"}}) {
            downloader = new OdyseeFakeDownloader()
                    .onProxy("resolve", resolved(VIDEO_URI,
                            withTags(OdyseeFixtures.video(), tags)));
            NewPipe.init(downloader);
            assertThrows(PaidContentException.class,
                    () -> StreamInfo.getInfo(Odysee, VIDEO_URL), tags[0]);
            assertEquals(1, downloader.requests.size());
        }

        final JsonObject withFee = OdyseeFixtures.video();
        final JsonObject fee = new JsonObject();
        fee.put("amount", "1");
        fee.put("currency", "LBC");
        withFee.getObject("value").put("fee", fee);
        downloader = new OdyseeFakeDownloader().onProxy("resolve", resolved(VIDEO_URI, withFee));
        NewPipe.init(downloader);
        assertThrows(PaidContentException.class, () -> StreamInfo.getInfo(Odysee, VIDEO_URL));
    }

    @Test
    void fileThatOdyseeWillNotHandOutIsReportedAsPaid() {
        downloader.onProxy("resolve", resolved(VIDEO_URI, OdyseeFixtures.video()))
                .onProxyError("get", "authentication required");

        assertThrows(PaidContentException.class, () -> StreamInfo.getInfo(Odysee, VIDEO_URL));
    }

    @Test
    void missingClaimIsNotAvailable() {
        downloader.onProxy("resolve", "{\"" + VIDEO_URI + "\":{\"error\":{\"name\":\"NOT_FOUND\","
                + "\"text\":\"Could not find claim.\"}}}");

        final ContentNotAvailableException error = assertThrows(
                ContentNotAvailableException.class, () -> StreamInfo.getInfo(Odysee, VIDEO_URL));
        assertEquals("Could not find claim.", error.getMessage());
    }

    @Test
    void claimsThatAreNotMediaAreNotSupported() {
        for (final JsonObject claim : new JsonObject[]{
                OdyseeFixtures.document(), OdyseeFixtures.channel(), OdyseeFixtures.playlist()}) {
            downloader = new OdyseeFakeDownloader().onProxy("resolve", resolved(VIDEO_URI, claim));
            NewPipe.init(downloader);
            assertThrows(ContentNotSupportedException.class,
                    () -> StreamInfo.getInfo(Odysee, VIDEO_URL), claim.getString("name"));
        }
    }

    @Test
    void repostPlaysTheVideoItReposts() throws Exception {
        serveTranscodedVideo(OdyseeFixtures.repostOf(OdyseeFixtures.video()));

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL);

        assertEquals("A video", info.getName());
        assertEquals("lbry://a-video#" + VIDEO_ID,
                params(downloader.requestsTo("?m=get").get(0)).getString("uri"));
    }

    @Test
    void matureAndUnlistedVideosSaySo() throws Exception {
        serveTranscodedVideo(withTags(OdyseeFixtures.video(), "mature", "c:unlisted", "art"));

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL);

        assertEquals(18, info.getAgeLimit());
        assertEquals(StreamExtractor.Privacy.UNLISTED, info.getPrivacy());
        assertEquals(List.of("mature", "art"), info.getTags());
    }

    @Test
    void videoWithoutAChannelIsAnonymous() throws Exception {
        final JsonObject claim = OdyseeFixtures.video();
        claim.remove("signing_channel");
        claim.getObject("value").remove("release_time");
        claim.getObject("value").remove("languages");
        serveTranscodedVideo(claim);

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL);

        assertEquals("Anonymous", info.getUploaderName());
        assertEquals("", info.getUploaderUrl());
        assertTrue(info.getUploaderAvatars().isEmpty());
        // without a release time the date is the one the claim was made on
        assertEquals(Instant.ofEpochSecond(1780000100), info.getUploadDate().getInstant());
        assertNull(info.getLanguageInfo());
    }
}
