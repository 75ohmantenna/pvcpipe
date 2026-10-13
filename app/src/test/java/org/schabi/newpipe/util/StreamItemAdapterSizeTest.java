package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper;

import java.io.IOException;
import java.util.ArrayList;
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

    private static AudioStream stream(final String name) {
        return new AudioStream.Builder().setId(name)
                .setContent("https://example.test/" + name + ".m4a", true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(128).build();
    }

    @Test
    public void aStreamThatFailsDoesNotCostTheOthersTheirSize() {
        final AudioStream unreachable = stream("unreachable");
        final AudioStream refused = stream("refused");
        final AudioStream limited = stream("limited");
        final AudioStream good = stream("good");
        final AudioStream silent = stream("silent");
        final AudioStream alsoGood = stream("also-good");
        final StreamInfoWrapper<AudioStream> wrapper = new StreamInfoWrapper<>(
                List.of(unreachable, refused, limited, good, silent, alsoGood), null);
        final List<String> asked = new ArrayList<>();

        final boolean changed = StreamInfoWrapper.fetchMoreInfo(null, wrapper, url -> {
            asked.add(url);
            if (url.contains("unreachable")) {
                throw new IOException("connection closed");
            } else if (url.contains("refused")) {
                // the error page has a length of its own
                return response(403, "162");
            } else if (url.contains("limited")) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            } else if (url.contains("silent")) {
                return response(200, null);
            } else if (url.contains("also-good")) {
                return response(206, "2048");
            }
            return response(200, "1024");
        });

        assertTrue(changed);
        // every stream was asked, also the ones after a failure
        assertEquals(6, asked.size());
        assertEquals(1024, wrapper.getSizeInBytes(good));
        assertEquals(2048, wrapper.getSizeInBytes(alsoGood));
        for (final AudioStream unknown : List.of(unreachable, refused, limited, silent)) {
            assertTrue(wrapper.getSizeInBytes(unknown) < 0);
        }
    }

    @Test
    public void nothingChangesWhenNoServerAnswers() {
        final StreamInfoWrapper<AudioStream> wrapper =
                new StreamInfoWrapper<>(List.of(stream("a"), stream("b")), null);

        assertFalse(StreamInfoWrapper.fetchMoreInfo(null, wrapper, url -> {
            throw new IOException("offline");
        }));
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
