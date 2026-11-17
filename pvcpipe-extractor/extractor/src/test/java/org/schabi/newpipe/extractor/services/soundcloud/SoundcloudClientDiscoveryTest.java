package org.schabi.newpipe.extractor.services.soundcloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.HttpResponseException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;

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
    private static final String ASSET = "https://a.sndcdn.com/assets/app.js";
    private static final String STALE_ASSET = "https://a.sndcdn.com/assets/stale.js";
    private final List<String> requests = new ArrayList<>();
    private String mobile = page("{\"runtimeConfig\":{\"clientId\":\"" + ID + "\"}}");
    private int status = 200;
    private int desktopStatus = 200;
    private int assetStatus = 206;
    private Integer staleAssetStatus;
    private int resolveStatus = 200;
    private String resolveBody = "{\"id\":123,\"policy\":\"ALLOW\"}";
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
                if (request.url().equals("https://soundcloud.com")) {
                    String body = "<script src='" + ASSET + "'></script>";
                    if (staleAssetStatus != null) {
                        body += "<script src='" + STALE_ASSET + "'></script>";
                    }
                    return new Response(desktopStatus, "", Map.of(), body, request.url());
                }
                if (request.url().equals(ASSET)) {
                    return new Response(assetStatus, "", Map.of(),
                            "var config={unused:0,client_id:\"" + ID + "\"};", request.url());
                }
                if (request.url().equals(STALE_ASSET) && staleAssetStatus != null) {
                    return new Response(staleAssetStatus, "", Map.of(),
                            "var config={unused:0,client_id:\"wrong\"};", request.url());
                }
                if (request.url().startsWith("https://api-v2.soundcloud.com/resolve?")) {
                    return new Response(resolveStatus, "", Map.of(), resolveBody, request.url());
                }
                throw new AssertionError("Unexpected request: " + request.url());
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
    void failedDesktopPageDoesNotSelectScriptsFromItsBody() {
        status = 404;
        desktopStatus = 503;
        final var error = assertThrows(HttpResponseException.class,
                SoundcloudParsingHelper::clientId);
        assertEquals(503, error.responseCode());
        assertEquals(2, requests.size());
    }

    @Test
    void removedBundleDoesNotHideWorkingPartialResponse() throws Exception {
        status = 404;
        staleAssetStatus = 410;
        assertEquals(ID, SoundcloudParsingHelper.clientId());
        assertEquals(List.of(STALE_ASSET, ASSET), requests.subList(2, 4));
    }

    @Test
    void failedBundleCannotSupplyOrCacheAnId() throws Exception {
        status = 404;
        assetStatus = 503;
        final var error = assertThrows(HttpResponseException.class,
                SoundcloudParsingHelper::clientId);
        assertEquals(503, error.responseCode());

        assetStatus = 206;
        assertEquals(ID, SoundcloudParsingHelper.clientId());
    }

    @Test
    void apiResolvePreservesHttpErrorsAndStillParsesSuccessfulBodies() throws Exception {
        final String url = "https://soundcloud.com/fixture/track";
        resolveStatus = 403;
        final var error = assertThrows(HttpResponseException.class,
                () -> SoundcloudParsingHelper.resolveFor(NewPipe.getDownloader(), url));
        assertEquals(403, error.responseCode());

        resolveStatus = 200;
        assertEquals(123, SoundcloudParsingHelper.resolveFor(NewPipe.getDownloader(), url)
                .getInt("id"));
        resolveBody = "<html>Invalid JSON</html>";
        assertThrows(ParsingException.class,
                () -> SoundcloudParsingHelper.resolveFor(NewPipe.getDownloader(), url));
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
