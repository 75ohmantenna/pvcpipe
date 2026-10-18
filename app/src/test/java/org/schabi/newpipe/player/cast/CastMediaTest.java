package org.schabi.newpipe.player.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Map;

public class CastMediaTest {
    @Test
    public void mediaHandoffRejectsMissingOrAmbiguousSources() {
        assertThrows(IllegalArgumentException.class,
                () -> new CastMedia("video/mp4", null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CastMedia("video/mp4", "url", "content", Map.of()));
    }

    @Test
    public void byteRangesHandleSuffixOpenEndedAndClippedEnd() throws Exception {
        assertEquals(new CastMediaServer.ByteRange(10, 19),
                CastMediaServer.ByteRange.parse("bytes=10-19", 100));
        assertEquals(new CastMediaServer.ByteRange(10, 99),
                CastMediaServer.ByteRange.parse("bytes=10-", 100));
        assertEquals(new CastMediaServer.ByteRange(80, 99),
                CastMediaServer.ByteRange.parse("bytes=-20", 100));
        assertEquals(new CastMediaServer.ByteRange(0, 99),
                CastMediaServer.ByteRange.parse("bytes=-200", 100));
        assertEquals(new CastMediaServer.ByteRange(80, 99),
                CastMediaServer.ByteRange.parse("bytes=80-200", 100));
    }

    @Test
    public void invalidRangesCarry416AndUnsatisfiedLength() {
        for (final String value : new String[]{"bytes=100-", "bytes=40-20", "bytes=-0",
                "bytes=1-2,4-5", "bytes=-", "bytes=999999999999999999999-"}) {
            final CastMediaServer.HttpFailure failure = assertThrows(
                    CastMediaServer.HttpFailure.class,
                    () -> CastMediaServer.ByteRange.parse(value, 100));
            assertEquals(416, failure.status);
            assertEquals("bytes */100", failure.headers.get("Content-Range"));
        }
    }
}
