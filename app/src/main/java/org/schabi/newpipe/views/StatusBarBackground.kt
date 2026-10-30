/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.views

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import org.schabi.newpipe.util.ThemeHelper

/**
 * Paints the theme's `colorPrimaryDark` behind the status bar.
 *
 * Apps targeting API 35 or above cannot set a status bar color on Android 15 or later, so the
 * window background would show behind the status icons, which stay light. Older versions still
 * draw the color from the theme.
 */
object StatusBarBackground {
    @JvmStatic
    fun install(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return
        }

        val background = View(activity).apply {
            setBackgroundColor(
                ThemeHelper.resolveColorFromAttr(activity, androidx.appcompat.R.attr.colorPrimaryDark)
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        (activity.window.decorView as ViewGroup).addView(
            background,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP)
        )
        ViewCompat.setOnApplyWindowInsetsListener(background) { view, insets ->
            // This is zero while the status bar is hidden, such as in the fullscreen player.
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updateLayoutParams { height = statusBarHeight }
            insets
        }
        ViewCompat.requestApplyInsets(background)
    }
}
