/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription.workers

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.subscription.SubscriptionManager

/** Exercises transfer JSON, subscription upserts, and feed writes against isolated Room storage. */
class SubscriptionTransferDatabaseTest {
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
    fun importAcrossBatchesPreservesSubscriptionIdentityAndExportsActualDatabaseSnapshot() = runBlocking {
        val original = insertOriginal()
        val items = subscriptionItems(51)
        val destination = ByteArrayOutputStream()
        val transfer = transfer(items, destination)
        val progress = ConcurrentLinkedQueue<SubscriptionTransfer.Progress>()

        val imported = transfer.`import`(SubscriptionImportInput.PreviousExportMode(INPUT)) { progress.add(it) }

        assertEquals(SubscriptionTransfer.Outcome.Success(51), imported)
        assertEquals(51, rowCount("subscriptions"))
        assertEquals(51, rowCount("streams"))
        assertEquals(51, rowCount("feed"))
        assertEquals(51, rowCount("feed_last_updated"))
        val updated = database.subscriptionDAO().getSubscription(1, channelUrl(1)).blockingGet()!!
        assertEquals(original.uid, updated.uid)
        assertEquals("updated-1", updated.name)
        assertEquals("description-1", updated.description)
        assertEquals(101L, updated.subscriberCount)
        assertEquals(
            listOf(0, 50, 51),
            progress.filterIsInstance<SubscriptionTransfer.Progress.Importing>().map { it.current }
        )
        items.forEach { item ->
            val stored = database.subscriptionDAO().getSubscription(item.serviceId, item.url).blockingGet()!!
            assertEquals(1, feedCountFor(stored.uid))
            assertTrue(database.streamDAO().exists(1, streamUrl(item.url.substringAfterLast('/').toInt())))
        }

        val exported = transfer.export(OUTPUT) { }

        assertEquals(SubscriptionTransfer.Outcome.Success(51), exported)
        val json = JSONObject(destination.toString(Charsets.UTF_8.name()))
        val exportedItems = json.getJSONArray("subscriptions")
        assertEquals(51, exportedItems.length())
        val exportedNames = (0 until exportedItems.length()).associate { index ->
            val item = exportedItems.getJSONObject(index)
            assertEquals(1, item.getInt("service_id"))
            item.getString("url") to item.getString("name")
        }
        assertEquals((1..51).associate { channelUrl(it) to "updated-$it" }, exportedNames)
        assertTrue(json.has("app_version"))
        assertTrue(json.has("app_version_int"))
    }

    @Test
    fun extractionFailureLeavesExistingSubscriptionAndFeedUnchanged() = runBlocking {
        val original = insertOriginal()
        val failure = IOException("Channel extraction failed")
        val transfer = transfer(subscriptionItems(3)) { item ->
            if (item.url == channelUrl(2)) {
                throw failure
            }
            extracted(item)
        }

        val result = transfer.`import`(SubscriptionImportInput.PreviousExportMode(INPUT)) { }

        assertTrue(result is SubscriptionTransfer.Outcome.Failure)
        assertEquals(1, rowCount("subscriptions"))
        assertEquals(original, database.subscriptionDAO().getSubscription(original.uid))
        assertEquals(0, rowCount("streams"))
        assertEquals(0, rowCount("feed"))
        assertEquals(0, rowCount("feed_last_updated"))
    }

    @Test
    fun failingSecondBatchRollsBackItsSubscriptionsAndFeedButKeepsFirstBatch() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_final_transfer_stream
            BEFORE INSERT ON streams
            WHEN NEW.url = '${streamUrl(52)}'
            BEGIN SELECT RAISE(ABORT, 'reject final transfer stream'); END
            """.trimIndent()
        )
        val transfer = transfer(subscriptionItems(52))
        val progress = ConcurrentLinkedQueue<SubscriptionTransfer.Progress>()

        val result = transfer.`import`(SubscriptionImportInput.PreviousExportMode(INPUT)) { progress.add(it) }
        assertTrue(result is SubscriptionTransfer.Outcome.Failure)
        assertTrue((result as SubscriptionTransfer.Outcome.Failure).cause is SQLiteException)

        assertEquals(50, rowCount("subscriptions"))
        assertEquals(50, rowCount("streams"))
        assertEquals(50, rowCount("feed"))
        assertEquals(50, rowCount("feed_last_updated"))
        assertEquals(
            listOf(0, 50),
            progress.filterIsInstance<SubscriptionTransfer.Progress.Importing>().map { it.current }
        )
        assertTrue(database.subscriptionDAO().getSubscription(1, channelUrl(51)).isEmpty.blockingGet())
        assertTrue(database.subscriptionDAO().getSubscription(1, channelUrl(52)).isEmpty.blockingGet())
        assertFalse(database.streamDAO().exists(1, streamUrl(51)))
        assertFalse(database.streamDAO().exists(1, streamUrl(52)))
        (1..50).forEach { index ->
            val stored = database.subscriptionDAO().getSubscription(1, channelUrl(index)).blockingGet()!!
            assertEquals(1, feedCountFor(stored.uid))
        }
    }

    private fun transfer(
        items: List<SubscriptionItem>,
        output: ByteArrayOutputStream = ByteArrayOutputStream(),
        extraction: suspend (SubscriptionItem) -> Pair<ChannelInfo, ChannelTabInfo> = { extracted(it) }
    ): SubscriptionTransfer {
        val json = """{"subscriptions":[${items.joinToString(",") {
            """{"service_id":${it.serviceId},"url":"${it.url}","name":"${it.name}"}"""
        }}]}"""
        val operations = object : SubscriptionTransfer.AndroidOperations(context, SubscriptionManager(database)) {
            override fun openInput(source: String): InputStream {
                assertEquals(INPUT, source)
                return ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
            }

            override fun openOutput(destination: String): OutputStream {
                assertEquals(OUTPUT, destination)
                return output
            }

            override suspend fun extract(item: SubscriptionItem): Pair<ChannelInfo, ChannelTabInfo> = extraction(item)
        }
        return SubscriptionTransfer(operations)
    }

    private fun insertOriginal(): SubscriptionEntity {
        return database.subscriptionDAO().upsertAll(
            listOf(
                SubscriptionEntity(
                    serviceId = 1,
                    url = channelUrl(1),
                    name = "original",
                    description = "original description",
                    subscriberCount = 7L
                )
            )
        ).single()
    }

    private fun subscriptionItems(count: Int): List<SubscriptionItem> = (1..count).map { index ->
        SubscriptionItem(1, channelUrl(index), "source-$index")
    }

    private fun extracted(item: SubscriptionItem): Pair<ChannelInfo, ChannelTabInfo> {
        val index = item.url.substringAfterLast('/').toInt()
        val handler = ListLinkHandler(item.url, item.url, "$index", listOf(ChannelTabs.VIDEOS), emptyList())
        val channel = ChannelInfo(item.serviceId, "$index", item.url, item.url, "updated-$index").apply {
            description = "description-$index"
            subscriberCount = 100L + index
            tabs = listOf(handler)
        }
        val stream = StreamInfoItem(item.serviceId, streamUrl(index), "stream-$index", StreamType.VIDEO_STREAM).apply {
            uploaderName = channel.name
            uploaderUrl = channel.url
            duration = 60
            uploadDate = DateWrapper(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
        }
        val tab = ChannelTabInfo(item.serviceId, handler).apply {
            relatedItems = listOf(stream)
        }
        return channel to tab
    }

    private fun rowCount(table: String): Int = database.query("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun feedCountFor(subscriptionId: Long): Int = database.query(
        "SELECT COUNT(*) FROM feed WHERE subscription_id = ?",
        arrayOf(subscriptionId)
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun channelUrl(index: Int) = "https://example.com/channel/$index"

    private fun streamUrl(index: Int) = "https://example.com/watch/$index"

    companion object {
        private const val INPUT = "content://transfer-test/import.json"
        private const val OUTPUT = "content://transfer-test/export.json"
    }
}
