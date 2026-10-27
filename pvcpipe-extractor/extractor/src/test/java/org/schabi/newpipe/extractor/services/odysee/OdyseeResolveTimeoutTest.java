package org.schabi.newpipe.extractor.services.odysee;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Odysee;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

@Tag("offline")
class OdyseeResolveTimeoutTest {
    private static final String URI = "lbry://@Example#8";
    private OdyseeFakeDownloader downloader;

    @BeforeEach
    void setUp() {
        downloader = new OdyseeFakeDownloader();
        NewPipe.init(downloader);
        OdyseeApi.clearCaches();
    }

    @Test
    void channelExtractionRecoversFromOneResolveTimeoutAndCachesTheClaim() throws Exception {
        final List<Request> attempts = new ArrayList<>();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) throws IOException {
                attempts.add(request);
                assertEquals(OdyseeApi.PROXY_URL + "?m=resolve", request.url());
                if (attempts.size() == 1) {
                    throw new SocketTimeoutException("waiting for response headers");
                }
                return OdyseeFakeDownloader.ok(request, "{\"result\":"
                        + OdyseeFixtures.resolved(URI, OdyseeFixtures.channel()) + "}");
            }
        });

        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8");

        assertEquals("Example Channel", channel.getName());
        assertTrue(channel.getErrors().isEmpty());
        assertEquals(2, attempts.size());
        assertEquals("POST", attempts.get(0).httpMethod());
        assertArrayEquals(attempts.get(0).dataToSend(), attempts.get(1).dataToSend());
        assertEquals(attempts.get(0).headers(), attempts.get(1).headers());
        assertEquals("@Example", OdyseeApi.resolveCached(URI).getString("name"));
        assertEquals(2, attempts.size(), "Successful retry populates the existing claim cache");
    }

    @Test
    void aSecondTimeoutReachesTheCallerWithoutAnotherAttempt() {
        final SocketTimeoutException timeout = new SocketTimeoutException("timeout");
        downloader.onFailure(OdyseeApi.PROXY_URL, () -> timeout);

        assertSame(timeout, assertThrows(SocketTimeoutException.class,
                () -> OdyseeApi.resolve(URI)));
        assertEquals(2, downloader.requests.size());
    }

    @Test
    void ordinaryIoFailuresAreNotRetried() {
        final IOException failure = new IOException("connection failed");
        downloader.onFailure(OdyseeApi.PROXY_URL, () -> failure);

        assertSame(failure, assertThrows(IOException.class, () -> OdyseeApi.resolve(URI)));
        assertEquals(1, downloader.requests.size());
    }

    @Test
    void interruptionIsNotRetried() {
        final InterruptedIOException failure = new InterruptedIOException("cancelled");
        downloader.onFailure(OdyseeApi.PROXY_URL, () -> failure);

        assertSame(failure, assertThrows(InterruptedIOException.class,
                () -> OdyseeApi.resolve(URI)));
        assertEquals(1, downloader.requests.size());
    }

    @Test
    void anInterruptedThreadDoesNotRetryATimeout() {
        final SocketTimeoutException failure = new SocketTimeoutException("timeout");
        downloader.onFailure(OdyseeApi.PROXY_URL, () -> failure);
        Thread.currentThread().interrupt();
        try {
            assertSame(failure, assertThrows(SocketTimeoutException.class,
                    () -> OdyseeApi.resolve(URI)));
            assertEquals(1, downloader.requests.size());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void apiErrorsAreNotRetried() {
        downloader.onProxyError("resolve", "temporarily unavailable");

        assertThrows(ExtractionException.class, () -> OdyseeApi.resolve(URI));
        assertEquals(1, downloader.requests.size());
    }

    @Test
    void httpErrorsAreNotRetried() {
        downloader.on(OdyseeApi.PROXY_URL, request -> OdyseeFakeDownloader.status(request, 503));

        assertThrows(IOException.class, () -> OdyseeApi.resolve(URI));
        assertEquals(1, downloader.requests.size());
    }
}
