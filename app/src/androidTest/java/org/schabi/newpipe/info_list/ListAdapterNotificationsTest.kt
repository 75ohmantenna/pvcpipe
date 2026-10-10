/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.info_list

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import java.util.function.Supplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.LocalItemListAdapter
import org.schabi.newpipe.testUtil.TestDatabase

/**
 * Pins the item counts, view types and change notifications of the two list adapters, including
 * where the local adapter's footer behaves differently from the remote one's.
 */
class ListAdapterNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        // Both adapters build a HistoryRecordManager, which opens the application database.
        database = TestDatabase.createReplacingNewPipeDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun infoAdapterAppendsAfterHeaderAndKeepsFooterLast() {
        val adapter = InfoListAdapter(context)
        val events = Recorder().also(adapter::registerAdapterDataObserver)

        adapter.addInfoItemList(null)
        assertEquals(emptyList<String>(), events.take())

        adapter.addInfoItemList(streams(3))
        assertEquals(listOf("inserted(0,3)"), events.take())
        assertEquals(listOf(STREAM, STREAM, STREAM), adapter.viewTypes())

        adapter.showFooter(true)
        assertEquals(listOf("inserted(3,1)"), events.take())
        adapter.showFooter(true)
        assertEquals(emptyList<String>(), events.take())
        assertEquals(listOf(STREAM, STREAM, STREAM, FOOTER), adapter.viewTypes())

        adapter.addInfoItemList(streams(2))
        assertEquals(listOf("inserted(3,2)", "moved(3,5)"), events.take())
        assertEquals(listOf(STREAM, STREAM, STREAM, STREAM, STREAM, FOOTER), adapter.viewTypes())

        adapter.showFooter(false)
        assertEquals(listOf("removed(5,1)"), events.take())

        val header = Supplier<View> { View(context) }
        adapter.setHeaderSupplier(header)
        assertEquals(listOf("changed"), events.take())
        adapter.setHeaderSupplier(header)
        assertEquals(emptyList<String>(), events.take())
        assertEquals(listOf(HEADER, STREAM, STREAM, STREAM, STREAM, STREAM), adapter.viewTypes())

        adapter.addInfoItemList(streams(1))
        assertEquals(listOf("inserted(6,1)"), events.take())

        adapter.showFooter(true)
        assertEquals(listOf("inserted(7,1)"), events.take())
        adapter.addInfoItemList(streams(1))
        assertEquals(listOf("inserted(7,1)", "moved(7,8)"), events.take())
        assertEquals(9, adapter.itemCount)
        assertEquals(7, adapter.itemsList.size)
    }

    @Test
    fun infoAdapterClearAndHeaderReplacementRefreshEverything() {
        val adapter = InfoListAdapter(context)
        val events = Recorder().also(adapter::registerAdapterDataObserver)

        adapter.clearStreamItemList()
        assertEquals(emptyList<String>(), events.take())

        adapter.setHeaderSupplier { View(context) }
        adapter.showFooter(true)
        adapter.addInfoItemList(streams(2))
        events.take()

        adapter.clearStreamItemList()
        assertEquals(listOf("changed"), events.take())
        assertEquals(listOf(HEADER, FOOTER), adapter.viewTypes())

        adapter.setHeaderSupplier { View(context) }
        assertEquals(listOf("changed"), events.take())
        adapter.setHeaderSupplier(null)
        assertEquals(listOf("changed"), events.take())
        assertEquals(listOf(FOOTER), adapter.viewTypes())
    }

    @Test
    fun infoAdapterSpansHeaderAndFooterAcrossTheGrid() {
        val adapter = InfoListAdapter(context)
        adapter.setHeaderSupplier { View(context) }
        adapter.showFooter(true)
        adapter.addInfoItemList(streams(2))

        val spans = adapter.getSpanSizeLookup(3)

        assertEquals(listOf(3, 1, 1, 3), (0 until adapter.itemCount).map(spans::getSpanSize))
    }

    @Test
    fun localAdapterCountsFooterOnlyOnceAFooterViewIsSet() {
        val adapter = LocalItemListAdapter(context)
        val events = Recorder().also(adapter::registerAdapterDataObserver)

        adapter.addItems(null)
        assertEquals(emptyList<String>(), events.take())

        adapter.addItems(playlists(1, 2, 3))
        assertEquals(listOf("inserted(0,3)"), events.take())

        // Without a footer view the flag still notifies, but no footer row exists.
        adapter.showFooter(true)
        assertEquals(listOf("inserted(3,1)"), events.take())
        assertEquals(listOf(PLAYLIST, PLAYLIST, PLAYLIST), adapter.viewTypes())
        adapter.addItems(playlists(4))
        assertEquals(listOf("inserted(3,1)"), events.take())

        adapter.setFooter(View(context))
        assertEquals(emptyList<String>(), events.take())
        assertEquals(listOf(PLAYLIST, PLAYLIST, PLAYLIST, PLAYLIST, FOOTER), adapter.viewTypes())

        adapter.addItems(playlists(5, 6))
        assertEquals(listOf("inserted(4,2)", "moved(4,6)"), events.take())

        adapter.showFooter(false)
        assertEquals(listOf("removed(6,1)"), events.take())
        adapter.showFooter(false)
        assertEquals(emptyList<String>(), events.take())
        assertEquals(6, adapter.itemCount)
    }

    @Test
    fun localAdapterHeaderShiftsPositionsAndClearRefreshesEverything() {
        val adapter = LocalItemListAdapter(context)
        val events = Recorder().also(adapter::registerAdapterDataObserver)

        adapter.clearStreamItemList()
        assertEquals(emptyList<String>(), events.take())

        val header = Supplier<View> { View(context) }
        adapter.setHeaderSupplier(header)
        assertEquals(listOf("changed"), events.take())
        adapter.setHeaderSupplier(header)
        assertEquals(emptyList<String>(), events.take())

        adapter.addItems(playlists(1, 2))
        assertEquals(listOf("inserted(1,2)"), events.take())
        assertEquals(listOf(HEADER, PLAYLIST, PLAYLIST), adapter.viewTypes())

        adapter.setFooter(View(context))
        adapter.showFooter(true)
        assertEquals(listOf("inserted(3,1)"), events.take())
        val spans = adapter.getSpanSizeLookup(4)
        assertEquals(listOf(4, 1, 1, 4), (0 until adapter.itemCount).map(spans::getSpanSize))

        adapter.clearStreamItemList()
        assertEquals(listOf("changed"), events.take())
        assertEquals(listOf(HEADER, FOOTER), adapter.viewTypes())

        adapter.setHeaderSupplier(null)
        assertEquals(listOf("changed"), events.take())
    }

    @Test
    fun localAdapterRemovesKnownItemsPreciselyAndRefreshesForUnknownOnes() {
        val adapter = LocalItemListAdapter(context)
        val items = playlists(1, 2, 3)
        adapter.setHeaderSupplier { View(context) }
        adapter.addItems(items)
        val events = Recorder().also(adapter::registerAdapterDataObserver)

        adapter.removeItem(items[1])
        assertEquals(listOf("removed(2,1)"), events.take())
        assertEquals(listOf(items[0], items[2]), adapter.itemsList)

        adapter.removeItem(items[1])
        assertEquals(listOf("changed"), events.take())
        assertEquals(listOf(items[0], items[2]), adapter.itemsList)
    }

    @Test
    fun localAdapterSwapsItemsByAdapterPositionAndRejectsHeaderOrOutOfRange() {
        val adapter = LocalItemListAdapter(context)
        val items = playlists(1, 2, 3)
        adapter.setHeaderSupplier { View(context) }
        adapter.addItems(items)
        val events = Recorder().also(adapter::registerAdapterDataObserver)

        assertTrue(adapter.swapItems(1, 3))
        assertEquals(listOf("moved(1,3)"), events.take())
        assertEquals(listOf(items[1], items[2], items[0]), adapter.itemsList)

        assertFalse(adapter.swapItems(0, 2))
        assertFalse(adapter.swapItems(1, 4))
        assertEquals(emptyList<String>(), events.take())
        assertEquals(listOf(items[1], items[2], items[0]), adapter.itemsList)
    }

    private fun streams(count: Int): List<InfoItem> = (1..count).map {
        StreamInfoItem(0, "https://example.com/watch?v=$it", "stream $it", StreamType.VIDEO_STREAM)
    }

    private fun playlists(vararg ids: Long): List<LocalItem> = ids.map {
        PlaylistMetadataEntry(it, "playlist $it", null, it, false, -1L, 0L)
    }

    private fun RecyclerView.Adapter<*>.viewTypes(): List<Int> = (0 until itemCount).map { getItemViewType(it) }

    private class Recorder : RecyclerView.AdapterDataObserver() {
        private val events = ArrayList<String>()

        fun take(): List<String> = ArrayList(events).also { events.clear() }

        override fun onChanged() {
            events += "changed"
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            events += "itemsChanged($positionStart,$itemCount)"
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            events += "inserted($positionStart,$itemCount)"
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            events += "removed($positionStart,$itemCount)"
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            events += "moved($fromPosition,$toPosition)"
        }
    }

    private companion object {
        const val HEADER = 0
        const val FOOTER = 1
        const val STREAM = 0x101
        const val PLAYLIST = 0x2000
    }
}
