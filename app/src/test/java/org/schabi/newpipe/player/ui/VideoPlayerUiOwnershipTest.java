/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.widget.PopupMenu;
import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadows.ShadowChoreographer;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.player.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Exercises real UI teardown with a mock player; no engine or playback is started. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {26, 36})
public class VideoPlayerUiOwnershipTest {
    private ActivityController<Activity> activity;
    private Context context;
    private FrameLayout host;
    private Player player;
    private PlayerBinding binding;
    private final List<MainPlayerUi> liveUis = new ArrayList<>();

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup();
        context = new ContextThemeWrapper(activity.get(), R.style.DarkTheme);
        player = mock(Player.class);
        when(player.getContext()).thenReturn(context);
        when(player.getPrefs()).thenReturn(PreferenceManager.getDefaultSharedPreferences(context));
        when(player.getSelectedVideoStream()).thenReturn(Optional.empty());
        when(player.getFragmentListener()).thenReturn(Optional.empty());
        when(player.getCurrentState()).thenReturn(Player.STATE_PLAYING);
        when(player.isPlaying()).thenReturn(true);
        host = new FrameLayout(context);
        activity.get().setContentView(host);
        binding = PlayerBinding.inflate(LayoutInflater.from(context));
    }

    @After
    public void tearDown() {
        for (final MainPlayerUi ui : new ArrayList<>(liveUis)) {
            discard(ui);
        }
        activity.pause().stop().destroy();
        ShadowChoreographer.setPaused(false);
        verify(player, never()).play();
        verify(player, never()).pause();
        verify(player, never()).playPause();
    }

    @Test
    public void activeOwnerStillHidesControlsAfterItsDelay() {
        final MainPlayerUi ui = attachUi();
        ui.hideControls(0, 1000);
        idle(500);
        assertEquals(View.VISIBLE, binding.playbackControlRoot.getVisibility());
        idle(1000);
        assertEquals(View.GONE, binding.playbackControlRoot.getVisibility());
    }

    @Test
    public void engineTeardownDoesNotDiscardTheSurvivingUi() {
        final MainPlayerUi ui = attachUi();
        ui.destroyPlayer();
        ui.hideControls(0, 1000);
        idle(1500);
        assertEquals(View.GONE, binding.playbackControlRoot.getVisibility());
        ui.showControls(0);
        assertEquals(View.VISIBLE, binding.playbackControlRoot.getVisibility());
    }

    @Test
    public void discardedOwnerCannotHideControlsAfterBindingReuse() {
        final MainPlayerUi old = attachUi();
        old.hideControls(0, 1000);
        discard(old);
        attachUi();
        idle(2000);
        assertEquals(View.VISIBLE, binding.playbackControlRoot.getVisibility());
    }

    @Test
    public void menuDismissDuringTeardownCannotHideReplacementControls() {
        final MainPlayerUi old = attachUi();
        final PopupMenu menu = openQualityMenu(old);
        assertTrue(old.isSomePopupMenuVisible());
        assertTrue(isShowing(menu));
        discard(old);
        assertFalse(isShowing(menu));
        attachUi();
        idle(1000);
        assertEquals(View.VISIBLE, binding.playbackControlRoot.getVisibility());
    }

    @Test
    public void ordinaryMenuInteractionKeepsControlsUntilDismissed() {
        final MainPlayerUi ui = attachUi();
        ui.hideControls(0, 1000);
        final PopupMenu menu = openQualityMenu(ui);
        idle(10000);
        assertTrue(ui.isSomePopupMenuVisible());
        assertEquals(View.VISIBLE, binding.playbackControlRoot.getVisibility());
        menu.dismiss();
        assertFalse(ui.isSomePopupMenuVisible());
        idle(1000);
        assertEquals(View.GONE, binding.playbackControlRoot.getVisibility());
    }

    @Test
    public void pendingGestureCannotHideReplacementControls() {
        assertPendingGestureDiscarded(Player.STATE_PLAYING, View.VISIBLE);
    }

    @Test
    public void pendingGestureCannotShowReplacementControls() {
        assertPendingGestureDiscarded(Player.STATE_PLAYING, View.GONE);
    }

    @Test
    public void pendingCompletedGestureCannotShowReplacementControls() {
        assertPendingGestureDiscarded(Player.STATE_COMPLETED, View.GONE);
    }

    @Test
    public void discardedShowAnimationCannotScheduleAnotherHide() {
        final MainPlayerUi old = attachUi();
        ShadowChoreographer.setPaused(true);
        binding.playbackControlRoot.setVisibility(View.GONE);
        binding.playbackControlRoot.setAlpha(0.0f);
        old.showControlsThenHide();
        idle(20);
        idle(50);
        assertPartialFade(binding.playbackControlRoot);
        discard(old);
        attachUi(false);
        idle(10000);
        assertVisibleAndOpaqueControls();
    }

    @Test
    public void discardedHideAnimationCannotHideReusedBinding() {
        final MainPlayerUi old = attachUi();
        ShadowChoreographer.setPaused(true);
        old.hideControls(600, 0);
        idle(20);
        idle(50);
        for (final View view : controlViews()) {
            assertPartialFade(view);
        }
        discard(old);
        attachUi(false);
        idle(1000);
        assertVisibleAndOpaqueControls();
    }

    @Test
    public void plainAnimatorCancellationStopsItsEndListener() {
        final View view = new View(context);
        host.addView(view, new FrameLayout.LayoutParams(100, 100));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ShadowChoreographer.setPaused(true);
        view.animate().alpha(0.0f).setDuration(600).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final Animator animation) {
                view.setVisibility(View.GONE);
            }
        }).start();
        idle(20);
        idle(50);
        assertPartialFade(view);
        view.animate().setListener(null).cancel();
        assertPartialFade(view);
        view.setAlpha(1.0f);
        idle(1000);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(1.0f, view.getAlpha(), 0.0f);
    }

    private View[] controlViews() {
        return new View[]{binding.playbackControlRoot, binding.playbackControlsShadow,
                binding.playerTopShadow, binding.playerBottomShadow};
    }

    private static void assertPartialFade(final View view) {
        final String name = view.getId() == View.NO_ID ? "plain view"
                : view.getResources().getResourceEntryName(view.getId());
        assertEquals(name + " before cancellation", View.VISIBLE, view.getVisibility());
        assertTrue(name + " started fading", view.getAlpha() < 1.0f);
        assertTrue(name + " still fading", view.getAlpha() > 0.0f);
    }

    private void assertVisibleAndOpaqueControls() {
        for (final View view : controlViews()) {
            final String name = view.getResources().getResourceEntryName(view.getId());
            assertEquals(name, View.VISIBLE, view.getVisibility());
            assertEquals(name, 1.0f, view.getAlpha(), 0.0f);
        }
    }

    private void assertPendingGestureDiscarded(final int state, final int visibility) {
        when(player.getCurrentState()).thenReturn(state);
        final MainPlayerUi old = attachUi();
        final long now = SystemClock.uptimeMillis();
        final MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20, 20, 0);
        final MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 20, 20, 0);
        old.getGestureDetector().onTouchEvent(down);
        old.getGestureDetector().onTouchEvent(up);
        down.recycle();
        up.recycle();
        discard(old);
        attachUi();
        binding.playbackControlRoot.setVisibility(visibility);
        idle(1000);
        assertEquals(visibility, binding.playbackControlRoot.getVisibility());
    }

    private MainPlayerUi attachUi() {
        return attachUi(true);
    }

    private MainPlayerUi attachUi(final boolean show) {
        host.addView(binding.getRoot());
        final MainPlayerUi ui = new MainPlayerUi(player, binding);
        liveUis.add(ui);
        if (show) {
            ui.showControls(0);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return ui;
    }

    private void discard(final MainPlayerUi ui) {
        new PlayerUiList(ui).destroyAll(MainPlayerUi.class);
        liveUis.remove(ui);
    }

    private PopupMenu openQualityMenu(final MainPlayerUi ui) {
        final PopupMenu menu = ReflectionHelpers.getField(ui, "qualityPopupMenu");
        menu.getMenu().add("720p");
        // Production installs this listener while building the stream-dependent menu.
        menu.setOnDismissListener(ui);
        binding.qualityTextView.performClick();
        return menu;
    }

    private static boolean isShowing(final PopupMenu menu) {
        return ReflectionHelpers.callInstanceMethod(
                ReflectionHelpers.getField(menu, "mPopup"), "isShowing");
    }

    private static void idle(final long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
