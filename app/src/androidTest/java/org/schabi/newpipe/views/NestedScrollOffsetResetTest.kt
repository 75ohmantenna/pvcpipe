/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.views

import android.content.Context
import android.view.View
import androidx.core.view.NestedScrollingChild2
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The scrolling views in the detail pager must not carry the window offset of an earlier nested
 * scroll into drags made after nested scrolling is switched off.
 */
class NestedScrollOffsetResetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun commentsListForgetsTheOffsetOfAnEarlierNestedScroll() {
        assertOffsetCleared { NewPipeRecyclerView(context) }
    }

    @Test
    fun relatedItemsListForgetsTheOffsetOfAnEarlierNestedScroll() {
        assertOffsetCleared { OffsetResettingRecyclerView(context) }
    }

    @Test
    fun descriptionForgetsTheOffsetOfAnEarlierNestedScroll() {
        assertOffsetCleared { OffsetResettingNestedScrollView(context) }
    }

    private fun assertOffsetCleared(create: () -> View) {
        // What the view's own array holds after a nested scroll that moved it by 39 px.
        val offsetInWindow = intArrayOf(0, 39)
        var handled = true
        instrumentation.runOnMainSync {
            val view = create()
            view.isNestedScrollingEnabled = false
            handled = (view as NestedScrollingChild2).dispatchNestedPreScroll(
                0,
                12,
                IntArray(2),
                offsetInWindow,
                ViewCompat.TYPE_TOUCH
            )
        }
        assertFalse(handled)
        assertArrayEquals(intArrayOf(0, 0), offsetInWindow)
    }
}
