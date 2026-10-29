/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.xwray.groupie.GroupAdapter
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.subscription.item.ChannelItem
import org.schabi.newpipe.local.subscription.item.Header
import org.schabi.newpipe.settings.tabs.Tab
import org.schabi.newpipe.settings.tabs.TabsJsonHelper

/** Host-staged real screen and default on-disk Room database; empty disposable installs only. */
class SubscriptionScreenEmulatorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val database get() = NewPipeDatabase.getInstance(context)
    private val dao get() = database.subscriptionDAO()
    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(context)
    private lateinit var nonce: String
    private lateinit var evidence: File
    private lateinit var case: String
    private var activity: MainActivity? = null
    private lateinit var fragment: SubscriptionFragment

    @Before
    fun guard() {
        assumeTrue("Host-controlled disposable emulator only", arguments.getString("subscriptionScreenDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Physical hardware refused" }
        check(context.packageName == "org.seventyfiveohmantenna.pvcpipe.debug")
        nonce = arguments.getString("subscriptionScreenNonce") ?: error("Missing nonce")
        check(nonce.matches(Regex("[a-f0-9]{32}")))
        check(File(context.filesDir, "subscription-screen-allow").readText() == nonce)
        case = arguments.getString("subscriptionScreenCase") ?: error("Missing case")
        check(case.matches(Regex("[a-z-]+")))
        evidence = File(context.filesDir, "subscription-screen-$nonce").apply { mkdirs() }
    }

    @After
    fun finishActivity() {
        activity?.let { started ->
            main { started.finish() }
            await("activity destruction") { main { started.isDestroyed } }
        }
    }

    @Test
    fun runStage() {
        when (arguments.getString("subscriptionScreenPhase")) {
            "seed" -> seed()
            "inspect" -> inspect()
            "delete" -> deleteLastChannel()
            "talkback" -> talkBack()
            "cleanup" -> cleanup()
            else -> error("Unknown phase")
        }
    }

    private fun stateFile() = File(evidence, "state.json")
    private fun state() = JSONObject(stateFile().readText())
    private fun rows() = dao.getAll().blockingFirst().sortedBy { it.uid }
    private fun encode(rows: List<SubscriptionEntity>) = JSONArray(
        rows.map { row ->
            JSONObject().put("uid", row.uid).put("serviceId", row.serviceId)
                .put("url", row.url).put("name", row.name).put("avatarUrl", row.avatarUrl ?: JSONObject.NULL)
                .put("subscriberCount", row.subscriberCount ?: JSONObject.NULL)
                .put("description", row.description ?: JSONObject.NULL).put("notificationMode", row.notificationMode)
        }
    )

    private fun otherCounts(): JSONObject {
        val result = JSONObject()
        database.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { tables ->
            while (tables.moveToNext()) {
                val name = tables.getString(0)
                if (name in setOf("subscriptions", "room_master_table", "android_metadata", "sqlite_sequence")) continue
                check(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
                database.query("SELECT COUNT(*) FROM `$name`", null).use { count ->
                    check(count.moveToFirst())
                    result.put(name, count.getLong(0))
                }
            }
        }
        return result
    }

    private fun seed() {
        check(!stateFile().exists()) { "Nonce already used" }
        check(rows().isEmpty()) { "Existing subscriptions refused" }
        val counts = otherCounts()
        check(counts.keys().asSequence().all { counts.getLong(it) == 0L }) { "Existing library records refused: $counts" }
        val saved = JSONObject()
        for (key in preferenceKeys()) saved.put(key, preferences.all[key] ?: JSONObject.NULL)
        val state = JSONObject().put("preferences", saved).put("otherCounts", counts).put("removed", false)
        stateFile().writeText(state.toString(2))
        val ids = listOf(0, 0, 1, 2, 3, 4, 99, Int.MAX_VALUE)
        for ((index, id) in ids.withIndex()) {
            dao.insert(SubscriptionEntity(serviceId = id, url = "https://fixture.invalid/$nonce/$index", name = "Fixture $index service $id", avatarUrl = "", subscriberCount = index.toLong(), description = "Controlled subscription $index", notificationMode = 0))
        }
        state.put("rows", encode(rows()))
        stateFile().writeText(state.toString(2))
        configure("list")
        report(JSONObject().put("rows", encode(rows())).put("otherCounts", counts))
    }

    private fun preferenceKeys() = listOf(R.string.saved_tabs_key, R.string.list_view_mode_key, R.string.disable_error_reports_key).map(context::getString)

    private fun configure(mode: String) {
        check(mode in setOf("list", "grid"))
        check(
            preferences.edit()
                .putString(context.getString(R.string.saved_tabs_key), TabsJsonHelper.getJsonToSave(listOf(Tab.Type.SUBSCRIPTIONS.tab)))
                .putString(context.getString(R.string.list_view_mode_key), context.getString(if (mode == "grid") R.string.list_view_mode_grid_key else R.string.list_view_mode_list_key))
                .putBoolean(context.getString(R.string.disable_error_reports_key), true).commit()
        )
    }

    private fun expected(): JSONArray {
        val state = state()
        val original = state.getJSONArray("rows")
        return JSONArray((0 until original.length()).map(original::getJSONObject).filterNot { state.getBoolean("removed") && it.getInt("serviceId") == 99 })
    }

    private fun assertStored() {
        assertEquals(expected().toString(), encode(rows()).toString())
        assertEquals(state().getJSONObject("otherCounts").toString(), otherCounts().toString())
    }

    private fun open(mode: String) {
        assertStored()
        configure(mode)
        val expectedCount = rows().size
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("production subscriptions fragment") {
            main {
                fun find(f: Fragment): SubscriptionFragment? = if (f is SubscriptionFragment) f else f.childFragmentManager.fragments.firstNotNullOfOrNull(::find)
                val found = activity!!.supportFragmentManager.fragments.firstNotNullOfOrNull(::find)
                if (found?.view != null && found.isResumed) fragment = found
                ::fragment.isInitialized && fragment.view?.findViewById<RecyclerView>(R.id.items_list)?.adapter?.let { a -> a is GroupAdapter<*> && (0 until a.itemCount).count { a.getItem(it) is ChannelItem } == expectedCount } == true
            }
        }
        val version = if (mode == "grid") ChannelItem.ItemVersion.GRID else ChannelItem.ItemVersion.MINI
        main {
            for (i in 0 until adapter().itemCount) {
                val item = adapter().getItem(i)
                if (item is ChannelItem) assertEquals(version, item.itemVersion)
            }
        }
    }

    private fun recycler() = fragment.requireView().findViewById<RecyclerView>(R.id.items_list)
    private fun adapter() = recycler().adapter as GroupAdapter<*>
    private fun headerPositions() = (0 until adapter().itemCount).filter { adapter().getItem(it) is Header }

    private fun visible(position: Int): View {
        main { (recycler().layoutManager as GridLayoutManager).scrollToPositionWithOffset(position, 0) }
        await("row $position laid out") { main { recycler().findViewHolderForAdapterPosition(position)?.itemView?.let { it.isLaidOut && it.height > 0 } == true } }
        return main { recycler().findViewHolderForAdapterPosition(position)!!.itemView }
    }

    private fun inspectHeaders(): JSONArray {
        val data = rows()
        val ids = data.map { it.serviceId }.distinct().sorted()
        val channelPositions = main { (0 until adapter().itemCount).filter { adapter().getItem(it) is ChannelItem } }
        for (position in channelPositions) {
            val root = visible(position)
            main {
                val item = adapter().getItem(position) as ChannelItem
                val expected = data.single { it.uid == item.id }
                assertEquals(expected.name, root.findViewById<TextView>(R.id.itemTitleView).text.toString())
                assertFalse(AccessibilityNodeInfoCompat.wrap(root.createAccessibilityNodeInfo()).isHeading)
            }
        }
        val positions = main { headerPositions() }
        assertEquals(ids.size + 1, positions.size)
        val expectedChannels = data.sortedWith(compareBy<SubscriptionEntity> { it.serviceId }.thenBy { it.name!!.lowercase(Locale.ROOT) }).map { it.uid }
        assertEquals(expectedChannels, main { (0 until adapter().itemCount).map(adapter()::getItem).filterIsInstance<ChannelItem>().map { it.id } })
        val labels = listOf(context.getString(R.string.tab_subscriptions)) + ids.map { id ->
            when (id) {
                0 -> "YouTube"
                1 -> "SoundCloud"
                else -> context.getString(R.string.subscription_unavailable_service, id)
            }
        }
        val result = JSONArray()
        for ((index, position) in positions.withIndex()) {
            val root = visible(position) as TextView
            main {
                assertEquals(labels[index], root.text.toString())
                assertTrue(AccessibilityNodeInfoCompat.wrap(root.createAccessibilityNodeInfo()).isHeading)
                val manager = recycler().layoutManager as GridLayoutManager
                assertEquals(manager.spanCount, manager.spanSizeLookup.getSpanSize(position))
                for (line in 0 until root.lineCount) assertEquals(0, root.layout.getEllipsisCount(line))
                assertEquals(root.text.length, root.layout.getLineEnd(root.lineCount - 1))
                if (arguments.getString("subscriptionScreenLarge") == "true" && index == labels.lastIndex) {
                    assertEquals(2f, root.resources.configuration.fontScale, 0.01f)
                    assertTrue("Exercise the former two-line limit", root.lineCount > 2)
                }
                result.put(JSONObject().put("label", root.text).put("lines", root.lineCount).put("width", root.width).put("height", root.height).put("spanCount", manager.spanCount))
            }
        }
        assertStored()
        return result
    }

    private fun inspect() {
        val mode = arguments.getString("subscriptionScreenMode") ?: "list"
        open(mode)
        val headers = inspectHeaders()
        val selected = if (arguments.getString("subscriptionScreenLarge") == "true") main { headerPositions().last() } else main { headerPositions()[3] }
        visible(selected)
        visual(JSONObject().put("headers", headers))
        report(JSONObject().put("headers", headers).put("rows", encode(rows())))
    }

    private fun deleteLastChannel() {
        check(!state().getBoolean("removed"))
        open("list")
        val target = rows().single { it.serviceId == 99 }
        val position = main { (0 until adapter().itemCount).single { adapter().getItem(it) is ChannelItem && adapter().getItem(it).id == target.uid } }
        val row = visible(position)
        main { assertTrue(row.performLongClick()) }
        visual(JSONObject().put("channel", target.name).put("uid", target.uid).put("action", context.getString(R.string.unsubscribe)))
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        var selected: AccessibilityNodeInfo? = null
        await("visible unsubscribe dialog") {
            val roots = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }.mapNotNull { it.root }
            selected = roots.filter { it.findAccessibilityNodeInfosByText(target.name).any { node -> node.text?.toString() == target.name } }
                .flatMap { it.findAccessibilityNodeInfosByText(context.getString(R.string.unsubscribe)) }
                .firstOrNull { it.text?.toString() == context.getString(R.string.unsubscribe) && it.isVisibleToUser }
            selected != null
        }
        // Dialog text may be a non-clickable child of a list row. Touch the identified label.
        val bounds = Rect().also(selected!!::getBoundsInScreen)
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.sendPointerSync(event)
            event.recycle()
            SystemClock.sleep(50)
        }
        await("subscription deleted through UI") { rows().none { it.uid == target.uid } }
        state().put("removed", true).also { stateFile().writeText(it.toString(2)) }
        val expectedHeaderCount = rows().map { it.serviceId }.distinct().size + 1
        await("last-channel heading removed") { main { headerPositions().size == expectedHeaderCount } }
        val headers = inspectHeaders()
        assertFalse(headers.toString().contains("Unavailable service (ID 99)"))
        report(JSONObject().put("headers", headers).put("rows", encode(rows())).put("removedUid", target.uid))
    }

    private fun talkBack() {
        open("list")
        inspectHeaders()
        val root = visible(main { headerPositions()[3] })
        val point = main { IntArray(2).also(root::getLocationOnScreen).let { JSONObject().put("x", it[0] + root.width / 2).put("y", it[1] + root.height / 2) } }
        visual(JSONObject().put("focusStart", point))
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        assertTrue(accessibility.getEnabledAccessibilityServiceList(-1).any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" })
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val focused = JSONArray()
        for (id in listOf(3, 4)) {
            File(evidence, "$case-next-$id").writeText("ready")
            var node: AccessibilityNodeInfo? = null
            val label = context.getString(R.string.subscription_unavailable_service, id)
            await("host TalkBack heading $id", 120_000) {
                node = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.takeIf { it.packageName?.toString() == context.packageName && it.text?.toString() == label }
                node != null
            }
            assertTrue(AccessibilityNodeInfoCompat.wrap(node!!).isHeading)
            focused.put(label)
        }
        assertStored()
        report(JSONObject().put("focusedHeadings", focused).put("rows", encode(rows())))
    }

    private fun cleanup() {
        val saved = state().getJSONObject("preferences")
        try {
            for (row in rows().filter { it.url?.startsWith("https://fixture.invalid/$nonce/") == true }) dao.delete(row)
        } finally {
            val editor = preferences.edit()
            for (key in saved.keys()) {
                when (val value = saved.get(key)) {
                    JSONObject.NULL -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> error("Unexpected preference type: $key")
                }
            }
            check(editor.commit())
        }
        assertTrue(rows().isEmpty())
        assertEquals(state().getJSONObject("otherCounts").toString(), otherCounts().toString())
        report(JSONObject().put("remainingSubscriptions", rows().size).put("preferencesRestored", preferenceKeys().all { (preferences.all[it] ?: JSONObject.NULL) == saved.get(it) }))
    }

    private fun visual(payload: JSONObject) {
        if (arguments.getString("subscriptionScreenVisual") != "true") return
        File(evidence, "$case-ready.json").writeText(payload.toString(2))
        await("host visual checkpoint", 180_000) { File(evidence, "$case-release").isFile }
    }

    private fun report(payload: JSONObject) {
        payload.put("case", case).put("pid", Process.myPid()).put("database", context.getDatabasePath("newpipe.db").path)
        File(evidence, "$case.json").writeText(payload.toString(2))
    }

    private fun await(description: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out: $description" }
            SystemClock.sleep(100)
        }
    }

    private fun <T> main(action: () -> T): T {
        val result = FutureTask(Callable(action))
        instrumentation.runOnMainSync(result)
        return result.get()
    }
}
