package org.schabi.newpipe.extractor.services.soundcloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("offline")
class SoundcloudClientDiscoveryTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private final List<String> requests = new ArrayList<>();
    private String mobile = page("{\"runtimeConfig\":{\"clientId\":\"" + ID + "\"}}");
    private int status = 200;
    private IOException failure;

    private static String page(final String json) {
        return "<script id='__NEXT_DATA__' type='application/json'>" + json + "</script>";
    }

    @BeforeEach
    void setUp() throws Exception {
        clearClientId();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) throws IOException {
                requests.add(request.url());
                if (request.url().equals("https://m.soundcloud.com/discover")) {
                    if (failure != null) {
                        throw failure;
                    }
                    return new Response(status, "", Map.of(), mobile, request.url());
                }
                final String body;
                if (request.url().equals("https://soundcloud.com")) {
                    body = "<script src='https://a.sndcdn.com/assets/app.js'></script>";
                } else if (request.url().equals("https://a.sndcdn.com/assets/app.js")) {
                    body = "var config={unused:0,client_id:\"" + ID + "\"};";
                } else {
                    throw new AssertionError("Unexpected request: " + request.url());
                }
                return new Response(200, "", Map.of(), body, request.url());
            }
        });
    }

    @AfterEach
    void clearClientId() throws Exception {
        final var field = SoundcloudParsingHelper.class.getDeclaredField("clientId");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void readsStructuredConfigurationAndCachesTheId() throws Exception {
        assertEquals(ID, SoundcloudParsingHelper.clientId());
        assertEquals(ID, SoundcloudParsingHelper.clientId());
        assertEquals(List.of("https://m.soundcloud.com/discover"), requests);
    }

    @Test
    void doesNotUseClientIdMentionedInUnrelatedContent() throws Exception {
        mobile = page("{\"props\":{\"clientId\":\"untrusted\"}}");
        assertFallback();
    }

    @Test
    void rejectsQueryInjectionInClientId() throws Exception {
        mobile = page("{\"runtimeConfig\":{\"clientId\":\"x&limit=999\"}}");
        assertFallback();
    }

    @Test
    void malformedConfigurationFallsBack() throws Exception {
        mobile = page("not json");
        assertFallback();
    }

    @Test
    void missingConfigurationFallsBack() throws Exception {
        mobile = "<html>New layout</html>";
        assertFallback();
    }

    @Test
    void errorResponseCannotSupplyAnId() throws Exception {
        status = 503;
        assertFallback();
    }

    @Test
    void networkFailureFallsBack() throws Exception {
        failure = new IOException("unavailable");
        assertFallback();
    }

    @Test
    void socketTimeoutFallsBack() throws Exception {
        failure = new java.net.SocketTimeoutException("timed out");
        assertFallback();
    }

    @Test
    void wrongConfigurationTypeFallsBack() throws Exception {
        mobile = page("{\"runtimeConfig\":\"unexpected\"}");
        assertFallback();
    }

    @Test
    void cancellationDoesNotStartMoreRequests() {
        failure = new InterruptedIOException("cancelled");
        assertThrows(InterruptedIOException.class, SoundcloudParsingHelper::clientId);
        assertEquals(1, requests.size());
    }

    private void assertFallback() throws Exception {
        assertEquals(ID, SoundcloudParsingHelper.clientId());
        assertEquals(3, requests.size());
    }
}
