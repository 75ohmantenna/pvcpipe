package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper;

import java.util.List;
import java.util.Map;

public class StreamItemAdapterSizeTest {
    private static Response response(final int status, final String contentLength) {
        final Map<String, List<String>> headers = contentLength == null
                ? Map.of() : Map.of("Content-Length", List.of(contentLength));
        return new Response(status, "", headers, "", "https://example.test/video.mp4");
    }

    @Test
    public void onlyASuccessfulAnswerDescribesTheStream() {
        assertTrue(StreamInfoWrapper.isSuccessful(response(200, "1")));
        assertTrue(StreamInfoWrapper.isSuccessful(response(206, "1")));
        // 45 is the length of the error page Odysee's file host sends with a 401
        assertFalse(StreamInfoWrapper.isSuccessful(response(401, "45")));
        assertFalse(StreamInfoWrapper.isSuccessful(response(403, "162")));
        assertFalse(StreamInfoWrapper.isSuccessful(response(404, "0")));
        assertFalse(StreamInfoWrapper.isSuccessful(response(302, "0")));
    }

    @Test
    public void aLengthThatCannotBeReadIsNoLength() {
        assertEquals(1628769, StreamInfoWrapper.contentLengthOf(response(200, "1628769")));
        assertEquals(12, StreamInfoWrapper.contentLengthOf(response(200, " 12 ")));
        assertEquals(-1, StreamInfoWrapper.contentLengthOf(response(200, null)));
        assertEquals(-1, StreamInfoWrapper.contentLengthOf(response(200, "")));
        assertEquals(-1, StreamInfoWrapper.contentLengthOf(response(200, "chunked")));
    }
}
