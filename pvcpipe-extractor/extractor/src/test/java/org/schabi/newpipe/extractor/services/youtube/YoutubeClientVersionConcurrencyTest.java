package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_REMIX_HARDCODED_CLIENT_VERSION;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYoutubeMusicClientVersion;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isHardcodedClientVersionValid;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.resetClientVersion;

@Tag("offline")
class YoutubeClientVersionConcurrencyTest {
    private static final String FIRST_VERSION = "2.20261005.01.00";
    private static final String SECOND_VERSION = "2.20261006.01.00";
    private final Map<String, Object> previousState = new HashMap<>();
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        for (final String name : new String[]{"clientVersion", "clientVersionExtracted",
                "hardcodedClientVersionValid", "youtubeMusicClientVersion"}) {
            previousState.put(name, field(name).get(null));
        }
        resetClientVersion();
        field("youtubeMusicClientVersion").set(null, null);
        field("hardcodedClientVersionValid").set(null, Optional.empty());
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Workers must finish before reset");
        for (final var entry : previousState.entrySet()) {
            field(entry.getKey()).set(null, entry.getValue());
        }
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @Test
    void concurrentWebLookupsShareOneFetch() throws Exception {
        final BlockingDownloader downloader = new BlockingDownloader(false);
        NewPipe.init(downloader);
        final CountDownLatch ready = new CountDownLatch(2);
        final Future<String> first = executor.submit(() -> {
            ready.countDown();
            return getClientVersion();
        });
        final Future<String> second = executor.submit(() -> {
            ready.countDown();
            return getClientVersion();
        });
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(downloader.entered.await(5, TimeUnit.SECONDS));
            // With serialization the second lookup waits at the cache lock, not the downloader.
            downloader.bothEntered.await(1, TimeUnit.SECONDS);
        } finally {
            downloader.release.countDown();
        }
        assertEquals(FIRST_VERSION, first.get(5, TimeUnit.SECONDS));
        assertEquals(FIRST_VERSION, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, downloader.requests.get());
    }

    @Test
    void concurrentMusicLookupsShareOneValidationRequest() throws Exception {
        final BlockingDownloader downloader = new BlockingDownloader(true);
        NewPipe.init(downloader);
        final CountDownLatch ready = new CountDownLatch(2);
        final Future<String> first = executor.submit(() -> {
            ready.countDown();
            return getYoutubeMusicClientVersion();
        });
        final Future<String> second = executor.submit(() -> {
            ready.countDown();
            return getYoutubeMusicClientVersion();
        });
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(downloader.entered.await(5, TimeUnit.SECONDS));
            downloader.bothEntered.await(1, TimeUnit.SECONDS);
        } finally {
            downloader.release.countDown();
        }
        assertEquals(WEB_REMIX_HARDCODED_CLIENT_VERSION, first.get(5, TimeUnit.SECONDS));
        assertEquals(WEB_REMIX_HARDCODED_CLIENT_VERSION, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, downloader.requests.get());
    }

    @Test
    void concurrentHardcodedValidationSharesOneRequest() throws Exception {
        final BlockingDownloader downloader = new BlockingDownloader(false);
        downloader.responseBodyOverride = "x".repeat(5001);
        NewPipe.init(downloader);
        final CountDownLatch ready = new CountDownLatch(2);
        final Future<Boolean> first = executor.submit(() -> {
            ready.countDown();
            return isHardcodedClientVersionValid();
        });
        final Future<Boolean> second = executor.submit(() -> {
            ready.countDown();
            return isHardcodedClientVersionValid();
        });
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(downloader.entered.await(5, TimeUnit.SECONDS));
            downloader.bothEntered.await(1, TimeUnit.SECONDS);
        } finally {
            downloader.release.countDown();
        }
        assertTrue(first.get(5, TimeUnit.SECONDS));
        assertTrue(second.get(5, TimeUnit.SECONDS));
        assertEquals(1, downloader.requests.get());
    }

    @Test
    void resetWaitsForAnInFlightFetchAndInvalidatesItsResult() throws Exception {
        final BlockingDownloader downloader = new BlockingDownloader(false);
        NewPipe.init(downloader);
        final Future<String> reading = executor.submit(YoutubeParsingHelper::getClientVersion);
        final CountDownLatch resetting = new CountDownLatch(1);
        try {
            assertTrue(downloader.entered.await(5, TimeUnit.SECONDS));
            final Future<?> reset = executor.submit(() -> {
                resetting.countDown();
                resetClientVersion();
            });
            assertTrue(resetting.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> reset.get(100, TimeUnit.MILLISECONDS));
            downloader.release.countDown();
            assertEquals(FIRST_VERSION, reading.get(5, TimeUnit.SECONDS));
            reset.get(5, TimeUnit.SECONDS);
            assertEquals(SECOND_VERSION, getClientVersion());
            assertEquals(2, downloader.requests.get());
        } finally {
            downloader.release.countDown();
        }
    }

    @Test
    void musicLookupDoesNotWaitForAWebFetch() throws Exception {
        final BlockingDownloader downloader = new BlockingDownloader(false);
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) throws IOException {
                if (request.url().startsWith("https://music.youtube.com/")) {
                    return response(request, "x".repeat(501));
                }
                return downloader.execute(request);
            }
        });
        final Future<String> web = executor.submit(YoutubeParsingHelper::getClientVersion);
        try {
            assertTrue(downloader.entered.await(5, TimeUnit.SECONDS));
            final Future<String> music = executor.submit(
                    YoutubeParsingHelper::getYoutubeMusicClientVersion);
            assertEquals(WEB_REMIX_HARDCODED_CLIENT_VERSION, music.get(5, TimeUnit.SECONDS));
        } finally {
            downloader.release.countDown();
        }
        assertEquals(FIRST_VERSION, web.get(5, TimeUnit.SECONDS));
    }

    @Test
    void htmlFallbackUsesEcatcherWhenCsiAndRegexHaveNoVersion() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                requests.incrementAndGet();
                if (request.url().endsWith("/sw.js")) {
                    return response(request, "no version here");
                }
                return response(request, "var ytInitialData = {\"responseContext\":{"
                        + "\"serviceTrackingParams\":[{\"service\":\"ECATCHER\","
                        + "\"params\":[{\"key\":\"client.version\",\"value\":\""
                        + FIRST_VERSION + "\"}]}]}};");
            }
        });
        assertEquals(FIRST_VERSION, getClientVersion());
        assertEquals(FIRST_VERSION, getClientVersion());
        assertEquals(2, requests.get());
    }

    @Test
    void failedLookupDoesNotPoisonTheWebCache() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) throws IOException {
                if (requests.incrementAndGet() <= 2) {
                    throw new IOException("Fixture request failed");
                }
                return response(request, versionBody(FIRST_VERSION));
            }
        });
        assertThrows(IOException.class, YoutubeParsingHelper::getClientVersion);
        assertEquals(FIRST_VERSION, getClientVersion());
        assertEquals(FIRST_VERSION, getClientVersion());
        assertEquals(3, requests.get());
    }

    @Test
    void failedMusicValidationDoesNotPoisonTheCache() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) throws IOException {
                if (requests.incrementAndGet() == 1) {
                    throw new IOException("Fixture validation failed");
                }
                return response(request, "x".repeat(501));
            }
        });
        assertThrows(IOException.class, YoutubeParsingHelper::getYoutubeMusicClientVersion);
        assertEquals(WEB_REMIX_HARDCODED_CLIENT_VERSION, getYoutubeMusicClientVersion());
        assertEquals(WEB_REMIX_HARDCODED_CLIENT_VERSION, getYoutubeMusicClientVersion());
        assertEquals(2, requests.get());
    }

    @Test
    void resetOnlyInvalidatesTheWebVersionAndKeepsOtherCaches() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                requests.incrementAndGet();
                if (request.url().startsWith("https://music.youtube.com/")) {
                    return response(request, "x".repeat(501));
                }
                if (request.url().contains("/guide?")) {
                    return response(request, "x".repeat(5001));
                }
                return response(request, versionBody(FIRST_VERSION));
            }
        });
        assertTrue(isHardcodedClientVersionValid());
        assertEquals(WEB_REMIX_HARDCODED_CLIENT_VERSION, getYoutubeMusicClientVersion());
        assertEquals(FIRST_VERSION, getClientVersion());

        resetClientVersion();

        assertEquals(FIRST_VERSION, getClientVersion());
        assertTrue(isHardcodedClientVersionValid());
        assertEquals(WEB_REMIX_HARDCODED_CLIENT_VERSION, getYoutubeMusicClientVersion());
        assertEquals(List.of(WEB_REMIX_HARDCODED_CLIENT_VERSION),
                YoutubeParsingHelper.getYoutubeMusicHeaders().get("X-YouTube-Client-Version"));
        assertEquals(4, requests.get());
    }

    private static Field field(final String name) throws Exception {
        final Field field = YoutubeParsingHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String versionBody(final String version) {
        return "INNERTUBE_CONTEXT_CLIENT_VERSION\":\"" + version + "\"";
    }

    private static Response response(final Request request, final String body) {
        return new Response(200, "OK", Map.of(), body, request.url());
    }

    private static final class BlockingDownloader extends Downloader {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch bothEntered = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final boolean music;
        private String responseBodyOverride;

        private BlockingDownloader(final boolean music) {
            this.music = music;
        }

        @Override
        public Response execute(@Nonnull final Request request) throws IOException {
            final int requestNumber = requests.incrementAndGet();
            entered.countDown();
            bothEntered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Fixture release timed out");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (responseBodyOverride != null) {
                return response(request, responseBodyOverride);
            }
            return response(request, music ? "x".repeat(501)
                    : versionBody(requestNumber == 1 ? FIRST_VERSION : SECOND_VERSION));
        }
    }
}
