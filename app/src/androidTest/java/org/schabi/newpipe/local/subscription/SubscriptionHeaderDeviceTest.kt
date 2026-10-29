/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.SubscriptionHeaderBinding
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.local.subscription.item.Header
import org.schabi.newpipe.views.NewPipeTextView

/** Packaged resources and native accessibility nodes; no subscription database writes. */
class SubscriptionHeaderDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun requireDisposableEmulator() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Requires a disposable emulator" }
        check(context.packageName == "org.seventyfiveohmantenna.pvcpipe.debug")
        check(File(context.filesDir, "subscription-heading-emulator-allow").isFile) { "Host marker missing" }
    }

    private fun ui(locale: Locale = Locale.US, fontScale: Float = 1f): Context {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            this.fontScale = fontScale
        }
        return ContextThemeWrapper(context.createConfigurationContext(config), R.style.LightTheme)
    }

    private fun header(id: Int, ui: Context = ui()): NewPipeTextView {
        val root = LayoutInflater.from(ui).inflate(R.layout.subscription_header, null) as NewPipeTextView
        Header(id).bind(SubscriptionHeaderBinding.bind(root), 0)
        return root
    }

    @Test
    fun packagedLabelsKeepSupportedNamesAndDistinguishUnavailableIds() {
        instrumentation.runOnMainSync {
            for (service in ServiceList.all()) {
                assertEquals(service.serviceInfo.name, header(service.serviceId).text.toString())
            }
            val ids = listOf(-1, 2, 3, 4, 99, Int.MAX_VALUE)
            val labels = ids.map { header(it).text.toString() }
            assertEquals(ids.size, labels.distinct().size)
            for ((id, label) in ids.zip(labels)) {
                assertEquals("Unavailable service (ID $id)", label)
                assertFalse(label.contains("<unknown>"))
            }
        }
    }

    @Test
    fun packagedResourceFormatsIdsWithTheBoundUiLocale() {
        instrumentation.runOnMainSync {
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
                val ui = ui(locale)
                for (id in listOf(2, 99, Int.MAX_VALUE)) {
                    val label = header(id, ui).text.toString()
                    assertEquals(ui.getString(R.string.subscription_unavailable_service, id), label)
                    assertTrue(label.contains(String.format(locale, "%d", id)))
                    if (locale.language == "ar") assertTrue(label.any { it in '٠'..'٩' })
                }
            }
        }
    }

    @Test
    fun nativeNodesExposeHeadingSemanticsWithoutBecomingClickable() {
        instrumentation.runOnMainSync {
            for (id in listOf(0, 2, 3, 99)) {
                val root = header(id)
                assertTrue(ViewCompat.isAccessibilityHeading(root))
                assertFalse(root.isClickable)
            }
        }
        // Detached views have no window-backed native node. Inspect the actual fixture window.
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        context.startActivity(
            Intent().apply {
                setClassName(instrumentation.context.packageName, SubscriptionHeaderRenderingActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("service_ids", intArrayOf(0, 2, 3, 99))
            }
        )
        val labels = listOf("YouTube", "Unavailable service (ID 2)", "Unavailable service (ID 3)", "Unavailable service (ID 99)")
        val fixturePackages = setOf(context.packageName, instrumentation.context.packageName)
        val deadline = SystemClock.uptimeMillis() + 30_000
        for (label in labels) {
            var node: AccessibilityNodeInfo? = null
            while (node == null && SystemClock.uptimeMillis() < deadline) {
                node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.packageName?.toString() in fixturePackages && it.text?.toString() == label }
                if (node == null) SystemClock.sleep(100)
            }
            checkNotNull(node) { "Rendered heading missing: $label" }
            node.refresh()
            assertTrue("Native heading metadata missing: $label", AccessibilityNodeInfoCompat.wrap(node).isHeading)
            assertFalse(node.isClickable)
        }
    }

    @Test
    fun narrowLargeFontLayoutKeepsTheEntireIdVisible() {
        instrumentation.runOnMainSync {
            val ui = ui(fontScale = 2f)
            val root = header(Int.MAX_VALUE, ui)
            val width = (220 * ui.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, width, root.measuredHeight)
            assertTrue("Must exercise more than the former two-line limit", root.lineCount > 2)
            for (line in 0 until root.lineCount) assertEquals(0, root.layout.getEllipsisCount(line))
            assertEquals(root.text.length, root.layout.getLineEnd(root.lineCount - 1))
        }
    }

    /** Host-driven TalkBack navigation is checked without suppressing the enabled service. */
    @Test
    fun focusedTalkBackHeadingMatchesHostExpectation() {
        val expected = InstrumentationRegistry.getArguments().getString("expected_heading")
        assumeTrue("Host drives TalkBack while this probe waits", expected != null)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        assertTrue(accessibility.getEnabledAccessibilityServiceList(-1).any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" })
        // Starting instrumentation can close the test-APK host. Launch it and navigate after
        // this readiness message, keeping the probe alive through the actual TalkBack action.
        println("WAITING_FOR_TALKBACK_HEADING=$expected")
        val deadline = SystemClock.uptimeMillis() + 60_000
        var focused: AccessibilityNodeInfo? = null
        while (focused == null && SystemClock.uptimeMillis() < deadline) {
            focused = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?.takeIf { it.packageName?.toString() == instrumentation.context.packageName && it.text?.toString() == expected }
            if (focused == null) SystemClock.sleep(100)
        }
        checkNotNull(focused) { "No expected accessibility focus after TalkBack action: $expected" }
        assertEquals(instrumentation.context.packageName, focused.packageName.toString())
        assertEquals(expected, focused.text.toString())
        assertTrue(AccessibilityNodeInfoCompat.wrap(focused).isHeading)
        println("TALKBACK_HEADING_FOCUS=${focused.text}")
    }
}
