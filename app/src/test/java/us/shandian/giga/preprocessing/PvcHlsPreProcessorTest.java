package us.shandian.giga.preprocessing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import us.shandian.giga.preprocessing.PvcHlsPreProcessor.FetchWithDownloaderImpl;

public class PvcHlsPreProcessorTest {
    // An AES-128 key is 16 arbitrary bytes. These are not valid UTF-8.
    private static final byte[] KEY = {(byte) 0xff, (byte) 0xfe, (byte) 0x80, 0x00, 0x7f,
            (byte) 0xc3, 0x28, (byte) 0xa0, (byte) 0xa1, (byte) 0xe2, (byte) 0x82, 0x28,
            (byte) 0xf0, (byte) 0x90, 0x0a, 0x0d};

    private final AtomicBoolean bodyClosed = new AtomicBoolean();
    private final AtomicReference<Request> sent = new AtomicReference<>();

    // A client that answers every request itself, without any network.
    private OkHttpClient clientAnswering(final int status, final byte[] body) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            sent.set(chain.request());
            return new Response.Builder().request(chain.request())
                    .protocol(Protocol.HTTP_1_1).code(status).message("")
                    .body(new ClosingBody(body)).build();
        }).build();
    }

    private final class ClosingBody extends ResponseBody {
        private final byte[] bytes;

        private ClosingBody(final byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public MediaType contentType() {
            return null;
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @NonNull
        @Override
        public BufferedSource source() {
            return Okio.buffer(new ForwardingSource(new Buffer().write(bytes)) {
                @Override
                public void close() throws IOException {
                    bodyClosed.set(true);
                    super.close();
                }
            });
        }
    }

    @Test
    public void aBinaryKeyArrivesUnchanged() throws IOException {
        final FetchWithDownloaderImpl fetcher =
                new FetchWithDownloaderImpl(clientAnswering(200, KEY));
        try (InputStream stream =
                     fetcher.fetchContent(URI.create("https://example.test/hls/key.bin"))) {
            assertArrayEquals(KEY, stream.readAllBytes());
        }
        assertTrue(bodyClosed.get());
    }

    @Test
    public void anErrorPageIsNotAPlaylist() {
        for (final int status : new int[]{401, 403, 404, 429, 500}) {
            bodyClosed.set(false);
            final FetchWithDownloaderImpl fetcher = new FetchWithDownloaderImpl(
                    clientAnswering(status, "try again later".getBytes()));
            final IOException error = assertThrows(IOException.class, () ->
                    fetcher.fetchContent(URI.create("https://example.test/hls/master.m3u8")));
            assertTrue(error.getMessage().contains(String.valueOf(status)));
            assertTrue(bodyClosed.get());
        }
    }

    @Test
    public void odyseeHostsGetTheirReferrer() throws IOException {
        new FetchWithDownloaderImpl(clientAnswering(200, new byte[0])).fetchContent(
                URI.create("https://player.odycdn.com/v6/streams/abc/def/master.m3u8")).close();
        assertEquals("https://odysee.com/", sent.get().header("Referer"));

        new FetchWithDownloaderImpl(clientAnswering(200, new byte[0])).fetchContent(
                URI.create("https://example.test/hls/master.m3u8")).close();
        assertEquals(null, sent.get().header("Referer"));
    }
}
