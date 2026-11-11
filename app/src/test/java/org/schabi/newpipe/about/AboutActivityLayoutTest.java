/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.about;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.widget.NestedScrollView;
import androidx.viewpager2.widget.ViewPager2;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.schabi.newpipe.R;

/** Keeps a short About page from determining the viewport used by the long licenses page. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {26, 36},
        qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AboutActivityLayoutTest {
    @Test
    public void tallPortraitPagerFillsTheWindowAcrossTabSwitches() {
        assertPagerFillsWindow();
    }

    @Test
    @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    public void landscapePagerFillsTheWindowAcrossTabSwitches() {
        assertPagerFillsWindow();
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    public void tabletPagerFillsTheWindowAcrossTabSwitches() {
        assertPagerFillsWindow();
    }

    private static void assertPagerFillsWindow() {
        try (ActivityController<AboutActivity> controller =
                     Robolectric.buildActivity(AboutActivity.class).setup()) {
            final AboutActivity activity = controller.get();
            final ViewGroup content = activity.findViewById(android.R.id.content);
            final View root = content.getChildAt(0);
            final ViewPager2 pager = activity.findViewById(R.id.about_viewPager2);
            Shadows.shadowOf(activity.getMainLooper()).idle();
            // The first page must fill the viewport before a longer page requests a remeasure.
            assertReachesBottom(root, pager);
            for (final int page : new int[]{1, 0, 1}) {
                pager.setCurrentItem(page, false);
                activity.getSupportFragmentManager().executePendingTransactions();
                Shadows.shadowOf(activity.getMainLooper()).idle();
                assertReachesBottom(root, pager);
                if (page == 1) {
                    final ViewGroup rows = root.findViewById(R.id.licenses_software_components);
                    final NestedScrollView scroll =
                            (NestedScrollView) rows.getParent().getParent();
                    assertTrue("The full components list should be present",
                            rows.getChildCount() > 40);
                    assertReachesBottom(root, scroll);
                }
            }
        }
    }

    private static void assertReachesBottom(final View root, final View viewport) {
        final Rect rootBounds = new Rect();
        final Rect viewportBounds = new Rect();
        assertTrue("Activity must be laid out", root.getGlobalVisibleRect(rootBounds));
        assertTrue("Page viewport must be visible", viewport.getGlobalVisibleRect(viewportBounds));
        assertTrue("Page viewport " + viewportBounds + " should reach window bottom " + rootBounds,
                viewportBounds.bottom >= rootBounds.bottom);
    }
}
