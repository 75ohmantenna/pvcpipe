/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Checks this app's ExoPlayer DASH parser, not an installed receiver's androidx.media3 parser. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class CastDashReceiverCompatibilityTest {
    @Test
    public void pairedTracksParseWithDistinctAdaptationIdsAndRetainTheirIndexes() throws Exception {
        final String merged = CastManifest.mergeDash(
                track("video/mp4", "avc1.64001f", "video", "100-200"),
                track("audio/mp4", "mp4a.40.2", "audio", "50-99"));
        final var manifest = new DashManifestParser().parse(
                Uri.parse("http://phone/manifest.mpd"),
                new ByteArrayInputStream(merged.getBytes(StandardCharsets.UTF_8)));
        final var groups = manifest.getPeriod(0).adaptationSets;
        assertEquals(2, groups.size());
        final var video = groups.get(0);
        final var audio = groups.get(1);
        assertEquals(C.TRACK_TYPE_VIDEO, video.type);
        assertEquals(C.TRACK_TYPE_AUDIO, audio.type);
        assertNotEquals(video.id, audio.id);
        final var videoTrack = video.representations.get(0);
        final var audioTrack = audio.representations.get(0);
        assertEquals("video/avc", videoTrack.format.sampleMimeType);
        assertEquals("audio/mp4a-latm", audioTrack.format.sampleMimeType);
        assertEquals(100, videoTrack.getIndexUri().start);
        assertEquals(101, videoTrack.getIndexUri().length);
        assertEquals(50, audioTrack.getIndexUri().start);
        assertEquals(50, audioTrack.getIndexUri().length);
    }

    private static String track(final String mime, final String codec, final String id,
                                final String index) {
        return "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" "
                + "mediaPresentationDuration=\"PT10S\" minBufferTime=\"PT1S\">"
                + "<Period><AdaptationSet id=\"1\" mimeType=\"" + mime + "\">"
                + "<Representation id=\"" + id + "\" codecs=\"" + codec + "\" bandwidth=\"128000\">"
                + "<BaseURL>http://phone/" + id + "</BaseURL>"
                + "<SegmentBase indexRange=\"" + index + "\"><Initialization range=\"0-49\"/>"
                + "</SegmentBase></Representation></AdaptationSet></Period></MPD>";
    }
}
