/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.extractor.services.soundcloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.utils.Utils;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.SoundCloud;

/** Exercises public factory, fetch and stream extraction paths without any network transport. */
@Tag("offline")
class SoundcloudTranscodingTest {
    private static final String CLIENT_ID = "0123456789abcdef0123456789abcdef";
    private static final String PUBLIC = "https://soundcloud.com/fixture-artist/fixture-track";
    private static final String WIDGET = "https://api-widget.soundcloud.com/resolve?url="
            + Utils.encodeUrlUtf8(PUBLIC) + "&format=json&client_id=" + CLIENT_ID;
    private static final String EMBED = "https://w.soundcloud.com/player/?url="
            + Utils.encodeUrlUtf8("https://api.soundcloud.com/tracks/123");
    private static final String RESOLVE = "https://api-v2.soundcloud.com/resolve?url="
            + Utils.encodeUrlUtf8(PUBLIC) + "&client_id=" + CLIENT_ID;

    private final Map<Field, Object> previousState = new LinkedHashMap<>();
    private final Map<String, String> responses = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        save(NewPipe.class, "downloader");
        save(NewPipe.class, "preferredLocalization");
        save(NewPipe.class, "preferredContentCountry");
        save(SoundcloudParsingHelper.class, "clientId").set(null, CLIENT_ID);
        responses.put(WIDGET, "{\"id\":123}");
        responses.put(EMBED, "<link rel='canonical' href='" + PUBLIC + "'>");
        // Deliberately do not use DownloaderFactory: its default can perform real requests.
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                assertEquals("GET", request.httpMethod());
                requests.add(request.url());
                final String body = responses.get(request.url());
                if (body == null) {
                    throw new AssertionError("Unexpected request: " + request.url());
                }
                return new Response(200, "", Map.of(), body, request.url());
            }
        });
    }

    @AfterEach
    void restoreState() throws Exception {
        for (final Map.Entry<Field, Object> entry : previousState.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
    }

    @Test
    void aac96OnlyTrackProducesPlayableM4aHlsStream() throws Exception {
        final String preset = "aac_96k";
        final List<AudioStream> streams = extract(true, transcoding(preset, "hls"));

        assertEquals(1, streams.size());
        assertStream(streams.get(0), preset, MediaFormat.M4A, 96, DeliveryMethod.HLS);
        assertEquals(List.of(WIDGET, EMBED, RESOLVE, endpoint(preset)), requests);
    }

    @Test
    void existingFormatsAndBothAacBitratesRemainDistinctWhileEncryptedIsSkipped()
            throws Exception {
        final String encrypted = "{\"preset\":\"aac_96k\","
                + "\"url\":\"https://fixture.invalid/encrypted\","
                + "\"format\":{\"protocol\":\"ctr-encrypted-hls\"}}";
        final List<AudioStream> streams = extract(true,
                transcoding("mp3_0_1", "progressive"),
                transcoding("opus_0_0", "hls"),
                transcoding("aac_160k", "hls"),
                encrypted,
                transcoding("aac_96k", "hls"),
                transcoding("unknown", "hls"));

        assertEquals(4, streams.size());
        assertStream(streams.get(0), "mp3_0_1", MediaFormat.MP3, 128, DeliveryMethod.PROGRESSIVE_HTTP);
        assertStream(streams.get(1), "opus_0_0", MediaFormat.OPUS, 64, DeliveryMethod.HLS);
        assertStream(streams.get(2), "aac_160k", MediaFormat.M4A, 160, DeliveryMethod.HLS);
        assertStream(streams.get(3), "aac_96k", MediaFormat.M4A, 96, DeliveryMethod.HLS);
        assertEquals(List.of(WIDGET, EMBED, RESOLVE, endpoint("mp3_0_1"),
                endpoint("opus_0_0"), endpoint("aac_160k"), endpoint("aac_96k"),
                endpoint("unknown")), requests);
    }

    @Test
    void nonStreamableTrackDoesNotResolveAac96Transcoding() throws Exception {
        assertTrue(extract(false, transcoding("aac_96k", "hls")).isEmpty());
        assertEquals(List.of(WIDGET, EMBED, RESOLVE), requests);
    }

    private List<AudioStream> extract(final boolean streamable, final String... transcodings)
            throws Exception {
        responses.put(RESOLVE, "{\"id\":123,\"policy\":\"ALLOW\",\"streamable\":"
                + streamable + ",\"track_authorization\":\"fixture-token\","
                + "\"media\":{\"transcodings\":[" + String.join(",", transcodings) + "]}}");
        final var extractor = SoundCloud.getStreamExtractor(PUBLIC);
        extractor.fetchPage();
        return extractor.getAudioStreams();
    }

    private String transcoding(final String preset, final String protocol) {
        responses.put(endpoint(preset), "{\"url\":\"" + content(preset) + "\"}");
        return "{\"preset\":\"" + preset + "\",\"url\":\"https://fixture.invalid/"
                + preset + "\",\"format\":{\"protocol\":\"" + protocol + "\"}}";
    }

    private static String endpoint(final String preset) {
        return "https://fixture.invalid/" + preset + "?client_id=" + CLIENT_ID
                + "&track_authorization=fixture-token";
    }

    private static String content(final String preset) {
        return "https://media.invalid/" + preset;
    }

    private static void assertStream(final AudioStream stream, final String preset,
                                     final MediaFormat format, final int bitrate,
                                     final DeliveryMethod delivery) {
        assertEquals(preset, stream.getId());
        assertEquals(content(preset), stream.getContent());
        assertTrue(stream.isUrl());
        assertEquals(format, stream.getFormat());
        assertEquals(bitrate, stream.getAverageBitrate());
        assertEquals(delivery, stream.getDeliveryMethod());
    }

    private Field save(final Class<?> owner, final String name) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        previousState.put(field, field.get(null));
        return field;
    }
}
