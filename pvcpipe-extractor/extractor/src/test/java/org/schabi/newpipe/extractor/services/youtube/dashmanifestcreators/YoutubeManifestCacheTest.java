package org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.utils.ManifestCreatorCache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class YoutubeManifestCacheTest {
    private static final String URL = "https://example.invalid/stream";
    private static final String MANIFEST = "cached manifest";

    @Test
    void allGeneratorsReturnCachedManifestsBeforeTryingToGenerateThem() throws Exception {
        final ManifestCreatorCache<String, String> progressive =
                YoutubeProgressiveDashManifestCreator.getCache();
        final ManifestCreatorCache<String, String> otf = YoutubeOtfDashManifestCreator.getCache();
        final ManifestCreatorCache<String, String> dvr =
                YoutubePostLiveStreamDvrDashManifestCreator.getCache();
        try {
            progressive.put(URL, MANIFEST);
            otf.put(URL, MANIFEST);
            dvr.put(URL, MANIFEST);
            final ItagItem itag = audioItag();
            assertEquals(MANIFEST,
                    YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(URL, itag, 0));
            assertEquals(MANIFEST, YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(URL, itag, 0));
            assertEquals(MANIFEST,
                    YoutubePostLiveStreamDvrDashManifestCreator.fromPostLiveStreamDvrStreamingUrl(
                            URL, itag, 0, 0));
        } finally {
            progressive.clear();
            otf.clear();
            dvr.clear();
        }
    }

    @Test
    void concurrentClearProducesACacheMissRatherThanANullPointer() throws Exception {
        final ManifestCreatorCache<String, String> cache =
                YoutubeProgressiveDashManifestCreator.getCache();
        final ItagItem itag = audioItag();
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch ready = new CountDownLatch(2);
        try {
            final Future<?> writer = executor.submit(() -> {
                ready.countDown();
                start.await();
                for (int i = 0; i < 30000; i++) {
                    cache.clear();
                    cache.put(URL, MANIFEST);
                }
                return null;
            });
            final Future<?> reader = executor.submit(() -> {
                ready.countDown();
                start.await();
                for (int i = 0; i < 30000; i++) {
                    try {
                        assertEquals(MANIFEST,
                                YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                                        URL, itag, 0));
                    } catch (final CreationException exception) {
                        // A miss follows the normal missing-duration path, with no network access.
                        assertTrue(exception.getMessage().contains("durationSecondsFallback"));
                    }
                }
                return null;
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            cache.clear();
        }
    }

    private static ItagItem audioItag() {
        return new ItagItem(140, ItagItem.ItagType.AUDIO, MediaFormat.M4A, 128);
    }
}
