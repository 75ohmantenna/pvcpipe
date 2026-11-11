/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.ui;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding;

/** Measures the shipped player XML without starting playback, networking or the application. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {26, 36}, qualifiers = "mdpi")
public class PlayerControlsLayoutTest {
    @Test
    public void embeddedControlsStayAtTheVideoEdges() {
        assertControlsAtEdges(360, 203, false);
        assertControlsAtEdges(320, 180, false);
        assertControlsAtEdges(600, 338, false);
    }

    @Test
    public void fullscreenControlsStayAtTheVideoEdges() {
        assertControlsAtEdges(640, 360, true);
        assertControlsAtEdges(360, 640, true);
        assertControlsAtEdges(1280, 720, true);
    }

    @Test
    public void fragmentHierarchyKeepsControlsAtTheVideoEdges() {
        final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.DarkTheme);
        final FragmentVideoDetailBinding detail = FragmentVideoDetailBinding.inflate(
                LayoutInflater.from(context));
        detail.detailThumbnailImageView.setLayoutParams(
                new FrameLayout.LayoutParams(MATCH_PARENT, 203));
        detail.detailThumbnailImageView.setMinimumHeight(203);
        final PlayerBinding binding = PlayerBinding.inflate(LayoutInflater.from(context));
        detail.playerPlaceholder.addView(binding.getRoot(),
                new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        binding.surfaceView.setHeights(203, 640);
        binding.surfaceView.setAspectRatio(16.0f / 9.0f);
        binding.playbackControlRoot.setVisibility(View.VISIBLE);
        binding.metadataView.setVisibility(View.GONE);
        binding.playerCloseButton.setVisibility(View.VISIBLE);
        binding.loadingPanel.setVisibility(View.GONE);
        detail.getRoot().measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        detail.getRoot().layout(0, 0, 360, 640);
        assertEquals(203, binding.getRoot().getHeight());
        assertEquals(0, binding.playbackControlRoot.getTop());
        assertEquals(203, binding.playbackControlRoot.getHeight());
        assertEquals(0, binding.playbackWindowRoot.getTop());
        assertEquals(203, binding.playbackWindowRoot.getHeight());
        assertEquals(0, binding.topControls.getTop());
        assertEquals(203, binding.bottomControls.getBottom());
    }

    @Test
    public void forwardedInsetsDoNotPushInlineControlsTowardTheCenter() {
        final PlayerBinding binding = newBinding();
        final WindowInsetsCompat insets = systemInsets();
        // Reproduce the old XML behavior before installing the main-player policy.
        ViewCompat.dispatchApplyWindowInsets(binding.playbackControlRoot, insets);
        assertTrue(binding.playbackControlRoot.getPaddingTop() > 0);
        final boolean[] fullscreen = {false};
        ViewCompat.setOnApplyWindowInsetsListener(binding.playbackControlRoot,
                (view, applied) -> MainPlayerUi.applyPlaybackControlInsets(
                        view, applied, fullscreen[0], false));
        for (int dispatch = 0; dispatch < 2; dispatch++) {
            ViewCompat.dispatchApplyWindowInsets(binding.playbackControlRoot, insets);
            assertNoControlPadding(binding);
        }
        fullscreen[0] = true;
        ViewCompat.dispatchApplyWindowInsets(binding.playbackControlRoot, insets);
        assertEquals(24, binding.playbackControlRoot.getPaddingLeft());
        assertEquals(44, binding.playbackControlRoot.getPaddingTop());
        assertEquals(16, binding.playbackControlRoot.getPaddingRight());
        assertEquals(48, binding.playbackControlRoot.getPaddingBottom());
        fullscreen[0] = false;
        for (int dispatch = 0; dispatch < 2; dispatch++) {
            ViewCompat.dispatchApplyWindowInsets(binding.playbackControlRoot, insets);
            assertNoControlPadding(binding);
        }
        binding.playbackControlRoot.setVisibility(View.VISIBLE);
        binding.getRoot().measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(203, View.MeasureSpec.EXACTLY));
        binding.getRoot().layout(0, 0, 360, 203);
        assertEquals(0, binding.topControls.getTop());
        assertEquals(203, binding.bottomControls.getBottom());
    }

    @Test
    public void multiWindowDoesNotApplyTheParentInsetsTwiceEvenWhenFullscreen() {
        final PlayerBinding binding = newBinding();
        ViewCompat.setOnApplyWindowInsetsListener(binding.playbackControlRoot,
                (view, insets) -> MainPlayerUi.applyPlaybackControlInsets(
                        view, insets, true, true));
        binding.playbackControlRoot.setPadding(24, 44, 16, 48);
        ViewCompat.dispatchApplyWindowInsets(binding.playbackControlRoot, systemInsets());
        assertNoControlPadding(binding);
    }

    @Test
    public void removingMainPlayerListenerRestoresPopupInsetHandling() {
        final PlayerBinding binding = newBinding();
        ViewCompat.setOnApplyWindowInsetsListener(binding.playbackControlRoot,
                (view, insets) -> MainPlayerUi.applyPlaybackControlInsets(
                        view, insets, false, false));
        ViewCompat.dispatchApplyWindowInsets(binding.playbackControlRoot, systemInsets());
        assertNoControlPadding(binding);
        ViewCompat.setOnApplyWindowInsetsListener(binding.playbackControlRoot, null);
        ViewCompat.dispatchApplyWindowInsets(binding.playbackControlRoot, systemInsets());
        assertEquals(44, binding.playbackControlRoot.getPaddingTop());
        assertEquals(48, binding.playbackControlRoot.getPaddingBottom());
    }

    @Test
    public void numericLabelsHaveOpaqueBlackBackgroundsAndInteractiveFeedback() {
        final PlayerBinding binding = newBinding();
        for (final View counter : new View[]{binding.playbackCurrentTime,
                binding.playbackEndTime, binding.currentDisplaySeek}) {
            final GradientDrawable background = (GradientDrawable) counter.getBackground();
            assertEquals(Color.BLACK, background.getColor().getDefaultColor());
        }
        for (final View counter : new View[]{binding.qualityTextView,
                binding.playbackSpeed, binding.playbackLiveSync}) {
            assertTrue(counter.getBackground() instanceof RippleDrawable);
            final RippleDrawable ripple = (RippleDrawable) counter.getBackground();
            final GradientDrawable background = (GradientDrawable) ripple.getDrawable(0);
            assertEquals(Color.BLACK, background.getColor().getDefaultColor());
        }
    }

    private static PlayerBinding newBinding() {
        final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.DarkTheme);
        return PlayerBinding.inflate(LayoutInflater.from(context));
    }

    private static WindowInsetsCompat systemInsets() {
        return new WindowInsetsCompat.Builder()
                .setSystemWindowInsets(Insets.of(24, 44, 16, 48))
                .build();
    }

    private static void assertNoControlPadding(final PlayerBinding binding) {
        assertEquals(0, binding.playbackControlRoot.getPaddingLeft());
        assertEquals(0, binding.playbackControlRoot.getPaddingTop());
        assertEquals(0, binding.playbackControlRoot.getPaddingRight());
        assertEquals(0, binding.playbackControlRoot.getPaddingBottom());
    }

    private static void assertControlsAtEdges(final int width, final int height,
                                             final boolean fullscreen) {
        final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.DarkTheme);
        final FrameLayout host = new FrameLayout(context);
        final View thumbnail = new View(context);
        host.addView(thumbnail, new FrameLayout.LayoutParams(MATCH_PARENT, height));
        final PlayerBinding binding = PlayerBinding.inflate(LayoutInflater.from(context));
        final FrameLayout placeholder = new FrameLayout(context);
        host.addView(placeholder, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        placeholder.addView(binding.getRoot(),
                new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        binding.surfaceView.setHeights(height, height);
        binding.surfaceView.setAspectRatio(16.0f / 9.0f);
        binding.playbackControlRoot.setVisibility(View.VISIBLE);
        binding.playerCloseButton.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        binding.metadataView.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        binding.queueButton.setVisibility(View.VISIBLE);
        binding.castButton.setVisibility(View.VISIBLE);
        binding.qualityTextView.setText("720p60");
        binding.playbackSpeed.setText("1x");
        binding.playbackCurrentTime.setText("3:57:47");
        binding.playbackEndTime.setText("5:45:42");
        binding.loadingPanel.setVisibility(View.GONE);
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(fullscreen ? height : 0,
                        fullscreen ? View.MeasureSpec.EXACTLY : View.MeasureSpec.UNSPECIFIED));
        host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());
        final String size = width + "x" + height;
        assertEquals(size + " player height", height, binding.getRoot().getHeight());
        assertEquals(size + " controls top", 0, binding.playbackControlRoot.getTop());
        assertEquals(size + " controls height", height, binding.playbackControlRoot.getHeight());
        assertEquals(size + " window top", 0, binding.playbackWindowRoot.getTop());
        assertEquals(size + " window height", height, binding.playbackWindowRoot.getHeight());
        assertEquals(size + " top row", 0, binding.topControls.getTop());
        assertEquals(size + " bottom row", height, binding.bottomControls.getBottom());
        assertTrue(size + " seekbar width", binding.playbackSeekBar.getWidth() > 0);
    }
}
