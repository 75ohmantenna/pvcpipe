package us.shandian.giga.ui.common;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

public class ProgressDrawable extends Drawable {
    private static final int MARQUEE_INTERVAL = 150;

    private float progress;
    private int backgroundColor;
    private int foregroundColor;
    private Handler marqueeHandler;
    private float marqueeProgress;
    private Path marqueeLine;
    private int marqueeSize;
    private long marqueeNext;

    public ProgressDrawable() {
        marqueeLine = null; // marquee disabled
        marqueeProgress = 0.0f;
        marqueeSize = 0;
        marqueeNext = 0;
    }

    public void setColors(@ColorInt final int background, @ColorInt final int foreground) {
        backgroundColor = background;
        foregroundColor = foreground;
    }

    public void setProgress(final double value) {
        progress = (float) value;
        invalidateSelf();
    }

    public void setMarquee(final boolean marquee) {
        if (marquee == (marqueeLine != null)) {
            return;
        }
        marqueeLine = marquee ? new Path() : null;
        marqueeHandler = marquee ? new Handler(Looper.getMainLooper()) : null;
        marqueeSize = 0;
        marqueeNext = 0;
    }

    @Override
    public void draw(@NonNull final Canvas canvas) {
        int width = getBounds().width();
        final int height = getBounds().height();

        final Paint paint = new Paint();

        paint.setColor(backgroundColor);
        canvas.drawRect(0, 0, width, height, paint);

        paint.setColor(foregroundColor);

        if (marqueeLine != null) {
            if (marqueeSize < 1) {
                setupMarquee(width, height);
            }

            int size = marqueeSize;
            final Paint paint2 = new Paint();
            paint2.setColor(foregroundColor);
            paint2.setStrokeWidth(size);
            paint2.setStyle(Paint.Style.STROKE);

            size *= 2;

            if (marqueeProgress >= size) {
                marqueeProgress = 1;
            } else {
                marqueeProgress++;
            }

            // render marquee
            width += size * 2;
            final Path marquee = new Path();
            for (int i = -size; i < width; i += size) {
                marquee.addPath(marqueeLine, ((float) i + marqueeProgress), 0);
            }
            marquee.close();

            canvas.drawPath(marquee, paint2); // draw marquee

            if (System.currentTimeMillis() >= marqueeNext) {
                // program next update
                marqueeNext = System.currentTimeMillis() + MARQUEE_INTERVAL;
                marqueeHandler.postDelayed(this::invalidateSelf, MARQUEE_INTERVAL);
            }
            return;
        }

        canvas.drawRect(0, 0, (int) (progress * width), height, paint);
    }

    @Override
    public void setAlpha(final int alpha) {
        // Unsupported
    }

    @Override
    public void setColorFilter(final ColorFilter filter) {
        // Unsupported
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    @Override
    public void onBoundsChange(final Rect rect) {
        if (marqueeLine != null) {
            setupMarquee(rect.width(), rect.height());
        }
    }

    private void setupMarquee(final int width, final int height) {
        marqueeSize = (int) ((width * 10.0f) / 100.0f); // the size is 10% of the width

        marqueeLine.rewind();
        marqueeLine.moveTo(-marqueeSize, -marqueeSize);
        marqueeLine.lineTo(-marqueeSize * 4, height + marqueeSize);
        marqueeLine.close();
    }
}
