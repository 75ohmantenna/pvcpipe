package org.schabi.newpipe.player.seekbarpreview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.collection.SparseArrayCompat;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

public class SeekbarPreviewLifecycleTest {
    @Test
    public void delayedOldResetCannotClearNewDataAndCloseRejectsNewWork() throws Exception {
        final ExecutorService executor = mock(ExecutorService.class);
        final List<Runnable> tasks = new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(0));
            return mock(Future.class);
        });
        try (var log = mockStatic(Log.class)) {
            final SeekbarPreviewThumbnailHolder holder =
                    new SeekbarPreviewThumbnailHolder(executor);
            holder.resetFrom(SeekbarPreviewThumbnailHelper.SeekbarPreviewThumbnailType.NONE,
                    List.of());
            final Field identifier = field("currentUpdateRequestIdentifier");
            final UUID old = (UUID) identifier.get(holder);
            holder.resetFrom(SeekbarPreviewThumbnailHelper.SeekbarPreviewThumbnailType.NONE,
                    List.of());
            final Bitmap current = mock(Bitmap.class);
            @SuppressWarnings("unchecked")
            final SparseArrayCompat<Supplier<Bitmap>> data =
                    (SparseArrayCompat<Supplier<Bitmap>>) field("seekbarPreviewData").get(holder);
            data.put(0, () -> current);
            final Method reset = SeekbarPreviewThumbnailHolder.class.getDeclaredMethod(
                    "resetFromAsync", int.class, List.class, UUID.class);
            reset.setAccessible(true);
            reset.invoke(holder,
                    SeekbarPreviewThumbnailHelper.SeekbarPreviewThumbnailType.HIGH_QUALITY,
                    List.of(), old);
            assertSame(current, holder.getBitmapAt(0).orElseThrow());
            holder.close();
            holder.resetFrom(SeekbarPreviewThumbnailHelper.SeekbarPreviewThumbnailType.NONE,
                    List.of());
            assertEquals(2, tasks.size());
            assertTrue(holder.getBitmapAt(0).isEmpty());
        }
    }

    private static Field field(final String name) throws Exception {
        final Field field = SeekbarPreviewThumbnailHolder.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
