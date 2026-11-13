package org.schabi.newpipe.util.external_communication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import coil3.BitmapImage;
import coil3.DrawableImage;
import coil3.SingletonImageLoader;
import coil3.memory.MemoryCache;

/** Cached-only, bounded thumbnail creation. Every method runs on the preview worker. */
final class SharePreviewStore {
    static final int MAX_EDGE = 1024;
    static final int MAX_FILES = 64;
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    static final long RETENTION_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final String PREFIX = "preview-";

    private SharePreviewStore() {
    }

    @Nullable
    static File prepare(final Context context, final String url, final BooleanSupplier active) {
        Bitmap owned = null;
        try {
            final File directory = new File(context.getCacheDir(), "share_preview");
            if (!active.getAsBoolean() || !hasRoom(directory, System.currentTimeMillis())) {
                return null;
            }
            final var loader = SingletonImageLoader.get(context);
            final var memory = loader.getMemoryCache();
            final var value = memory == null ? null
                    : memory.get(new MemoryCache.Key(url, Collections.emptyMap()));
            if (value != null && value.getImage() instanceof BitmapImage) {
                owned = copyBounded(((BitmapImage) value.getImage()).getBitmap());
            } else if (value != null && value.getImage() instanceof DrawableImage) {
                owned = copyDrawable(context, ((DrawableImage) value.getImage()).getDrawable());
            }
            // Unsupported/oversized hardware cache images can still have a bounded disk preview.
            if (owned == null && loader.getDiskCache() != null) {
                try (var snapshot = loader.getDiskCache().openSnapshot(url)) {
                    if (snapshot != null) {
                        owned = decodeBounded(snapshot.getData().toString());
                    }
                }
            }
            return owned == null || !active.getAsBoolean() ? null : write(directory, owned);
        } catch (final Exception | OutOfMemoryError error) {
            Log.w("SharePreviewStore", "Cannot prepare share preview", error);
            return null;
        } finally {
            if (owned != null) {
                owned.recycle();
            }
        }
    }

    @Nullable
    static Bitmap copyBounded(final Bitmap source) {
        if (source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        Bitmap readable = source;
        final boolean hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && source.getConfig() == Bitmap.Config.HARDWARE;
        if (hardware) {
            if (source.getWidth() > MAX_EDGE || source.getHeight() > MAX_EDGE) {
                return null;
            }
            readable = source.copy(Bitmap.Config.ARGB_8888, false);
            if (readable == null) {
                return null;
            }
        }
        try {
            final double ratio = Math.min(1.0,
                    (double) MAX_EDGE / Math.max(readable.getWidth(), readable.getHeight()));
            final int width = Math.max(1, (int) (readable.getWidth() * ratio));
            final int height = Math.max(1, (int) (readable.getHeight() * ratio));
            final Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                new Canvas(result).drawBitmap(readable, null, new Rect(0, 0, width, height), null);
                return result;
            } catch (final RuntimeException error) {
                result.recycle();
                throw error;
            }
        } finally {
            if (hardware) {
                readable.recycle();
            }
        }
    }

    @Nullable
    static Bitmap copyDrawable(final Context context, final Drawable source) {
        if (source instanceof BitmapDrawable) {
            return copyBounded(((BitmapDrawable) source).getBitmap());
        }
        final Drawable.ConstantState state = source.getConstantState();
        if (state == null) {
            return null;
        }
        // Never change the bounds/state of a drawable currently owned by an ImageView.
        final Drawable copy = state.newDrawable(context.getResources()).mutate();
        final int sourceWidth = Math.max(1, source.getIntrinsicWidth());
        final int sourceHeight = Math.max(1, source.getIntrinsicHeight());
        final double ratio = Math.min(1.0,
                (double) MAX_EDGE / Math.max(sourceWidth, sourceHeight));
        final int width = Math.max(1, (int) (sourceWidth * ratio));
        final int height = Math.max(1, (int) (sourceHeight * ratio));
        final Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            copy.setBounds(0, 0, width, height);
            copy.draw(new Canvas(result));
            return result;
        } catch (final RuntimeException error) {
            result.recycle();
            throw error;
        }
    }

    @Nullable
    static Bitmap decodeBounded(final String path) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        options.inSampleSize = 1;
        while ((long) options.outWidth > (long) MAX_EDGE * options.inSampleSize
                || (long) options.outHeight > (long) MAX_EDGE * options.inSampleSize) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        final Bitmap decoded = BitmapFactory.decodeFile(path, options);
        if (decoded == null) {
            return null;
        }
        // Some image codecs round sampled dimensions up. Enforce the output bound as well.
        if (decoded.getWidth() <= MAX_EDGE && decoded.getHeight() <= MAX_EDGE) {
            return decoded;
        }
        try {
            return copyBounded(decoded);
        } finally {
            decoded.recycle();
        }
    }

    static boolean hasRoom(final File directory, final long now) throws IOException {
        Files.createDirectories(directory.toPath());
        final File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }
        long bytes = 0;
        int count = 0;
        for (final File file : files) {
            if (file.isFile() && file.getName().startsWith(PREFIX)
                    && file.getName().endsWith(".jpg")
                    && now - file.lastModified() > RETENTION_MILLIS
                    && file.delete()) {
                continue;
            }
            count++;
            final long size = file.length();
            if (size > MAX_CACHE_BYTES - bytes) {
                return false;
            }
            bytes += size;
        }
        return count < MAX_FILES && bytes <= MAX_CACHE_BYTES - MAX_FILE_BYTES;
    }

    static File write(final File directory, final Bitmap bitmap) throws IOException {
        final File file = File.createTempFile(PREFIX, ".jpg", directory);
        boolean complete = false;
        try {
            try (var output = new LimitedOutputStream(Files.newOutputStream(file.toPath()))) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw new IOException("Preview encoder failed");
                }
            }
            complete = true;
            return file;
        } finally {
            if (!complete) {
                file.delete();
            }
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private long written;

        LimitedOutputStream(final OutputStream output) {
            super(output);
        }

        @Override
        public void write(final int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length)
                throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }

        private void reserve(final int length) throws IOException {
            if (length > MAX_FILE_BYTES - written) {
                throw new IOException("Preview exceeds byte limit");
            }
            written += length;
        }
    }
}
