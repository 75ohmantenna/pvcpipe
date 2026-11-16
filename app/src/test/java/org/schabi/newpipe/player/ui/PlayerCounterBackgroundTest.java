/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.views.NewPipeTextView;
import org.schabi.newpipe.views.PlayerCounterTextView;

import java.time.Duration;

/** Raster checks of the shipped counters, without a player, receiver or device. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 36, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlayerCounterBackgroundTest {
    @Test
    public void backingStaysInsideClippedControlsInRtlAndAfterPopupResizing() {
        final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.DarkTheme);
        final PlayerBinding binding = PlayerBinding.inflate(LayoutInflater.from(context));
        for (final TextView counter : new TextView[]{binding.qualityTextView,
                binding.playbackSpeed, binding.playbackCurrentTime, binding.playbackLiveSync}) {
            counter.setTextSize(28);
            counter.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            counter.setTextDirection(View.TEXT_DIRECTION_RTL);
            counter.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            for (final int width : new int[]{320, 60, 160, 90}) {
                counter.setText("2160p60 HDR مباشر");
                measure(counter, width, 35);
                counter.scrollTo(width == 60 ? 8 : 0, width == 60 ? 4 : 0);
                final Bitmap image = Bitmap.createBitmap(width + 16, 51, Bitmap.Config.ARGB_8888);
                image.eraseColor(Color.MAGENTA);
                final Canvas canvas = new Canvas(image);
                canvas.translate(8 - counter.getScrollX(), 8 - counter.getScrollY());
                counter.draw(canvas);
                final Rect black = blackBounds(image);
                assertTrue(black.left >= 8 && black.top >= 8);
                assertTrue(black.right <= width + 8 && black.bottom <= 43);
                assertEquals(width, counter.getWidth());
                assertEquals(35, counter.getHeight());
                image.recycle();
            }
            counter.setText("");
            counter.scrollTo(0, 0);
            measure(counter, 160, 35);
            final Bitmap empty = Bitmap.createBitmap(160, 35, Bitmap.Config.ARGB_8888);
            empty.eraseColor(Color.MAGENTA);
            counter.draw(new Canvas(empty));
            for (int y = 0; y < empty.getHeight(); y++) {
                for (int x = 0; x < empty.getWidth(); x++) {
                    assertEquals("Empty labels have no stale backing", Color.MAGENTA,
                            empty.getPixel(x, y));
                }
            }
            empty.recycle();
        }
    }

    @Test
    public void focusFeedbackAndPressedStateReachBothControlVersions() {
        assertFeedback(false);
    }

    @Test
    @Config(sdk = 26)
    public void solidPressedRippleDrawsAboveBothControlVersions() {
        assertFeedback(true);
    }

    private static void assertFeedback(final boolean pressedRaster) {
        for (final boolean original : new boolean[]{true, false}) {
            final Activity activity = Robolectric.buildActivity(Activity.class).setup()
                    .windowFocusChanged(true).get();
            final Context context = new ContextThemeWrapper(activity, R.style.DarkTheme);
            final View root = playerLayout(context, original);
            root.findViewById(R.id.playbackControlRoot).setVisibility(View.VISIBLE);
            activity.setContentView(root);
            final TextView counter = root.findViewById(R.id.playbackSpeed);
            final Drawable feedback = original ? counter.getBackground() : counter.getForeground();
            counter.setText("1x");
            final ViewGroup.LayoutParams params = counter.getLayoutParams();
            params.width = 100;
            params.height = 35;
            counter.setLayoutParams(params);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            measure(counter, 100, 35);
            final Bitmap image = Bitmap.createBitmap(100, 35, Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.MAGENTA);
            counter.draw(new Canvas(image));
            final int[] before = new int[100 * 35];
            image.getPixels(before, 0, 100, 0, 0, 100, 35);
            counter.setFocusableInTouchMode(true);
            assertTrue(counter.requestFocus());
            drawFeedbackFrame(counter, feedback, image);
            assertTrue("Focus feedback, original=" + original, feedbackVisible(before, image));
            counter.clearFocus();
            counter.setFocusable(false);
            assertFalse(counter.hasFocus());
            drawFeedbackFrame(counter, feedback, image);
            assertFalse("Focus cleared before pressing", feedbackVisible(before, image));
            if (!original) {
                // The same focus assertion must reject a backing with feedback removed.
                counter.setForeground(null);
                counter.setFocusableInTouchMode(true);
                assertTrue(counter.requestFocus());
                drawFeedbackFrame(counter, feedback, image);
                assertFalse("Missing feedback is detected", feedbackVisible(before, image));
                counter.clearFocus();
                counter.setFocusable(false);
                counter.setForeground(feedback);
            }
            counter.setPressed(true);
            boolean pressed = false;
            for (final int state : feedback.getState()) {
                pressed |= state == android.R.attr.state_pressed;
            }
            assertTrue("Pressed state reaches feedback", pressed);
            if (pressedRaster) {
                drawFeedbackFrame(counter, feedback, image);
                assertTrue("Pressed feedback, original=" + original,
                        feedbackVisible(before, image));
            }
            image.recycle();
            activity.finish();
        }
    }

    private static void drawFeedbackFrame(final TextView counter, final Drawable feedback,
                                          final Bitmap image) {
        feedback.jumpToCurrentState();
        // API 36 starts focus animation on the first draw, rather than on the state change.
        counter.draw(new Canvas(image));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(image.getWidth(), counter.getWidth());
        assertEquals(image.getHeight(), counter.getHeight());
        image.eraseColor(Color.MAGENTA);
        counter.draw(new Canvas(image));
    }

    private static boolean feedbackVisible(final int[] before, final Bitmap after) {
        for (int y = 0; y < after.getHeight(); y++) {
            for (int x = 0; x < after.getWidth(); x++) {
                if (before[y * after.getWidth() + x] == Color.BLACK
                        && after.getPixel(x, y) != Color.BLACK) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void shippedLayoutMatchesOriginalGeometryAtPlayerSizesAndFontScales() {
        for (final float scale : new float[]{1, 1.5f, 2}) {
            final Configuration config = new Configuration(
                    RuntimeEnvironment.getApplication().getResources().getConfiguration());
            config.fontScale = scale;
            final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication()
                    .createConfigurationContext(config), R.style.DarkTheme);
            for (final int[] size : new int[][]{{360, 203}, {640, 360}, {360, 640}, {240, 135}}) {
                final View compact = playerLayout(context, false);
                final View original = playerLayout(context, true);
                for (final View player : new View[]{compact, original}) {
                    player.findViewById(R.id.playbackControlRoot).setVisibility(View.VISIBLE);
                    final TextView quality = player.findViewById(R.id.qualityTextView);
                    final TextView speed = player.findViewById(R.id.playbackSpeed);
                    quality.setText("2160p60 HDR");
                    speed.setText("0.25x");
                    ((TextView) player.findViewById(R.id.playbackCurrentTime)).setText("123:45:56");
                    ((TextView) player.findViewById(R.id.playbackEndTime)).setText("234:56:07");
                    final TextView live = player.findViewById(R.id.playbackLiveSync);
                    live.setText("EN DIRECT");
                    live.setVisibility(View.VISIBLE);
                    if (size[0] == 240) {
                        final int padding = context.getResources().getDimensionPixelSize(
                                R.dimen.player_popup_buttons_padding);
                        quality.setPadding(padding, padding, padding, padding);
                        speed.setPadding(padding, padding, padding, padding);
                        speed.setMinimumWidth(0);
                    }
                    measure(player, size[0], size[1]);
                }
                for (final int id : new int[]{R.id.qualityTextView, R.id.playbackSpeed,
                        R.id.playbackCurrentTime, R.id.playbackEndTime, R.id.playbackLiveSync,
                        R.id.playbackSeekBar, R.id.screenRotationButton}) {
                    final View before = original.findViewById(id);
                    final View after = compact.findViewById(id);
                    assertEquals(before.getLeft(), after.getLeft());
                    assertEquals(before.getTop(), after.getTop());
                    assertEquals(before.getRight(), after.getRight());
                    assertEquals(before.getBottom(), after.getBottom());
                }
            }
        }
    }

    private static View playerLayout(final Context context, final boolean original) {
        final LayoutInflater inflater = LayoutInflater.from(context).cloneInContext(context);
        if (original) {
            inflater.setFactory2(new LayoutInflater.Factory2() {
                @Override
                public View onCreateView(final View parent, final String name,
                                         final Context viewContext, final AttributeSet attrs) {
                    return onCreateView(name, viewContext, attrs);
                }

                @Override
                public View onCreateView(final String name, final Context viewContext,
                                         final AttributeSet attrs) {
                    if (name.equals(PlayerCounterTextView.class.getName())) {
                        final TextView view = new NewPipeTextView(viewContext, attrs);
                        view.setForeground(null);
                        final int id = attrs.getAttributeResourceValue(
                                "http://schemas.android.com/apk/res/android", "id", View.NO_ID);
                        if (id == R.id.qualityTextView || id == R.id.playbackSpeed
                                || id == R.id.playbackLiveSync) {
                            view.setBackground(new RippleDrawable(
                                    ColorStateList.valueOf(0x40FFFFFF),
                                    AppCompatResources.getDrawable(viewContext,
                                            R.drawable.player_counter_background), null));
                        } else {
                            view.setBackgroundResource(R.drawable.player_counter_background);
                        }
                        return view;
                    }
                    return null;
                }
            });
        }
        return inflater.inflate(R.layout.player, null);
    }

    @Test
    public void compactBackingFollowsTextWithoutChangingControlGeometry() {
        for (final float fontScale : new float[]{1, 2}) {
            final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                    R.style.DarkTheme);
            final PlayerBinding binding = PlayerBinding.inflate(LayoutInflater.from(context));
            final TextView[] counters = {binding.qualityTextView, binding.playbackSpeed,
                    binding.playbackCurrentTime, binding.playbackEndTime,
                    binding.currentDisplaySeek, binding.playbackLiveSync};
            final String[] labels = {"2160p60 HDR", "0.25x", "123:45:56", "234:56:07",
                    "123:45:56", "EN DIRECT"};
            for (int i = 0; i < counters.length; i++) {
                final TextView counter = counters[i];
                counter.setVisibility(View.VISIBLE);
                counter.setText(labels[i]);
                counter.setTextSize(14 * fontScale);
                // Leave room for the large font while checking both fixed and minimum sizes.
                measure(counter, 320, 80);
                final int width = counter.getWidth();
                final int height = counter.getHeight();
                final int paddingLeft = counter.getPaddingLeft();
                final int paddingTop = counter.getPaddingTop();
                final Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                image.eraseColor(Color.MAGENTA);
                counter.draw(new Canvas(image));
                final Rect black = blackBounds(image);
                final int textLeft = counter.getCompoundPaddingLeft()
                        + (int) Math.floor(counter.getLayout().getLineLeft(0));
                final int textRight = counter.getCompoundPaddingLeft()
                        + (int) Math.ceil(counter.getLayout().getLineRight(0));
                assertEquals(labels[i], textLeft - 2, black.left);
                assertEquals(labels[i], textRight + 2, black.right);
                assertEquals(labels[i], Math.max(0, counter.getTotalPaddingTop() - 1), black.top);
                assertEquals(labels[i], counter.getTotalPaddingTop()
                        + counter.getLayout().getHeight() + 1, black.bottom);
                assertTrue(labels[i], black.height() < height);
                assertEquals(width, counter.getWidth());
                assertEquals(height, counter.getHeight());
                assertEquals(paddingLeft, counter.getPaddingLeft());
                assertEquals(paddingTop, counter.getPaddingTop());
                image.recycle();
            }
        }
    }

    @Test
    public void backingUpdatesWhenTextAndPopupPaddingChange() {
        final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.DarkTheme);
        final TextView counter = PlayerBinding.inflate(LayoutInflater.from(context)).playbackSpeed;
        counter.setPadding(4, 4, 4, 4);
        counter.setMinimumWidth(0);
        int previousWidth = 0;
        for (final String label : new String[]{"1x", "0.25x", "2160p60 HDR"}) {
            counter.setText(label);
            measure(counter, 320, 80);
            final Bitmap image = Bitmap.createBitmap(320, 80, Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.MAGENTA);
            counter.draw(new Canvas(image));
            final Rect bounds = blackBounds(image);
            assertTrue(bounds.width() > previousWidth);
            previousWidth = bounds.width();
            image.recycle();
        }
    }

    private static void measure(final View view, final int width, final int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static Rect blackBounds(final Bitmap image) {
        final Rect bounds = new Rect(image.getWidth(), image.getHeight(), 0, 0);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getPixel(x, y) == Color.BLACK) {
                    bounds.left = Math.min(bounds.left, x);
                    bounds.top = Math.min(bounds.top, y);
                    bounds.right = Math.max(bounds.right, x + 1);
                    bounds.bottom = Math.max(bounds.bottom, y + 1);
                }
            }
        }
        assertTrue("Opaque black backing is visible", bounds.width() > 0 && bounds.height() > 0);
        return bounds;
    }
}
