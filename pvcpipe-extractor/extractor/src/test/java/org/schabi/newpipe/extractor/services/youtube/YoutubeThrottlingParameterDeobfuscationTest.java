package org.schabi.newpipe.extractor.services.youtube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

@Tag("offline")
class YoutubeThrottlingParameterDeobfuscationTest {
    static final String PLAYER = "var ar=[fn];fn=function(a){return "
                                 + "a.split(\"\").reverse().join(\"\")};a.get(\"n\"))&&(b=ar[0](b);"
                                 + "signatureTimestamp:12345;";
    @BeforeEach
    void reset() {
        YoutubeJavaScriptPlayerManager.clearAllCaches();
    }

    @AfterEach
    void cleanup() {
        YoutubeJavaScriptPlayerManager.clearAllCaches();
        NewPipe.init(null);
    }

    static Response response(String url, String body) {
        return new Response(200, "OK", null, body, url);
    }

    static void install(String code) {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(Request r) {
                return response(
                        r.url(), r.url().endsWith("iframe_api") ? "player\\/12345678\\/" : code);
            }
        });
    }

    @Test
    void onlyNValueChanges() throws Exception {
        install(PLAYER);
        assertEquals("https://example.test/abc?other=abc&n=cba#abc",
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                        "id", "https://example.test/abc?other=abc&n=abc#abc"));
    }

    @Test
    void directFunctionName() throws Exception {
        assertEquals("fn",
                YoutubeThrottlingParameterUtils.getDeobfuscationFunctionName(
                        "fn=function(a){return a};a.get(\"n\"))&&(b=fn(b)"));
    }

    @Test
    void clearedGenerationCannotBeRepopulatedByOldDownload() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(Request r) throws java.io.IOException {
                if (r.url().endsWith("iframe_api"))
                    return response(r.url(), "player\\/12345678\\/");
                boolean old = Thread.currentThread().getName().equals("old-player");
                if (old) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new java.io.IOException("latch timeout");
                    } catch (InterruptedException e) {
                        throw new java.io.IOException(e);
                    }
                }
                return response(r.url(), "signatureTimestamp:" + (old ? "11111" : "22222"));
            }
        });
        ExecutorService executor =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "old-player"));
        Future<Integer> old =
                executor.submit(() -> YoutubeJavaScriptPlayerManager.getSignatureTimestamp("id"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            YoutubeJavaScriptPlayerManager.clearAllCaches();
            assertEquals(22222, YoutubeJavaScriptPlayerManager.getSignatureTimestamp("id"));
            release.countDown();
            assertEquals(11111, old.get(5, TimeUnit.SECONDS));
            assertEquals(22222, YoutubeJavaScriptPlayerManager.getSignatureTimestamp("id"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void recorded2026SignatureHasIndependentlyVerifiedOutput() throws Exception {
        // Existing c9168c90 fixture, recorded January 2026. Golden independently
        // verified in Node22/V8 and Rhino using only the extracted function.
        // This is NOT evidence of current-live n deobfuscation compatibility.
        try (var in = getClass().getResourceAsStream(
                     "/mocks/v1/org/schabi/newpipe/extractor/services/youtube/youtubesignatures/"
                     + "generated_mock_1.json")) {
            assertNotNull(in);
            String code = com.grack.nanojson.JsonParser.object()
                                  .from(in)
                                  .getObject("response")
                                  .getString("responseBody");
            install(code);
            long coldStart = System.nanoTime();
            assertEquals("6543210ZYXWVUTSRQPONMLKJIHGFEDCBAzyxwvutsrqponalkjihgfedcbm",
                    YoutubeJavaScriptPlayerManager.deobfuscateSignature("id",
                            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"));
            long coldNanos = System.nanoTime() - coldStart;
            long warmStart = System.nanoTime();
            for (int i = 0; i < 20; i++)
                assertEquals("6543210ZYXWVUTSRQPONMLKJIHGFEDCBAzyxwvutsrqponalkjihgfedcbm",
                        YoutubeJavaScriptPlayerManager.deobfuscateSignature("id",
                                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"));
            System.out.printf("Recorded signature diagnostic: cold %.3fms, warm mean %.3fms%n",
                    coldNanos / 1e6, (System.nanoTime() - warmStart) / 20e6);
            assertEquals(20472, YoutubeJavaScriptPlayerManager.getSignatureTimestamp("id"));
        }
    }

    @Test
    void dollarArgumentEarlyReturnIsRemoved() throws Exception {
        String code = "fn=function($a){var b=0;if(typeof missing===\"undefined\")return $a;return "
                      + "$a.split(\"\").reverse().join(\"\")};";
        assertEquals("cba",
                org.schabi.newpipe.extractor.utils.JavaScript.run(
                        YoutubeThrottlingParameterUtils.getDeobfuscationFunction(code, "fn"), "fn",
                        "abc"));
    }

    @Test
    void whitespaceRegexFallbackRemainsCallable() throws Exception {
        String code =
                "fn = function(a){a=a.split(\"\");try{a.reverse()}catch(e){}return a.join(\"\")};";
        assertEquals("cba",
                org.schabi.newpipe.extractor.utils.JavaScript.run(
                        YoutubeThrottlingParameterUtils.getDeobfuscationFunction(code, "fn"), "fn",
                        "abc"));
    }

    @Test
    void noParameterDoesNotFetchOrModifyUrl() throws Exception {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(Request request) {
                throw new AssertionError("unexpected download");
            }
        });
        for (String url :
                new String[] {"https://example.test/a?x=1", "https://example.test/a&n=abc",
                        "https://example.test/a#?n=abc", "https://example.test/a?x=z?n=abc",
                        "https://example.test/a?n=", "https://example.test/a?x=1#&n=abc"}) {
            assertEquals(url,
                    YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                            "id", url));
        }
    }

    @Test
    void encodedValueAndDuplicateKeysPreserveOtherBytes() throws Exception {
        install(PLAYER);
        assertEquals("https://example.test/?n=%2Bba&other=a%62%2B#frag",
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                        "id", "https://example.test/?n=a%62%2B&other=a%62%2B#frag"));
        assertEquals("https://example.test/?n=&n=cba&n=xyz",
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                        "id", "https://example.test/?n=&n=abc&n=xyz"));
        assertEquals("https://example.test/?n=%26ba",
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                        "id", "https://example.test/?n=ab%26"));
    }

    @Test
    void malformedEncodingFailsBeforeFetching() {
        NewPipe.init(null);
        assertThrows(org.schabi.newpipe.extractor.exceptions.ParsingException.class,
                ()
                        -> YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                                "id", "https://example.test/?n=%ZZ"));
    }

    @Test
    void concurrentColdCallsShareOneFetchAndIndependentEvaluations() throws Exception {
        var fetches = new java.util.concurrent.atomic.AtomicInteger();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(Request request) {
                if (request.url().endsWith("iframe_api"))
                    return response(request.url(), "player\\/12345678\\/");
                fetches.incrementAndGet();
                return response(request.url(), PLAYER);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 32; i++) {
                final String value = "token" + i;
                results.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                            "id", "https://example.test/?n=" + value);
                }));
            }
            start.countDown();
            for (int i = 0; i < 32; i++)
                assertEquals("https://example.test/?n=" + new StringBuilder("token" + i).reverse(),
                        results.get(i).get(10, TimeUnit.SECONDS));
            assertEquals(1, fetches.get());
            assertEquals(32, YoutubeJavaScriptPlayerManager.getThrottlingParametersCacheSize());
            assertEquals("https://example.test/?n=0nekot",
                    YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                            "different-video", "https://example.test/?n=token0"));
            assertEquals(1, fetches.get());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void parameterClearRetiresInFlightResultMapButKeepsPlayer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var fetches = new java.util.concurrent.atomic.AtomicInteger();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(Request request) throws java.io.IOException {
                if (request.url().endsWith("iframe_api"))
                    return response(request.url(), "player\\/12345678\\/");
                fetches.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new java.io.IOException("timeout");
                } catch (InterruptedException e) {
                    throw new java.io.IOException(e);
                }
                return response(request.url(), PLAYER);
            }
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> result = pool.submit(
                ()
                        -> YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                                "id", "https://example.test/?n=abc"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            YoutubeJavaScriptPlayerManager.clearThrottlingParametersCache();
            release.countDown();
            assertEquals("https://example.test/?n=cba", result.get(5, TimeUnit.SECONDS));
            assertEquals(0, YoutubeJavaScriptPlayerManager.getThrottlingParametersCacheSize());
            assertEquals("https://example.test/?n=cba",
                    YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                            "id", "https://example.test/?n=abc"));
            assertEquals(1, YoutubeJavaScriptPlayerManager.getThrottlingParametersCacheSize());
            assertEquals(1, fetches.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void onlySelectedFunctionIsEvaluatedAndExtractionErrorsAreRetired() throws Exception {
        install("throw new Error('full player must not execute');" + PLAYER);
        assertEquals("https://example.test/?n=cba",
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                        "id", "https://example.test/?n=abc"));
        YoutubeJavaScriptPlayerManager.clearAllCaches();
        install("var unsupported=1;");
        var first = assertThrows(org.schabi.newpipe.extractor.exceptions.ParsingException.class,
                ()
                        -> YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                                "id", "https://example.test/?n=abc"));
        install(PLAYER);
        assertSame(first,
                assertThrows(org.schabi.newpipe.extractor.exceptions.ParsingException.class,
                        ()
                                -> YoutubeJavaScriptPlayerManager
                                           .getUrlWithThrottlingParameterDeobfuscated(
                                                   "id", "https://example.test/?n=abc")));
        YoutubeJavaScriptPlayerManager.clearAllCaches();
        assertEquals("https://example.test/?n=cba",
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                        "id", "https://example.test/?n=abc"));
    }
}
