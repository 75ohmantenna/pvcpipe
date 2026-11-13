package org.schabi.newpipe.util.external_communication;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.schabi.newpipe.RouterActivity;
import org.schabi.newpipe.util.image.ImageStrategy;
import org.schabi.newpipe.util.image.PreferredImageQuality;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import coil3.BitmapImage;
import coil3.ImageLoader;
import coil3.SingletonImageLoader;
import coil3.memory.MemoryCache;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShareUtilsPreviewTest {
    private Application context;
    private Bitmap bitmap;
    private MemoryCache cache;
    private SharePreviewCoordinator coordinator;

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ImageStrategy.setPreferredImageQuality(PreferredImageQuality.MEDIUM);
        bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        cache = new MemoryCache.Builder().maxSizeBytes(1024 * 1024).build();
        cache.set(new MemoryCache.Key("fixture", Collections.emptyMap()),
                new MemoryCache.Value(new BitmapImage(bitmap, true)));
        final ImageLoader loader = mock(ImageLoader.class);
        when(loader.getMemoryCache()).thenReturn(cache);
        SingletonImageLoader.setUnsafe(loader);
        final var field = ShareUtils.class.getDeclaredField("PREVIEWS");
        field.setAccessible(true);
        coordinator = (SharePreviewCoordinator) field.get(null);
    }

    @After
    public void cleanup() throws Exception {
        coordinator.cancel();
        final CountDownLatch drained = new CountDownLatch(1);
        coordinator.submit(null, () -> true, active -> {
            drained.countDown();
            return null;
        }, ignored -> { });
        assertTrue(drained.await(10, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        coordinator.cancel();
        SingletonImageLoader.reset();
        bitmap.recycle();
    }

    @Test
    public void previewFromNonActivityContextPreservesChooserGrantsAndUniqueReadableUris()
            throws Exception {
        ShareUtils.shareText(context, "Title", "https://example.test/one", "fixture");
        final Intent first = awaitChooser();
        assertChooser(first, "Title", "https://example.test/one");
        assertNotNull(first.getClipData());
        final var firstUri = first.getClipData().getItemAt(0).getUri();
        final byte[] firstBytes;
        try (var input = context.getContentResolver().openInputStream(firstUri)) {
            firstBytes = input.readAllBytes();
        }
        bitmap.eraseColor(Color.BLUE);
        ShareUtils.shareText(context, "Second", "https://example.test/two", "fixture");
        final Intent second = awaitChooser();
        final var secondUri = second.getClipData().getItemAt(0).getUri();
        assertNotEquals(firstUri, secondUri);
        try (var input = context.getContentResolver().openInputStream(firstUri)) {
            assertArrayEquals(firstBytes, input.readAllBytes());
        }
        assertFalse(bitmap.isRecycled());
    }

    @Test
    public void blockedCacheLookupIsOffMainAndTextOnlySupersedesIt() throws Exception {
        final MemoryCache blocked = mock(MemoryCache.class);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> lookupThread = new AtomicReference<>();
        when(blocked.get(any())).thenAnswer(invocation -> {
            lookupThread.set(Thread.currentThread());
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return cache.get(new MemoryCache.Key("fixture", Collections.emptyMap()));
        });
        final ImageLoader loader = mock(ImageLoader.class);
        when(loader.getMemoryCache()).thenReturn(blocked);
        SingletonImageLoader.setUnsafe(loader);
        try {
            ShareUtils.shareText(context, "Old", "old", "fixture");
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread(), lookupThread.get());
            assertNull(Shadows.shadowOf(context).getNextStartedActivity());
            ShareUtils.shareText(context, "New", "new");
            assertChooser(Shadows.shadowOf(context).getNextStartedActivity(), "New", "new");
        } finally {
            release.countDown();
        }
        final CountDownLatch drained = new CountDownLatch(1);
        coordinator.submit(null, () -> true, active -> {
            drained.countDown();
            return null;
        }, ignored -> { });
        assertTrue(drained.await(10, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(Shadows.shadowOf(context).getNextStartedActivity());
    }

    @Test
    public void missingPreviewFallsBackToUnmodifiedTextIntent() throws Exception {
        ShareUtils.shareText(context, "Missing", "url", "not-cached");
        final Intent chooser = awaitChooser();
        assertChooser(chooser, "Missing", "url");
        assertNull(chooser.getClipData());
    }

    private Intent awaitChooser() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Intent result;
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            result = Shadows.shadowOf(context).getNextStartedActivity();
            if (result != null) {
                return result;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Share chooser was not launched");
    }

    private void assertChooser(final Intent chooser, final String title, final String text) {
        assertNotNull(chooser);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertTrue((chooser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        final Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(title, send.getStringExtra(Intent.EXTRA_TITLE));
        assertEquals(title, send.getStringExtra(Intent.EXTRA_SUBJECT));
        assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals(new ComponentName(context, RouterActivity.class),
                chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)[0]);
        if (send.getClipData() != null
                && send.getClipData().getItemAt(0).getUri() != null) {
            assertEquals(send.getClipData(), chooser.getClipData());
            assertTrue((chooser.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        }
    }
}
