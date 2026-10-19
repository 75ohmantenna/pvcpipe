package org.schabi.newpipe.util.text;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InternalUrlsHandlerTimestampTest {
    @Test
    public void ordinaryTimestampsAreKept() {
        assertEquals(0, InternalUrlsHandler.parseTimestampSeconds("0"));
        assertEquals(90, InternalUrlsHandler.parseTimestampSeconds("90"));
        assertEquals(90, InternalUrlsHandler.parseTimestampSeconds("0000090"));
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("2147483647"));
    }

    @Test
    public void oversizedTimestampsAreClampedInsteadOfThrowing() {
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("2147483648"));
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("99999999999"));
        // longer than any long, as an uploader can write in a description
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("9".repeat(400)));
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("000" + "1".repeat(30)));
    }

    @Test
    public void theClampedValueStillFitsThePlayersMillisecondSeek() {
        final int seconds = InternalUrlsHandler.parseTimestampSeconds("9".repeat(40));
        // Player.playFromTimestamp seeks to seconds * 1000L
        assertEquals(2_147_483_647_000L, seconds * 1000L);
    }
}
