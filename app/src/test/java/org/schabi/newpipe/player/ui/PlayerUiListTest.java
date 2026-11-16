/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerType;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;
import org.schabi.newpipe.player.playqueue.PlayQueue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Real mode UIs and views with a controlled player; no playback or overlay window is started. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {26, 36})
public class PlayerUiListTest {
    private ActivityController<Activity> activity;
    private Context context;
    private Player player;
    private PlayerUi persistent;
    private PlayerUiList uis;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup();
        context = new ContextThemeWrapper(activity.get(), R.style.DarkTheme);
        player = mock(Player.class);
        when(player.getContext()).thenReturn(context);
        when(player.getPrefs()).thenReturn(PreferenceManager.getDefaultSharedPreferences(context));
        when(player.getSelectedVideoStream()).thenReturn(Optional.empty());
        when(player.getFragmentListener()).thenReturn(Optional.empty());
        when(player.exoPlayerIsNull()).thenReturn(true);
        persistent = mock(PlayerUi.class);
        uis = new PlayerUiList(persistent);
    }

    @After
    public void tearDown() {
        uis.destroyAll(Object.class);
        activity.pause().stop().destroy();
    }

    @Test
    public void videoModesReuseBindingAndRetireDelayedWork() {
        uis.selectMode(player, PlayerType.MAIN);
        final MainPlayerUi old = uis.get(MainPlayerUi.class).orElseThrow();
        final PlayerBinding binding = old.getBinding();
        final FrameLayout host = new FrameLayout(context);
        activity.get().setContentView(host);
        host.addView(binding.getRoot());
        old.showControls(0);
        old.hideControls(0, 1000);

        uis.selectMode(player, PlayerType.POPUP);
        assertFalse(uis.get(MainPlayerUi.class).isPresent());
        assertSame(binding, uis.get(PopupPlayerUi.class).orElseThrow().getBinding());
        assertEquals(0, host.getChildCount());

        uis.selectMode(player, PlayerType.MAIN);
        final MainPlayerUi replacement = uis.get(MainPlayerUi.class).orElseThrow();
        assertNotSame(old, replacement);
        assertSame(binding, replacement.getBinding());
        assertFalse(uis.get(PopupPlayerUi.class).isPresent());
        replacement.showControls(0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(View.VISIBLE, binding.playbackControlRoot.getVisibility());
        verifyNoInteractions(persistent);
    }

    @Test
    public void selectingCurrentModePreservesItsUi() {
        for (final PlayerType mode : PlayerType.values()) {
            uis.selectMode(player, mode);
            final List<PlayerUi> before = new ArrayList<>();
            uis.call(before::add);
            uis.selectMode(player, mode);
            final List<PlayerUi> after = new ArrayList<>();
            uis.call(after::add);
            assertEquals(before, after);
        }
        verifyNoInteractions(persistent);
    }

    @Test
    public void audioRetiresVideoAndReturningToVideoCreatesANewBinding() {
        uis.selectMode(player, PlayerType.MAIN);
        final PlayerBinding old = uis.get(MainPlayerUi.class).orElseThrow().getBinding();
        uis.selectMode(player, PlayerType.AUDIO);
        assertFalse(uis.get(VideoPlayerUi.class).isPresent());
        uis.selectMode(player, PlayerType.MAIN);
        assertFalse(uis.get(BackgroundPlayerUi.class).isPresent());
        assertNotSame(old, uis.get(MainPlayerUi.class).orElseThrow().getBinding());
        final List<PlayerUi> remaining = new ArrayList<>();
        uis.call(remaining::add);
        assertEquals(List.of(persistent, uis.get(MainPlayerUi.class).orElseThrow()), remaining);
        verifyNoInteractions(persistent);
    }

    @Test
    public void coldAudioSelectionDoesNotInflateViews() {
        final Context noViews = mock(Context.class);
        when(player.getContext()).thenReturn(noViews);
        uis.selectMode(player, PlayerType.AUDIO);
        assertSame(player, uis.get(BackgroundPlayerUi.class).orElseThrow().getPlayer());
        assertFalse(uis.get(VideoPlayerUi.class).isPresent());
        verifyNoInteractions(noViews, persistent);
    }

    @Test
    public void replacementIsConstructedAfterRetirementAndReplaysReadyStateInOrder() {
        final PopupPlayerUi old = mock(PopupPlayerUi.class);
        when(old.getBinding()).thenReturn(PlayerBinding.inflate(
                android.view.LayoutInflater.from(context)));
        uis = new PlayerUiList(persistent, old);
        when(player.getFragmentListener()).thenReturn(
                Optional.of(mock(PlayerServiceEventListener.class)));
        when(player.exoPlayerIsNull()).thenReturn(false);
        when(player.getPlayQueue()).thenReturn(mock(PlayQueue.class));

        // Construction is controlled only for callback ordering; real teardown is exercised above.
        try (var replacements = mockConstruction(MainPlayerUi.class, (ui, construction) -> {
            verify(old).destroyPlayer();
            verify(old).destroy();
            assertFalse(uis.get(PopupPlayerUi.class).isPresent());
            when(ui.getPlayer()).thenReturn(player);
        })) {
            uis.selectMode(player, PlayerType.MAIN);
            final MainPlayerUi replacement = replacements.constructed().get(0);
            assertSame(replacement, uis.get(MainPlayerUi.class).orElseThrow());
            final InOrder order = inOrder(replacement);
            order.verify(replacement).onFragmentListenerSet();
            order.verify(replacement).initPlayer();
            order.verify(replacement).initPlayback();
            uis.selectMode(player, PlayerType.MAIN);
            assertEquals(1, replacements.constructed().size());
            verify(persistent, never()).destroy();
            verify(persistent, never()).destroyPlayer();
        }
    }
}
