/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.bookmark

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.R as MaterialR
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.LocalItem.LocalItemType
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.LocalItemListAdapter
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.settings.tabs.Tab
import org.schabi.newpipe.settings.tabs.TabsJsonHelper
import org.schabi.newpipe.testUtil.RoomTestExecutors
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.debounce.DebounceSaver
import org.schabi.newpipe.util.image.ImageStrategy
import org.schabi.newpipe.util.image.PreferredImageQuality

/**
 * Real touch input, FragmentManager lifecycles and Android Room/SQLite on a disposable emulator.
 * Gates delay the production write queue; triggers inject SQL failures. No persistence double.
 * Ordinary discovery skips this suite before fixture setup; the host must supply its nonce marker.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BookmarkEmulatorTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val room = RoomTestExecutors()
    private val gates = mutableListOf<WriteGate>()
    private val originalPreferences = linkedMapOf<String, Any?>()
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var database: AppDatabase
    private lateinit var images: File
    private lateinit var evidence: File
    private lateinit var red: String
    private lateinit var blue: String
    private var previousDatabase: Any? = null
    private var databaseReplaced = false
    private var previousImageQuality: PreferredImageQuality? = null
    private var activity: MainActivity? = null
    private lateinit var fragment: BookmarkFragment
    private var createdViews = 0
    private var destroyedViews = 0
    private var destroyedFragments = 0

    private val databaseField = NewPipeDatabase::class.java.getDeclaredField("databaseInstance").apply { isAccessible = true }
    private val imageQualityField = ImageStrategy::class.java.getDeclaredField("preferredImageQuality").apply { isAccessible = true }
    private val lifecycle = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
            if (f is BookmarkFragment && f.parentFragment == null && fm === activity?.supportFragmentManager) fragment = f
        }

        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (::fragment.isInitialized && f === fragment) createdViews++
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (::fragment.isInitialized && f === fragment) destroyedViews++
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (::fragment.isInitialized && f === fragment) destroyedFragments++
        }
    }

    @Before
    fun setup() {
        assumeTrue("Host-controlled disposable emulator suite", arguments.getString("bookmarkDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Physical devices are refused" }
        context = instrumentation.targetContext
        check(context.packageName == "org.seventyfiveohmantenna.pvcpipe.debug")
        val nonce = arguments.getString("bookmarkNonce") ?: error("Missing host nonce")
        check(nonce.matches(Regex("[a-f0-9]{32}")))
        check(File(context.filesDir, "bookmark-emulator-allow").readText() == nonce)
        evidence = File(context.filesDir, "bookmark-emulator-$nonce").apply { mkdirs() }
        images = File(context.cacheDir, "bookmark-images-${UUID.randomUUID()}").apply { mkdirs() }
        red = image("red.png", Color.RED)
        blue = image("blue.png", Color.BLUE)

        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val updates = mapOf(
            context.getString(R.string.saved_tabs_key) to TabsJsonHelper.getJsonToSave(listOf(Tab.Type.BOOKMARKS.tab)),
            context.getString(R.string.list_view_mode_key) to context.getString(R.string.list_view_mode_list_key),
            context.getString(R.string.disable_error_reports_key) to false
        )
        val editor = preferences.edit()
        for ((key, value) in updates) {
            originalPreferences[key] = preferences.all[key]
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        check(editor.commit())
        previousImageQuality = imageQualityField.get(null) as PreferredImageQuality
        ImageStrategy.setPreferredImageQuality(PreferredImageQuality.HIGH)

        database = room.applyTo(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java))
            .allowMainThreadQueries().build()
        seed()
        previousDatabase = databaseField.get(null)
        databaseField.set(null, database)
        databaseReplaced = true

        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        instrumentation.waitForIdleSync()
        onMain {
            val manager = activity!!.supportFragmentManager
            manager.executePendingTransactions()
            manager.registerFragmentLifecycleCallbacks(lifecycle, true)
            NavigationHelper.openBookmarksFragment(manager)
            manager.executePendingTransactions()
            assertSame(fragment, manager.findFragmentById(R.id.fragment_holder))
        }
        await("initial bookmark rendering") {
            onMain { loaded() && adapterOrder() == INITIAL && recycler().findViewHolderForAdapterPosition(4) != null }
        }
        assertSame(database, NewPipeDatabase.getInstance(context))
    }

    @After
    fun cleanup() {
        gates.forEach { it.release.countDown() }
        try {
            activity?.let { started ->
                onMain { started.finish() }
                await("activity destruction") { onMain { started.isDestroyed } }
            }
            if (databaseReplaced) {
                val drained = CountDownLatch(1)
                LocalPlaylistManager.WRITES.scheduleDirect { drained.countDown() }
                check(drained.await(15, TimeUnit.SECONDS)) { "Accepted writes did not drain" }
                instrumentation.waitForIdleSync()
            }
        } finally {
            if (databaseReplaced) databaseField.set(null, previousDatabase)
            if (::database.isInitialized) room.closeWhenIdle(database)
            if (::preferences.isInitialized) {
                val editor = preferences.edit()
                for ((key, value) in originalPreferences) {
                    when (value) {
                        null -> editor.remove(key)
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        else -> error("Unexpected original preference type: $key")
                    }
                }
                check(editor.commit())
            }
            previousImageQuality?.let(ImageStrategy::setPreferredImageQuality)
            if (::images.isInitialized) images.deleteRecursively()
        }
    }

    @Test
    fun rapidDragAndDeleteKeepNewerRevisionAfterOlderCompletion() {
        val first = gate()
        drag("L1", 2)
        onMain { fragment.saveImmediate() }
        val second = gate(waitUntilEntered = false)
        drag("R2", 0)
        delete("L3")
        val draft = onMain { adapterOrder() }
        assertEquals(listOf("R2", "R1", "L2", "L1"), draft)
        assertEquals(INITIAL, storedOrder())

        first.release.countDown()
        assertTrue(second.entered.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertEquals(listOf("R1", "L2", "L1", "R2", "L3"), storedOrder())
        onMain {
            assertTrue(saver().isModified)
            assertEquals(draft, adapterOrder())
            assertEquals(1, deletions().size)
        }
        second.release.countDown()
        awaitSaved(draft)
        assertNull(database.playlistDAO().getPlaylistSync(3))
        assertEquals("Beta", database.playlistDAO().getPlaylistSync(2)!!.name)
        record("rapid-edits")
    }

    @Test
    fun acceptedSaveAndAcknowledgmentCompleteWithNoView() {
        val held = gate()
        drag("L1", 2)
        val order = onMain { adapterOrder().also { fragment.saveImmediate() } }
        val oldView = onMain { fragment.requireView() }
        detach()
        onMain { assertNull(fragment.view) }
        held.release.countDown()
        await("viewless save acknowledgment") { onMain { !saver().isModified } }
        assertEquals(order, storedOrder())
        attach()
        await("new view loading persisted order") { onMain { loaded() && adapterOrder() == order } }
        onMain { assertNotSame(oldView, fragment.requireView()) }
        assertEquals(2, createdViews)
        assertEquals(1, destroyedViews)
        assertEquals(0, destroyedFragments)
        record("save-without-view")
    }

    @Test
    fun recreatedViewKeepsNewerDraftWhileOldSaveAcknowledges() {
        val first = gate()
        drag("L1", 2)
        onMain { fragment.saveImmediate() }
        val second = gate(waitUntilEntered = false)
        drag("R2", 0)
        val draft = onMain { adapterOrder() }
        val oldView = onMain { fragment.requireView() }
        detach()
        attach()
        await("retained draft in replacement view") { onMain { loaded() && adapterOrder() == draft } }
        onMain {
            assertNotSame(oldView, fragment.requireView())
            assertTrue(saver().isModified)
        }
        first.release.countDown()
        assertTrue(second.entered.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue(saver().isModified)
            assertEquals(draft, adapterOrder())
        }
        second.release.countDown()
        awaitSaved(draft)
        assertEquals(0, destroyedFragments)
        record("pending-view-recreation")
    }

    @Test
    fun oldFragmentCompletionCannotAcknowledgeNewFragmentAtEqualRevision() {
        val first = gate()
        drag("L1", 1)
        val oldSaver = onMain { saver().also { fragment.saveImmediate() } }
        val second = gate(waitUntilEntered = false)
        val old = fragment
        onMain {
            val manager = activity!!.supportFragmentManager
            assertTrue(manager.popBackStackImmediate())
            assertFalse(old.isAdded)
        }
        // A removed Fragment's registry can be reset by initState; observe destruction itself.
        await("old fragment destruction callback and cleared saver") {
            onMain { destroyedFragments == 1 && field<DebounceSaver?>("debounceSaver", old) == null }
        }
        onMain {
            val manager = activity!!.supportFragmentManager
            NavigationHelper.openBookmarksFragment(manager)
            manager.executePendingTransactions()
            fragment = manager.findFragmentById(R.id.fragment_holder) as BookmarkFragment
        }
        await("new fragment initial data") { onMain { loaded() && adapterOrder() == INITIAL } }
        drag("L2", 3)
        val draft = onMain {
            assertNotSame(oldSaver, saver())
            assertEquals(oldSaver.revision, saver().revision)
            adapterOrder().also { fragment.saveImmediate() }
        }
        first.release.countDown()
        assertTrue(second.entered.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        onMain { assertTrue(saver().isModified) }
        second.release.countDown()
        awaitSaved(draft)
        record("replacement-fragment-owner")
    }

    @Test
    fun remoteSqlFailureRollsBackLocalDeletionAndRetriesAfterViewRecreation() {
        failureAndRetry("remote_playlists", "L3", recreate = true)
        record("remote-failure-retry")
    }

    @Test
    fun localSqlFailureRollsBackRemoteDeletionAndEarlierRemoteOrderUpdate() {
        failureAndRetry("playlists", "R2", recreate = false)
        // The remote and local rows have equal numeric IDs; only the remote bookmark was deleted.
        assertNotNull(database.playlistDAO().getPlaylistSync(2))
        assertFalse(database.playlistRemoteDAO().getAll().blockingFirst().any { it.uid == 2L })
        record("local-failure-retry")
    }

    @Test
    fun permanentThumbnailSurvivesCleanupUntilUnsetOrPlaylistDeletion() {
        val manager = LocalPlaylistManager(database)
        val feeds = FeedDatabaseManager(database)
        val originalStream = database.streamDAO().getAll().blockingFirst().single { it.uid == 1L }
        assertEquals(1, manager.changePlaylistThumbnail(1, 1, true).blockingGet())
        manager.updateJoin(1, emptyList()).blockingAwait()

        feeds.clear()

        assertTrue(database.streamDAO().getAll().blockingFirst().any { it.uid == 1L })
        assertEquals(red, database.playlistStreamDAO().getPlaylistMetadata().blockingFirst().single { it.uid == 1L }.thumbnailUrl)
        await("permanent red thumbnail after contents removal and cleanup") { onMain { pixel("L1") == Color.RED } }

        val unset = manager.getAutomaticPlaylistThumbnailStreamId(1)
            .flatMapMaybe { manager.changePlaylistThumbnail(1, it, false) }.blockingGet()
        assertEquals(1, unset)
        feeds.clear()
        assertFalse(database.streamDAO().getAll().blockingFirst().any { it.uid == 1L })
        assertEquals(PlaylistEntity.DEFAULT_THUMBNAIL_ID, database.playlistDAO().getPlaylistSync(1)!!.thumbnailStreamId)
        await("default image after unsetting the only thumbnail reference") {
            onMain { entries().single { key(it) == "L1" }.thumbnailUrl == null && pixel("L1") != Color.RED }
        }

        val added = manager.appendToPlaylist(1, listOf(originalStream.copy(uid = 0))).blockingGet()
        assertEquals(1, added!!.size)
        val replacementId = manager.getAutomaticPlaylistThumbnailStreamId(1).blockingGet()
        assertTrue(replacementId > 0 && replacementId != 1L)
        assertEquals(1, manager.changePlaylistThumbnail(1, replacementId, true).blockingGet())
        manager.updateJoin(1, emptyList()).blockingAwait()
        feeds.clear()
        assertTrue(database.streamDAO().getAll().blockingFirst().any { it.uid == replacementId })

        val held = gate()
        delete("L1")
        val draft = onMain { adapterOrder() }
        held.release.countDown()
        awaitSaved(draft)
        feeds.clear()
        assertFalse(database.streamDAO().getAll().blockingFirst().any { it.uid == replacementId })
        assertEquals(setOf(2L), database.streamDAO().getAll().blockingFirst().map { it.uid }.toSet())
        record("thumbnail-cleanup")
    }

    @Test
    fun staleSavePreservesNewerMetadataAndDoesNotRecreateMissingRemoteRow() {
        await("initial local and remote red thumbnails") { onMain { pixel("L1") == Color.RED && pixel("R1") == Color.RED } }
        val held = gate()
        drag("L1", 2)
        onMain { fragment.saveImmediate() }
        val local = database.playlistDAO().getPlaylistSync(1)!!.copy(name = "Alpha newer", isThumbnailPermanent = true, thumbnailStreamId = 2)
        val remote = database.playlistRemoteDAO().getAll().blockingFirst().single { it.uid == 1L }
            .copy(orderingName = "Remote newer", thumbnailUrl = blue, streamCount = 91)
        database.runInTransaction {
            database.playlistDAO().update(local)
            database.playlistRemoteDAO().update(remote)
            database.playlistRemoteDAO().deletePlaylist(2)
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue(saver().isModified)
            assertEquals("Alpha", entries().single { key(it) == "L1" }.orderingName)
            assertTrue(adapterOrder().contains("R2"))
        }
        held.release.countDown()
        await("metadata save acknowledgment") { onMain { !saver().isModified } }
        assertEquals(local.copy(displayIndex = 2), database.playlistDAO().getPlaylistSync(1))
        assertEquals(remote.copy(displayIndex = 0), database.playlistRemoteDAO().getAll().blockingFirst().single())
        onMain { fragment.startLoading(true) }
        await("fresh names and blue thumbnails rendered") {
            onMain {
                loaded() && adapterOrder() == listOf("R1", "L2", "L1", "L3") &&
                    entries().single { key(it) == "L1" }.orderingName == "Alpha newer" &&
                    entries().single { key(it) == "R1" }.orderingName == "Remote newer" &&
                    pixel("L1") == Color.BLUE && pixel("R1") == Color.BLUE
            }
        }
        record("metadata-preservation")
        if (arguments.getString("bookmarkVisual") == "true") {
            // This final case releases accessibility ownership for external Android CLI inspection.
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
            File(evidence, "visual-ready").writeText("metadata-preservation")
            await("host visual inspection", 120_000) { File(evidence, "visual-release").exists() }
        }
    }

    private fun failureAndRetry(table: String, deletedKey: String, recreate: Boolean) {
        val beforeLocal = database.playlistDAO().getAll().blockingFirst().map { it.copy() }
        val beforeRemote = database.playlistRemoteDAO().getAll().blockingFirst().map { it.copy() }
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_bookmark BEFORE UPDATE OF display_index ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'bookmark controlled failure'); END"
        )
        val held = gate()
        drag("L1", 2)
        delete(deletedKey)
        val draft = onMain { adapterOrder() }
        held.release.countDown()
        await("real write-failure snackbar") {
            onMain { activity!!.findViewById<TextView>(MaterialR.id.snackbar_text)?.isShown == true }
        }
        assertEquals(beforeLocal, database.playlistDAO().getAll().blockingFirst())
        assertEquals(beforeRemote, database.playlistRemoteDAO().getAll().blockingFirst())
        onMain {
            assertTrue(saver().isModified)
            assertEquals(draft, adapterOrder())
            assertEquals(1, deletions().size)
            assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.error_panel).visibility)
        }
        val retry = gate()
        if (recreate) {
            detach()
            attach()
            await("failed draft restored") { onMain { loaded() && adapterOrder() == draft } }
            onMain { assertTrue(saver().isModified) }
        }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_bookmark")
        onMain { fragment.startLoading(true) }
        retry.release.countDown()
        awaitSaved(draft)
    }

    private fun seed() {
        for (id in 1..2) {
            database.streamDAO().insert(
                StreamEntity(
                    uid = id.toLong(),
                    serviceId = 0,
                    url = "https://example.invalid/bookmark/$id",
                    title = "Fixture stream $id",
                    streamType = StreamType.VIDEO_STREAM,
                    duration = 120,
                    uploader = "fixture",
                    thumbnailUrl = if (id == 1) red else blue
                )
            )
        }
        database.playlistDAO().insert(PlaylistEntity(1, "Alpha", false, 1, 0))
        database.playlistDAO().insert(PlaylistEntity(2, "Beta", true, 2, 2))
        database.playlistDAO().insert(PlaylistEntity(3, "Gamma", false, -1, 4))
        database.playlistStreamDAO().insertAll(listOf(PlaylistStreamEntity(1, 1, 0), PlaylistStreamEntity(2, 2, 0)))
        database.playlistRemoteDAO().insert(PlaylistRemoteEntity(1, 0, "Remote one", "https://example.invalid/one", red, "fixture", 1, 5))
        database.playlistRemoteDAO().insert(PlaylistRemoteEntity(2, 0, "Remote two", "https://example.invalid/two", red, "fixture", 3, 6))
    }

    private fun image(name: String, color: Int): String {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val path = File(images, name)
        path.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return path.toURI().toString()
    }

    private fun pixel(item: String): Int? {
        val index = adapterOrder().indexOf(item)
        if (index < 0) return null
        val holder = recycler().findViewHolderForAdapterPosition(index) ?: return null
        val view = holder.itemView.findViewById<ImageView>(R.id.itemThumbnailView)
        val loaded = (view.drawable as? BitmapDrawable)?.bitmap ?: return null
        // Coil may decode a hardware bitmap; read a software copy without changing its target.
        val readable = loaded.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        return try {
            readable.getPixel(readable.width / 2, readable.height / 2)
        } finally {
            readable.recycle()
        }
    }

    private fun gate(waitUntilEntered: Boolean = true): WriteGate {
        val gate = WriteGate()
        gates += gate
        LocalPlaylistManager.WRITES.scheduleDirect {
            gate.entered.countDown()
            check(gate.release.await(90, TimeUnit.SECONDS)) { "Host write gate timed out" }
        }
        if (waitUntilEntered) assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
        return gate
    }

    private fun detach() = onMain {
        activity!!.supportFragmentManager.beginTransaction().setReorderingAllowed(true).detach(fragment).commitNow()
        assertNull(fragment.view)
    }

    private fun attach() = onMain {
        activity!!.supportFragmentManager.beginTransaction().setReorderingAllowed(true).attach(fragment).commitNow()
        assertEquals(Lifecycle.State.RESUMED, fragment.lifecycle.currentState)
    }

    private fun drag(item: String, target: Int) {
        instrumentation.waitForIdleSync()
        await("RecyclerView animation completion") { onMain { recycler().itemAnimator?.isRunning != true } }
        val expected = onMain { adapterOrder().toMutableList().apply { add(target, removeAt(indexOf(item))) } }
        val points = onMain {
            val list = recycler()
            val sourceIndex = adapterOrder().indexOf(item)
            val source = list.findViewHolderForAdapterPosition(sourceIndex) ?: error("No source row")
            val destination = list.findViewHolderForAdapterPosition(target) ?: error("No destination row")
            val start = center(source.itemView.findViewById(R.id.itemHandle))
            val end = center(destination.itemView)
            // Cross the target's center rather than ending exactly at the swap threshold.
            start to (start.first to end.second + destination.itemView.height * if (target < sourceIndex) -0.25f else 0.25f)
        }
        val down = SystemClock.uptimeMillis()
        pointer(down, MotionEvent.ACTION_DOWN, points.first.first, points.first.second)
        for (step in 1..30) {
            SystemClock.sleep(20)
            val fraction = step / 30f
            pointer(
                down,
                MotionEvent.ACTION_MOVE,
                points.first.first + (points.second.first - points.first.first) * fraction,
                points.first.second + (points.second.second - points.first.second) * fraction
            )
        }
        repeat(5) {
            SystemClock.sleep(40)
            pointer(down, MotionEvent.ACTION_MOVE, points.second.first, points.second.second)
        }
        pointer(down, MotionEvent.ACTION_UP, points.second.first, points.second.second)
        try {
            await("touch reorder $item to $target: $expected") { onMain { adapterOrder() == expected && saver().isModified } }
        } catch (failure: AssertionError) {
            val actual = onMain { JSONObject().put("expected", JSONArray(expected)).put("actual", JSONArray(adapterOrder())).put("revision", saver().revision) }
            File(evidence, "failed-drag-$item.json").writeText(actual.toString(2))
            throw AssertionError("${failure.message}; $actual", failure)
        }
        SystemClock.sleep(350) // ItemTouchHelper's drop animation is separate from itemAnimator.
    }

    private fun delete(item: String) {
        await("row ready for long press") { onMain { recycler().itemAnimator?.isRunning != true } }
        val point = onMain {
            val row = recycler().findViewHolderForAdapterPosition(adapterOrder().indexOf(item)) ?: error("No deletion row")
            center(row.itemView.findViewById(R.id.itemTitleView))
        }
        val down = SystemClock.uptimeMillis()
        pointer(down, MotionEvent.ACTION_DOWN, point.first, point.second)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
        pointer(down, MotionEvent.ACTION_UP, point.first, point.second)
        if (item.startsWith("L")) clickAccessible(context.getString(R.string.delete))
        clickAccessible(context.getString(R.string.delete), "android:id/button1")
        await("confirmed deletion $item") { onMain { item !in adapterOrder() && saver().isModified } }
    }

    private fun clickAccessible(text: String, id: String? = null) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        await("dialog control $text/$id") {
            val root = automation.rootInActiveWindow ?: return@await false
            val nodes = if (id == null) root.findAccessibilityNodeInfosByText(text) else root.findAccessibilityNodeInfosByViewId(id)
            val target = nodes.firstOrNull { it.isVisibleToUser && it.text?.toString().equals(text, ignoreCase = true) }
            if (target == null) {
                false
            } else {
                val bounds = Rect()
                target.getBoundsInScreen(bounds)
                check(!bounds.isEmpty)
                val down = SystemClock.uptimeMillis()
                pointer(down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
                pointer(down, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY())
                true
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun center(view: View): Pair<Float, Float> {
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        check(view.isShown && view.width > 0 && view.height > 0)
        return (position[0] + view.width / 2f) to (position[1] + view.height / 2f)
    }

    private fun pointer(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun awaitSaved(order: List<String>) {
        await("save acknowledgment") { onMain { !saver().isModified && deletions().isEmpty() } }
        assertEquals(order, storedOrder())
        val indices = database.playlistDAO().getAll().blockingFirst().map { it.displayIndex } +
            database.playlistRemoteDAO().getAll().blockingFirst().map { it.displayIndex }
        assertEquals(order.indices.map { it.toLong() }, indices.sortedBy { it })
    }

    private fun storedOrder(): List<String> = (
        database.playlistDAO().getAll().blockingFirst().map { "L${it.uid}" to it.displayIndex } +
            database.playlistRemoteDAO().getAll().blockingFirst().map { "R${it.uid}" to it.displayIndex }
        ).sortedBy { it.second }.map { it.first }

    private fun entries(): List<PlaylistLocalItem> = field<LocalItemListAdapter>("itemListAdapter").itemsList.map { it as PlaylistLocalItem }
    private fun adapterOrder(): List<String> = entries().map(::key)
    private fun key(item: PlaylistLocalItem): String = (if (item.localItemType == LocalItemType.PLAYLIST_LOCAL_ITEM) "L" else "R") + item.uid
    private fun saver(): DebounceSaver = field("debounceSaver")
    private fun loaded(): Boolean = field<AtomicBoolean>("isLoadingComplete").get()
    private fun deletions(): List<*> = field("deletedItems")
    private fun recycler(): RecyclerView = fragment.requireView().findViewById(R.id.items_list)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String, owner: BookmarkFragment = fragment): T {
        var type: Class<*>? = BookmarkFragment::class.java
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("Missing field $name")
    }

    private fun record(name: String) {
        val state = onMain {
            JSONObject().put("case", name).put("adapterOrder", JSONArray(adapterOrder()))
                .put("dirty", saver().isModified).put("pendingDeletions", deletions().size)
                .put("createdViews", createdViews).put("destroyedViews", destroyedViews)
                .put("destroyedFragments", destroyedFragments).put("sdk", Build.VERSION.SDK_INT)
        }
        state.put("storedOrder", JSONArray(storedOrder()))
        File(evidence, "$name.json").writeText(state.toString(2))
    }

    private fun <T> onMain(action: () -> T): T {
        val work = FutureTask(Callable { action() })
        instrumentation.runOnMainSync(work)
        return work.get(15, TimeUnit.SECONDS)
    }

    private fun await(description: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        throw AssertionError("Timed out: $description")
    }

    private class WriteGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private companion object {
        val INITIAL = listOf("L1", "R1", "L2", "R2", "L3")
    }
}
