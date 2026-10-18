package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.video.media.ResultsStreamVideoMedia;
import com.grack.nanojson.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.VideoStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("offline")
class BitchuteStreamFormatTest {

    @Test
    void marksHlsMediaAsManifest() throws Exception {
        final String url = "https://cdn.example/video/master.m3u8?token=one";
        final VideoStream stream = BitchuteStreamExtractor.buildVideoStream(
                media("application/x-mpegURL", url));

        assertEquals(DeliveryMethod.HLS, stream.getDeliveryMethod());
        assertEquals(url, stream.getManifestUrl());
        assertEquals(VideoStream.RESOLUTION_UNKNOWN, stream.getResolution());
        assertNull(stream.getFormat());
    }

    @Test
    void keepsMp4MediaProgressive() throws Exception {
        final VideoStream stream = BitchuteStreamExtractor.buildVideoStream(
                media("video/mp4", "https://cdn.example/video/file.mp4?token=two"));

        assertEquals(DeliveryMethod.PROGRESSIVE_HTTP, stream.getDeliveryMethod());
        assertNull(stream.getManifestUrl());
        assertEquals(MediaFormat.MPEG_4, stream.getFormat());
        assertEquals(VideoStream.RESOLUTION_UNKNOWN, stream.getResolution());
    }

    @Test
    void detectsProgressiveFormatFromMimeType() throws Exception {
        final VideoStream stream = BitchuteStreamExtractor.buildVideoStream(
                media("Video/WebM; codecs=vp9", "https://cdn.example/video/file.bin"));

        assertEquals(MediaFormat.WEBM, stream.getFormat());
    }

    @Test
    void fallsBackToProgressiveUrlSuffix() throws Exception {
        final VideoStream stream = BitchuteStreamExtractor.buildVideoStream(
                media(null, "https://cdn.example/video/file.3gp#fragment"));

        assertEquals(MediaFormat.v3GPP, stream.getFormat());
    }

    @Test
    void recognizesStandardHlsMimeType() throws Exception {
        final VideoStream stream = BitchuteStreamExtractor.buildVideoStream(
                media("application/vnd.apple.mpegurl", "https://cdn.example/manifest"));

        assertEquals(DeliveryMethod.HLS, stream.getDeliveryMethod());
        assertNull(stream.getFormat());
    }

    @Test
    void recognizesParameterizedHlsMimeTypesWithoutAPlaylistSuffix() throws Exception {
        final String url = "https://cdn.example/manifest?token=one";
        for (final String type : new String[]{
                "application/vnd.apple.mpegurl; charset=utf-8",
                " Application/X-MpegURL ; charset=UTF-8"}) {
            final VideoStream stream = BitchuteStreamExtractor.buildVideoStream(media(type, url));

            assertEquals(DeliveryMethod.HLS, stream.getDeliveryMethod(), type);
            assertEquals(url, stream.getManifestUrl(), type);
            assertNull(stream.getFormat(), type);
        }
    }

    private static ResultsStreamVideoMedia media(final String type, final String url) {
        return new ResultsStreamVideoMedia(JsonObject.builder()
                .value("media_type", type)
                .value("media_url", url)
                .value("video_id", "UGlrF9o9b-Q")
                .done());
    }
}
