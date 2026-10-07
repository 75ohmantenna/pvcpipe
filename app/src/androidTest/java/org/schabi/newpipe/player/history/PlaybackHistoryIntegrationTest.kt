/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.history

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK
import com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
import com.google.android.exoplayer2.Player.REPEAT_MODE_ONE
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.testUtil.TrampolineSchedulerRule
import org.schabi.newpipe.util.InfoCache

/** Exercises the production preferences and history adapters against real in-memory Room. */
class PlaybackHistoryIntegrationTest {
    @get:Rule
    val trampolineScheduler = TrampolineSchedulerRule()

    private lateinit var application: Context
    private lateinit var context: Context
    private lateinit var preferenceName: String
    private lateinit var preferences: SharedPreferences
    private lateinit var database: AppDatabase
    private lateinit var history: PlaybackHistory
    private lateinit var watchHistoryKey: String
    private val cachedInfos = mutableListOf<StreamInfo>()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        preferenceName = "playback-history-test-${UUID.randomUUID()}"
        preferences = application.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        watchHistoryKey = application.getString(R.string.enable_watch_history_key)
        context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        database = TestDatabase.createReplacingNewPipeDatabase()
        history = PlaybackHistory(context)
    }

    @After
    fun tearDown() {
        cachedInfos.forEach {
            InfoCache.getInstance().removeInfo(it.serviceId, it.url, InfoCache.Type.STREAM)
        }
        database.close()
        application.deleteSharedPreferences(preferenceName)
    }

    @Test
    fun normalProgressPersistsPositionAndKeepsContentRecoveryDistinct() {
        setRecordingEnabled(true)
        val info = info("normal")
        val queue = SinglePlayQueue(info)

        history.record(PlaybackHistory.Event.PROGRESS, snapshot(info, queue, 6000, 4500))

        assertEquals(4500L, queue.item!!.recoveryPosition)
        assertEquals(6000L, state(info)!!.progressMillis)
        assertEquals(6000L, history.resumePosition(queue.item!!).blockingGet().toLong())
    }

    @Test
    fun saveThresholdRetainsStrictStartAndQuarterDurationRules() {
        setRecordingEnabled(true)
        val longInfo = info("threshold-long")
        history.record(
            PlaybackHistory.Event.PROGRESS,
            snapshot(longInfo, SinglePlayQueue(longInfo), 5000)
        )
        assertNull(state(longInfo))

        val shortInfo = info("threshold-short", duration = 8)
        val shortQueue = SinglePlayQueue(shortInfo)
        history.record(PlaybackHistory.Event.PROGRESS, snapshot(shortInfo, shortQueue, 2000))
        assertNull(state(shortInfo))
        history.record(PlaybackHistory.Event.PROGRESS, snapshot(shortInfo, shortQueue, 2001))
        assertEquals(2001L, state(shortInfo)!!.progressMillis)
    }

    @Test
    fun repeatedPlaybackIncrementsOneStoredViewCount() {
        setRecordingEnabled(true)
        val info = info("repeat")
        val queue = SinglePlayQueue(info)
        val snapshot = snapshot(info, queue, 7000)
        history.record(PlaybackHistory.Event.VIEWED, snapshot)
        history.onDiscontinuity(
            snapshot,
            PlaybackHistory.Discontinuity(
                DISCONTINUITY_REASON_AUTO_TRANSITION,
                0,
                REPEAT_MODE_ONE,
                true,
                false
            )
        )

        val entries = database.streamHistoryDAO().getAll().blockingFirst()
        assertEquals(1, entries.size)
        assertEquals(2L, entries.single().repeatCount)
        assertNull(state(info))
    }

    @Test
    fun completedMarkerIsPersistedButCannotResumePlayback() {
        setRecordingEnabled(true)
        val info = info("completed")
        val queue = SinglePlayQueue(info)

        history.record(PlaybackHistory.Event.COMPLETED, snapshot(info, queue, 7000))

        assertEquals(121000L, state(info)!!.progressMillis)
        assertEquals(
            PlayQueueItem.RECOVERY_UNSET,
            history.resumePosition(queue.item!!).blockingGet().toLong()
        )
    }

    @Test
    fun disabledRecordingStillUpdatesQueueRecoveryWithoutDatabaseWrites() {
        setRecordingEnabled(false)
        val info = info("disabled")
        val queue = SinglePlayQueue(info)
        val snapshot = snapshot(info, queue, 8000, 6500)

        history.record(PlaybackHistory.Event.VIEWED, snapshot)
        history.record(PlaybackHistory.Event.PROGRESS, snapshot)
        history.record(PlaybackHistory.Event.COMPLETED, snapshot)

        assertEquals(6500L, queue.item!!.recoveryPosition)
        assertTrue(database.streamDAO().getAll().blockingFirst().isEmpty())
        assertTrue(database.streamHistoryDAO().getAll().blockingFirst().isEmpty())
    }

    @Test
    fun missingPreferencePreservesDifferentViewAndProgressDefaults() {
        val info = info("missing-preference")
        val queue = SinglePlayQueue(info)
        val snapshot = snapshot(info, queue, 8000)

        history.record(PlaybackHistory.Event.VIEWED, snapshot)
        history.record(PlaybackHistory.Event.PROGRESS, snapshot)

        assertTrue(database.streamHistoryDAO().getAll().blockingFirst().isEmpty())
        assertEquals(8000L, state(info)!!.progressMillis)
    }

    @Test
    fun mismatchedQueueAndMediaIndicesCannotSaveTheWrongStream() {
        setRecordingEnabled(true)
        val info = info("mismatch")
        val queue = SinglePlayQueue(info)

        history.record(
            PlaybackHistory.Event.PROGRESS,
            PlaybackHistory.Snapshot(info, queue, 1, 7500, 8000)
        )

        assertEquals(PlayQueueItem.RECOVERY_UNSET, queue.item!!.recoveryPosition)
        assertTrue(database.streamDAO().getAll().blockingFirst().isEmpty())
    }

    @Test
    fun preparedSeekRecordsProgressThroughTheProductionAdapter() {
        setRecordingEnabled(true)
        val info = info("seek")
        val queue = SinglePlayQueue(info)

        history.onDiscontinuity(
            snapshot(info, queue, 9000, 8500),
            PlaybackHistory.Discontinuity(
                DISCONTINUITY_REASON_SEEK,
                0,
                REPEAT_MODE_OFF,
                true,
                false
            )
        )

        assertEquals(8500L, queue.item!!.recoveryPosition)
        assertEquals(9000L, state(info)!!.progressMillis)
    }

    @Test
    fun acceptedCheckpointRemainsOwnedAfterHistoryInstanceReplacement() {
        setRecordingEnabled(true)
        val info = info("replacement-checkpoint")
        val queue = SinglePlayQueue(info)
        val scheduler = TestScheduler()
        val previousHandler = RxJavaPlugins.getIoSchedulerHandler()
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        try {
            history.record(PlaybackHistory.Event.PROGRESS, snapshot(info, queue, 8000))
            history = PlaybackHistory(context)
            scheduler.triggerActions()

            assertEquals(8000L, state(info)?.progressMillis)
        } finally {
            scheduler.triggerActions()
            RxJavaPlugins.setIoSchedulerHandler(previousHandler)
        }
    }

    @Test
    fun completionCannotBeOverwrittenByAnEarlierDelayedCheckpoint() {
        setRecordingEnabled(true)
        val info = info("ordered-completion")
        val queue = SinglePlayQueue(info)
        val earlierScheduler = TestScheduler()
        val laterScheduler = TestScheduler()
        var schedulerSelections = 0
        val previousHandler = RxJavaPlugins.getIoSchedulerHandler()
        RxJavaPlugins.setIoSchedulerHandler {
            if (schedulerSelections++ == 0) earlierScheduler else laterScheduler
        }
        try {
            history.record(PlaybackHistory.Event.PROGRESS, snapshot(info, queue, 8000))
            history.record(PlaybackHistory.Event.COMPLETED, snapshot(info, queue, 8000))

            // Give the later write an opportunity to run before the earlier checkpoint.
            laterScheduler.triggerActions()
            earlierScheduler.triggerActions()
            laterScheduler.triggerActions()

            assertEquals(121000L, state(info)?.progressMillis)
        } finally {
            earlierScheduler.triggerActions()
            laterScheduler.triggerActions()
            RxJavaPlugins.setIoSchedulerHandler(previousHandler)
        }
    }

    @Test
    fun replacementHistorySharesOrderingWithEarlierAcceptedWrites() {
        setRecordingEnabled(true)
        val info = info("shared-write-order")
        val queue = SinglePlayQueue(info)
        val replacement = PlaybackHistory(context)
        val earlierScheduler = TestScheduler()
        val laterScheduler = TestScheduler()
        var schedulerSelections = 0
        val previousHandler = RxJavaPlugins.getIoSchedulerHandler()
        RxJavaPlugins.setIoSchedulerHandler {
            if (schedulerSelections++ == 0) earlierScheduler else laterScheduler
        }
        try {
            history.record(PlaybackHistory.Event.PROGRESS, snapshot(info, queue, 8000))
            replacement.record(PlaybackHistory.Event.PROGRESS, snapshot(info, queue, 9000))

            laterScheduler.triggerActions()
            earlierScheduler.triggerActions()
            laterScheduler.triggerActions()

            assertEquals(9000L, state(info)?.progressMillis)
        } finally {
            earlierScheduler.triggerActions()
            laterScheduler.triggerActions()
            RxJavaPlugins.setIoSchedulerHandler(previousHandler)
        }
    }

    @Test
    fun replacementResumeWaitsForEarlierAcceptedRoomCheckpoint() {
        setRecordingEnabled(true)
        val info = info("shared-read-order")
        val queue = SinglePlayQueue(info)
        val replacement = PlaybackHistory(context)
        val writeScheduler = TestScheduler()
        val readScheduler = TestScheduler()
        var schedulerSelections = 0
        val previousHandler = RxJavaPlugins.getIoSchedulerHandler()
        RxJavaPlugins.setIoSchedulerHandler {
            if (schedulerSelections++ == 0) writeScheduler else readScheduler
        }
        try {
            history.record(PlaybackHistory.Event.PROGRESS, snapshot(info, queue, 8000))
            val resume = replacement.resumePosition(queue.item!!).test()
            try {
                readScheduler.triggerActions()
                resume.assertNoValues().assertNotComplete()
                // Resume loading upserts stream metadata before observing Room state. No lookup
                // may begin while the earlier accepted checkpoint is still waiting to execute.
                assertTrue(database.streamDAO().getAll().blockingFirst().isEmpty())

                writeScheduler.triggerActions()
                readScheduler.triggerActions()

                assertTrue(resume.await(5, TimeUnit.SECONDS))
                resume.assertValue(8000L).assertComplete().assertNoErrors()
            } finally {
                resume.dispose()
            }
        } finally {
            writeScheduler.triggerActions()
            readScheduler.triggerActions()
            RxJavaPlugins.setIoSchedulerHandler(previousHandler)
        }
    }

    private fun setRecordingEnabled(enabled: Boolean) {
        assertTrue(preferences.edit().putBoolean(watchHistoryKey, enabled).commit())
    }

    private fun state(info: StreamInfo): StreamStateEntity? {
        val stream = database.streamDAO().getStream(info.serviceId.toLong(), info.url).blockingFirst()
        return stream.firstOrNull()?.let { database.streamStateDAO().getStateSync(it.uid) }
    }

    private fun snapshot(
        info: StreamInfo,
        queue: SinglePlayQueue,
        position: Long,
        contentPosition: Long = position
    ) = PlaybackHistory.Snapshot(info, queue, 0, contentPosition, position)

    private fun info(id: String, duration: Long = 120): StreamInfo = StreamInfo(
        ServiceList.MediaCCC.serviceId,
        "https://example.invalid/history/$id",
        "https://example.invalid/history/$id",
        StreamType.VIDEO_STREAM,
        id,
        "Fixture $id",
        0
    ).apply {
        this.duration = duration
        uploaderName = "Fixture uploader"
        InfoCache.getInstance().putInfo(serviceId, url, this, InfoCache.Type.STREAM)
        cachedInfos.add(this)
    }
}
