package org.schabi.newpipe.util.external_communication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Collections;

import coil3.BitmapImage;
import coil3.ImageLoader;
import coil3.SingletonImageLoader;
import coil3.memory.MemoryCache;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SharePreviewStoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @After
    public void resetLoader() {
        SingletonImageLoader.reset();
    }

    @Test
    public void boundedCopyNeverRecyclesOrReusesCacheBitmap() {
        final Bitmap source = Bitmap.createBitmap(2048, 512, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        final Bitmap copy = SharePreviewStore.copyBounded(source);
        assertNotNull(copy);
        assertNotSame(source, copy);
        assertEquals(1024, copy.getWidth());
        assertEquals(256, copy.getHeight());
        assertEquals(Color.RED, copy.getPixel(50, 50));
        copy.recycle();
        assertFalse(source.isRecycled());
        source.recycle();
    }

    @Test
    public void smallHardwareImageHasOwnedSoftwareCopy() {
        final Bitmap source = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888);
        final Bitmap hardware = source.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        final Bitmap copy = SharePreviewStore.copyBounded(hardware);
        assertNotNull(copy);
        assertEquals(Bitmap.Config.ARGB_8888, copy.getConfig());
        assertFalse(hardware.isRecycled());
        copy.recycle();
        hardware.recycle();
        source.recycle();
    }

    @Test
    public void drawableCopyDoesNotChangeCacheOwnedBounds() {
        final ColorDrawable source = new ColorDrawable(Color.GREEN);
        source.setBounds(10, 20, 200, 300);
        final Rect before = new Rect(source.getBounds());
        final Bitmap result = SharePreviewStore.copyDrawable(
                RuntimeEnvironment.getApplication(), source);
        assertNotNull(result);
        assertEquals(before, source.getBounds());
        assertEquals(Color.GREEN, result.getPixel(0, 0));
        result.recycle();
    }

    @Test
    public void sampledDiskDecodeBoundsPanoramicImages() throws Exception {
        final Bitmap source = Bitmap.createBitmap(4096, 32, Bitmap.Config.ARGB_8888);
        final File input = temporary.newFile();
        try (var output = Files.newOutputStream(input.toPath())) {
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        source.recycle();
        final Bitmap decoded = SharePreviewStore.decodeBounded(input.getAbsolutePath());
        assertNotNull(decoded);
        assertTrue(decoded.getWidth() <= SharePreviewStore.MAX_EDGE);
        assertTrue(decoded.getHeight() > 0);
        decoded.recycle();
    }

    @Test
    public void corruptDiskImageFallsBack() throws Exception {
        final File input = temporary.newFile();
        Files.writeString(input.toPath(), "not an image");
        assertNull(SharePreviewStore.decodeBounded(input.getAbsolutePath()));
    }

    @Test
    public void successivePreviewFilesAreUniqueAndPreviousBytesRemainIntact() throws Exception {
        final File directory = temporary.newFolder();
        final Bitmap bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        final File first = SharePreviewStore.write(directory, bitmap);
        final byte[] original = Files.readAllBytes(first.toPath());
        bitmap.eraseColor(Color.BLUE);
        final File second = SharePreviewStore.write(directory, bitmap);
        assertNotEquals(first, second);
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(first.toPath())));
        assertTrue(second.length() > 0 && second.length() <= SharePreviewStore.MAX_FILE_BYTES);
        assertNotNull(BitmapFactory.decodeFile(second.toString()));
        bitmap.recycle();
    }

    @Test
    public void oversizedEncodedOutputIsRejectedAndPartialFileRemoved() throws Exception {
        final File directory = temporary.newFolder();
        final Bitmap encoder = mock(Bitmap.class);
        when(encoder.compress(any(), anyInt(), any())).thenAnswer(invocation -> {
            final OutputStream output = invocation.getArgument(2);
            output.write(new byte[(int) SharePreviewStore.MAX_FILE_BYTES + 1]);
            return true;
        });
        assertThrows(IOException.class, () -> SharePreviewStore.write(directory, encoder));
        assertEquals(0, directory.listFiles().length);
    }

    @Test
    public void recentRetentionBudgetFallsBackWithoutDeletingPublishedFiles() throws Exception {
        final File directory = temporary.newFolder();
        for (int i = 0; i < SharePreviewStore.MAX_FILES; i++) {
            assertTrue(new File(directory, "preview-" + i + ".jpg").createNewFile());
        }
        assertFalse(SharePreviewStore.hasRoom(directory, System.currentTimeMillis()));
        assertEquals(SharePreviewStore.MAX_FILES, directory.listFiles().length);
    }

    @Test
    public void byteBudgetAndExpirationDoNotDeleteUnrelatedFiles() throws Exception {
        final File directory = temporary.newFolder();
        final File huge = new File(directory, "unrelated");
        try (var file = new RandomAccessFile(huge, "rw")) {
            file.setLength(SharePreviewStore.MAX_CACHE_BYTES);
        }
        assertFalse(SharePreviewStore.hasRoom(directory, System.currentTimeMillis()));
        assertTrue(huge.delete());
        final File old = new File(directory, "preview-old.jpg");
        assertTrue(old.createNewFile());
        final long now = System.currentTimeMillis();
        assertTrue(old.setLastModified(now - SharePreviewStore.RETENTION_MILLIS - 10000));
        final File foreign = new File(directory, "foreign.jpg");
        assertTrue(foreign.createNewFile());
        assertTrue(foreign.setLastModified(old.lastModified()));
        assertTrue(SharePreviewStore.hasRoom(directory, now));
        assertFalse(old.exists());
        assertTrue(foreign.exists());
    }

    @Test
    public void actualCachePathCreatesPreviewWithoutRecyclingCachedBitmap() throws Exception {
        final Bitmap bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        final MemoryCache memory = new MemoryCache.Builder().maxSizeBytes(1024 * 1024).build();
        memory.set(new MemoryCache.Key("fixture", Collections.emptyMap()),
                new MemoryCache.Value(new BitmapImage(bitmap, true)));
        final ImageLoader loader = mock(ImageLoader.class);
        when(loader.getMemoryCache()).thenReturn(memory);
        SingletonImageLoader.setUnsafe(loader);
        final Context context = mock(Context.class);
        when(context.getCacheDir()).thenReturn(temporary.newFolder());
        final File result = SharePreviewStore.prepare(context, "fixture", () -> true);
        assertNotNull(result);
        assertFalse(bitmap.isRecycled());
        assertNull(SharePreviewStore.prepare(context, "absent", () -> true));
        assertNull(SharePreviewStore.prepare(context, "fixture", () -> false));
        bitmap.recycle();
    }
}
