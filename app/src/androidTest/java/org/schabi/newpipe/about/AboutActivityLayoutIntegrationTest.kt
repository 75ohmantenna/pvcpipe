/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.about

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

/** Uses real activity frames without manually forcing a second parent measurement. */
@RunWith(AndroidJUnit4::class)
class AboutActivityLayoutIntegrationTest {
    @Test
    fun licensesFillTheWindowAcrossTabSwitchesAndReachTheLastComponent() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            repeat(2) {
                selectTab(scenario, 0)
                selectTab(scenario, 1)
                scenario.onActivity { activity ->
                    val rows = activity.findViewById<ViewGroup>(R.id.licenses_software_components)
                    val scroll = rows.parent.parent as NestedScrollView
                    assertTrue("All software components must be present", rows.childCount > 40)
                    assertTrue(
                        "Licenses viewport leaves an unused strip below it",
                        visibleBounds(scroll).bottom >= usableBottom(activity)
                    )
                    (activity.findViewById<View>(R.id.about_toolbar).parent as AppBarLayout).setExpanded(false, false)
                }
                awaitPage(scenario, 1)
                scenario.onActivity { activity ->
                    val rows = activity.findViewById<ViewGroup>(R.id.licenses_software_components)
                    val scroll = rows.parent.parent as NestedScrollView
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                }
                awaitPage(scenario, 1)
                scenario.onActivity { activity ->
                    val rows = activity.findViewById<ViewGroup>(R.id.licenses_software_components)
                    val scroll = rows.parent.parent as NestedScrollView
                    assertTrue("The complete licenses list should scroll", scroll.scrollY > 0)
                    val last = rows.getChildAt(rows.childCount - 1)
                    val lastBounds = visibleBounds(last)
                    assertTrue("The whole last component must be visible", lastBounds.height() == last.height)
                    assertTrue(
                        "The last component must be reachable above the navigation bar",
                        lastBounds.bottom <= usableBottom(activity)
                    )
                    (activity.findViewById<View>(R.id.about_toolbar).parent as AppBarLayout).setExpanded(true, false)
                }
                awaitPage(scenario, 1)
            }
        }
    }

    private fun selectTab(scenario: ActivityScenario<AboutActivity>, position: Int) {
        scenario.onActivity {
            it.findViewById<TabLayout>(R.id.about_tabLayout).getTabAt(position)!!.select()
        }
        awaitPage(scenario, position)
    }

    private fun awaitPage(scenario: ActivityScenario<AboutActivity>, position: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 30_000
        var lastState = "not measured"
        var stableFrames = 0
        var previousBounds: Rect? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val pager = activity.findViewById<ViewPager2>(R.id.about_viewPager2)
                val anchor = activity.findViewById<View>(
                    if (position == 0) R.id.about_app_version else R.id.licenses_software_components
                )
                val page = anchor?.parent?.parent as? View
                val bounds = Rect()
                val ready = pager.currentItem == position && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                    page != null && page.isLaidOut && page.getGlobalVisibleRect(bounds) && !bounds.isEmpty
                lastState = "page=${pager.currentItem}, state=${pager.scrollState}, " +
                    "pager=${pager.width}x${pager.height}, bounds=$bounds, ready=$ready"
                stableFrames = if (ready && bounds == previousBounds) stableFrames + 1 else 0
                previousBounds = if (ready) bounds else null
            }
            if (stableFrames >= 2) return
            SystemClock.sleep(32)
        }
        throw AssertionError("About tab $position did not finish layout and scrolling: $lastState")
    }

    private fun visibleBounds(view: View): Rect = Rect().also {
        assertTrue("View must be visible", view.getGlobalVisibleRect(it) && !it.isEmpty)
        val rootLocation = IntArray(2)
        view.rootView.getLocationOnScreen(rootLocation)
        it.offset(rootLocation[0], rootLocation[1])
    }

    private fun usableBottom(activity: AboutActivity): Int {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val decor = activity.window.decorView
        val navigation = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        return minOf(bottomOnScreen(root), bottomOnScreen(decor) - navigation)
    }

    private fun bottomOnScreen(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[1] + view.height
    }
}
