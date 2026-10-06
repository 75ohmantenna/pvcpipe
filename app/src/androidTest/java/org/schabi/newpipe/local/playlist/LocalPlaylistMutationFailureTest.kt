/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorPanelHelper
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.LocalItemListAdapter
import org.schabi.newpipe.util.debounce.DebounceSaver

/** Runs the real mutation error callback, preserves a pending draft, and retries its lifecycle save. */
class LocalPlaylistMutationFailureTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun failedWatchedRemovalKeepsDraftAndObservationSoLifecycleSaveCannotErasePlaylist() {
        val streams = insertStreams()
        val playlistId = database.playlistDAO().insert(
            PlaylistEntity(
                name = "playlist",
                isThumbnailPermanent = false,
                thumbnailStreamId = streams[0].uid,
                displayIndex = 0
            )
        )
        database.playlistStreamDAO().insertAll(
            streams.mapIndexed { index, stream -> PlaylistStreamEntity(playlistId, stream.uid, index) }
        )
        database.streamHistoryDAO().insert(
            StreamHistoryEntity(streams[0].uid, OffsetDateTime.now(ZoneOffset.UTC), 1L)
        )
        database.streamStateDAO().insert(StreamStateEntity(streams[0].uid, 120_000L))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_bulk_thumbnail
            BEFORE UPDATE ON playlists
            WHEN NEW.uid = $playlistId
            BEGIN SELECT RAISE(ABORT, 'reject bulk thumbnail'); END
            """.trimIndent()
        )
        val scheduler = HeldWriter()
        val manager = LocalPlaylistManager(database, scheduler)
        val observation = manager.getPlaylistStreams(playlistId).test()
        observation.awaitCount(1).assertNoErrors()
        val storedEntries = observation.values().first().associateBy { it.streamId }
        val pendingIds = listOf(streams[2].uid, streams[1].uid)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val disposables = CompositeDisposable()
        lateinit var fragment: ColdPlaylistFragment
        lateinit var adapter: LocalItemListAdapter
        lateinit var saver: DebounceSaver
        var errorPanel: ErrorPanelHelper? = null
        val failureFinished = CountDownLatch(1)
        var failedDraft: List<Long> = emptyList()
        var failedDirty = false
        var failedRevision = -1L
        var observationCancelled = false
        var savedBeforeRetry: List<Long> = emptyList()

        try {
            instrumentation.runOnMainSync {
                val themed = ContextThemeWrapper(context, R.style.LightTheme)
                val root = FrameLayout(themed).apply { id = android.R.id.content }
                root.addView(LayoutInflater.from(themed).inflate(R.layout.fragment_playlist, root, false))
                fragment = ColdPlaylistFragment(themed, root, failureFinished)
                adapter = LocalItemListAdapter(themed)
                adapter.addItems(pendingIds.map { storedEntries.getValue(it) })
                saver = DebounceSaver(fragment)
                saver.setHasChangesToSave()
                errorPanel = ErrorPanelHelper(fragment, root, null)
                setField(fragment, "errorPanelHelper", errorPanel)
                setField(fragment, "playlistManager", manager)
                setField(fragment, "playlistId", playlistId)
                setField(fragment, "itemListAdapter", adapter)
                setField(fragment, "debounceSaver", saver)
                setField(fragment, "isLoadingComplete", AtomicBoolean(true))
                setField(fragment, "databaseSubscription", observation)
                setField(fragment, "disposables", disposables)
                fragment.removeWatchedStreams(false)
            }
            assertTrue("Bulk mutation must reach the writer", scheduler.awaitScheduled())
            scheduler.triggerActions()
            assertTrue("Mutation failure callback must finish", failureFinished.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                failedDraft = adapter.itemsList.map { (it as org.schabi.newpipe.database.playlist.PlaylistStreamEntry).streamId }
                failedDirty = saver.getIsModified()
                failedRevision = saver.revision
                observationCancelled = observation.isCancelled
            }
            savedBeforeRetry = manager.getPlaylistStreams(playlistId).blockingFirst().map { it.streamId }
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_bulk_thumbnail")

            // Exercise the same public save called by onPause and the debounce timer, after the
            // real failure callback has had a chance to clear the adapter or cancel observation.
            instrumentation.runOnMainSync { fragment.saveImmediate() }
            scheduler.triggerActions()
            instrumentation.waitForIdleSync()

            val saved = manager.getPlaylistStreams(playlistId).blockingFirst()
            assertEquals(pendingIds, saved.map { it.streamId })
            assertEquals(pendingIds.indices.toList(), saved.map { it.joinIndex })
            assertEquals(pendingIds, failedDraft)
            assertTrue("Failed mutation must retain unsaved edits", failedDirty)
            assertEquals(1L, failedRevision)
            assertFalse("Failed mutation must preserve database observation", observationCancelled)
            assertEquals(streams.map { it.uid }, savedBeforeRetry)
            observation.awaitCount(2).assertNoErrors()
            assertEquals(pendingIds, observation.values().last().map { it.streamId })
            instrumentation.runOnMainSync { assertFalse(saver.getIsModified()) }
        } finally {
            observation.cancel()
            instrumentation.runOnMainSync {
                disposables.dispose()
                errorPanel?.dispose()
            }
        }
    }

    @Test
    fun readFailureClearsPresentationButLifecycleSaveCannotOverwriteStoredPlaylist() {
        val streams = insertStreams()
        val playlistId = database.playlistDAO().insert(
            PlaylistEntity(
                name = "playlist",
                isThumbnailPermanent = false,
                thumbnailStreamId = streams[0].uid,
                displayIndex = 0
            )
        )
        database.playlistStreamDAO().insertAll(
            streams.mapIndexed { index, stream -> PlaylistStreamEntity(playlistId, stream.uid, index) }
        )
        val scheduler = HeldWriter()
        val manager = LocalPlaylistManager(database, scheduler)
        val observation = manager.getPlaylistStreams(playlistId).test()
        observation.awaitCount(1).assertNoErrors()
        val entries = observation.values().first()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val disposables = CompositeDisposable()
        var errorPanel: ErrorPanelHelper? = null
        try {
            instrumentation.runOnMainSync {
                val themed = ContextThemeWrapper(context, R.style.LightTheme)
                val root = FrameLayout(themed).apply { id = android.R.id.content }
                root.addView(LayoutInflater.from(themed).inflate(R.layout.fragment_playlist, root, false))
                val fragment = ColdPlaylistFragment(themed, root, CountDownLatch(1))
                val adapter = LocalItemListAdapter(themed)
                adapter.addItems(entries.reversed())
                val saver = DebounceSaver(fragment)
                saver.setHasChangesToSave()
                errorPanel = ErrorPanelHelper(fragment, root, null)
                setField(fragment, "errorPanelHelper", errorPanel)
                setField(fragment, "playlistManager", manager)
                setField(fragment, "playlistId", playlistId)
                setField(fragment, "itemListAdapter", adapter)
                setField(fragment, "debounceSaver", saver)
                setField(fragment, "isLoadingComplete", AtomicBoolean(true))
                setField(fragment, "databaseSubscription", observation)
                setField(fragment, "disposables", disposables)

                // Exercise the public presentation used by read-observer failure. Clearing
                // that presentation must invalidate its eligibility for a later content save.
                fragment.showError(ErrorInfo(IOException("read failed"), UserAction.REQUESTED_BOOKMARK, "Loading local playlist"))
                assertTrue(adapter.itemsList.isEmpty())
                assertTrue(observation.isCancelled)
                fragment.saveImmediate()
            }
            scheduler.triggerActions()
            instrumentation.waitForIdleSync()

            val saved = manager.getPlaylistStreams(playlistId).blockingFirst()
            assertEquals(streams.map { it.uid }, saved.map { it.streamId })
            assertEquals(streams.indices.toList(), saved.map { it.joinIndex })
            assertEquals(streams[0].uid, database.playlistDAO().getPlaylist(playlistId).blockingFirst().single().thumbnailStreamId)
        } finally {
            observation.cancel()
            instrumentation.runOnMainSync {
                disposables.dispose()
                errorPanel?.dispose()
            }
        }
    }

    @Test
    fun readErrorDetachesQueuedMutationPresentationWhileAcceptedWriteFinishesAndReloadWorks() {
        val streams = insertStreams()
        val playlistId = database.playlistDAO().insert(
            PlaylistEntity(
                name = "playlist",
                isThumbnailPermanent = false,
                thumbnailStreamId = streams[0].uid,
                displayIndex = 0
            )
        )
        database.playlistStreamDAO().insertAll(
            streams.mapIndexed { index, stream -> PlaylistStreamEntity(playlistId, stream.uid, index) }
        )
        database.streamHistoryDAO().insert(
            StreamHistoryEntity(streams[0].uid, OffsetDateTime.now(ZoneOffset.UTC), 1L)
        )
        database.streamStateDAO().insert(StreamStateEntity(streams[0].uid, 120_000L))
        val scheduler = HeldWriter()
        val manager = LocalPlaylistManager(database, scheduler)
        val observation = manager.getPlaylistStreams(playlistId).test()
        observation.awaitCount(1).assertNoErrors()
        val entries = observation.values().first().associateBy { it.streamId }
        val pending = listOf(streams[2].uid, streams[1].uid)
        val firstReload = CountDownLatch(1)
        val committedReload = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val disposables = CompositeDisposable()
        var fragment: ColdPlaylistFragment? = null
        var errorPanel: ErrorPanelHelper? = null
        lateinit var adapter: LocalItemListAdapter
        try {
            instrumentation.runOnMainSync {
                val themed = ContextThemeWrapper(context, R.style.LightTheme)
                val root = FrameLayout(themed).apply { id = android.R.id.content }
                root.addView(LayoutInflater.from(themed).inflate(R.layout.fragment_playlist, root, false))
                val created = ColdPlaylistFragment(themed, root, CountDownLatch(1)) { delivered ->
                    firstReload.countDown()
                    if (delivered.map { it.streamId } == pending) {
                        committedReload.countDown()
                    }
                }
                fragment = created
                adapter = LocalItemListAdapter(themed)
                adapter.addItems(pending.map { entries.getValue(it) })
                val saver = DebounceSaver(created)
                saver.setHasChangesToSave()
                errorPanel = ErrorPanelHelper(created, root, null)
                setField(created, "errorPanelHelper", errorPanel)
                setField(created, "playlistManager", manager)
                setField(created, "playlistId", playlistId)
                setField(created, "itemListAdapter", adapter)
                setField(created, "debounceSaver", saver)
                setField(created, "isLoadingComplete", AtomicBoolean(true))
                setField(created, "databaseSubscription", observation)
                setField(created, "disposables", disposables)
                created.removeWatchedStreams(false)
            }
            assertTrue("Accepted mutation must be queued before the read error", scheduler.awaitScheduled())
            instrumentation.runOnMainSync {
                val created = checkNotNull(fragment)
                created.showError(ErrorInfo(IOException("read failed"), UserAction.REQUESTED_BOOKMARK, "Loading local playlist"))
                assertTrue(adapter.itemsList.isEmpty())
                created.startLoading(true)
            }
            assertTrue("Retry must deliver its read while the mutation remains queued", firstReload.await(10, TimeUnit.SECONDS))
            scheduler.triggerActions()
            assertTrue("The accepted mutation must finish and reach the fresh read observer", committedReload.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            assertEquals(pending, manager.getPlaylistStreams(playlistId).blockingFirst().map { it.streamId })
            // The headless read renderer only records delivery. An old bulk completion must
            // not independently repopulate the presentation after the read error detached it.
            instrumentation.runOnMainSync { assertTrue(adapter.itemsList.isEmpty()) }
        } finally {
            observation.cancel()
            instrumentation.runOnMainSync {
                disposables.dispose()
                if (fragment != null) {
                    fragment.onDestroyView()
                } else {
                    errorPanel?.dispose()
                }
            }
        }
    }

    private fun insertStreams(): List<StreamEntity> = (1..3).map { index ->
        StreamEntity(
            serviceId = 1,
            url = "https://example.com/watch/$index",
            title = "stream-$index",
            streamType = StreamType.VIDEO_STREAM,
            duration = 120L,
            uploader = "uploader"
        ).also { it.uid = database.streamDAO().insert(it) }
    }

    /** Supplies Android presentation without starting global application/database loading. */
    private class ColdPlaylistFragment(
        private val fixtureContext: Context,
        private val root: View,
        private val finished: CountDownLatch,
        private val render: ((List<PlaylistStreamEntry>) -> Unit)? = null
    ) : LocalPlaylistFragment() {
        override fun getContext(): Context = fixtureContext

        override fun getView(): View = root

        override fun handleResult(result: List<PlaylistStreamEntry>) {
            if (render == null) {
                super.handleResult(result)
            } else {
                render.invoke(result)
            }
        }

        override fun showLoading() = Unit

        override fun hideLoading() {
            finished.countDown()
        }

        override fun handleError() {
            super.handleError()
            finished.countDown()
        }
    }

    /** Holds accepted Room writer work until its asynchronous selection has settled. */
    private class HeldWriter : Scheduler() {
        private val delegate = TestScheduler()
        private val scheduled = CountDownLatch(1)

        override fun createWorker(): Worker {
            val worker = delegate.createWorker()
            return object : Worker() {
                override fun schedule(run: Runnable, delay: Long, unit: TimeUnit): Disposable {
                    val task = worker.schedule(run, delay, unit)
                    scheduled.countDown()
                    return task
                }

                override fun dispose() = worker.dispose()

                override fun isDisposed(): Boolean = worker.isDisposed
            }
        }

        fun awaitScheduled(): Boolean = scheduled.await(10, TimeUnit.SECONDS)

        fun triggerActions() = delegate.triggerActions()
    }

    private fun setField(target: Any, name: String, value: Any) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                type.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("No field $name on ${target.javaClass.name}")
    }
}
