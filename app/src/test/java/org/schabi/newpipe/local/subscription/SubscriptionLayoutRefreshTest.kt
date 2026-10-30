/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription

import android.app.Application
import android.widget.RelativeLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.Section
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.databinding.FragmentSubscriptionBinding
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.local.subscription.item.ChannelItem
import org.schabi.newpipe.local.subscription.item.Header
import org.schabi.newpipe.util.ThemeHelper

/** Real resume/adapter code with a minimal binding and controlled theme readings; no Settings UI. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class SubscriptionLayoutRefreshTest {
    private lateinit var themes: MockedStatic<ThemeHelper>
    private lateinit var fragment: SubscriptionFragment
    private lateinit var adapter: GroupAdapter<*>
    private lateinit var section: Section
    private lateinit var manager: GridLayoutManager
    private lateinit var recycler: RecyclerView
    private lateinit var observer: RecyclerView.AdapterDataObserver
    private var mode = ItemViewMode.LIST
    private var gridSpans = 2

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        themes = Mockito.mockStatic(ThemeHelper::class.java)
        themes.`when`<ItemViewMode> { ThemeHelper.getItemViewMode(context) }.thenAnswer { mode }
        themes.`when`<Int> { ThemeHelper.getGridSpanCountChannels(context) }.thenAnswer { gridSpans }
        fragment = Mockito.spy(SubscriptionFragment())
        doReturn(context).`when`(fragment).context
        doNothing().`when`(fragment).hideLoading()
        adapter = ReflectionHelpers.getField(fragment, "groupAdapter")
        section = ReflectionHelpers.getField(fragment, "subscriptionsSection")
        adapter.add(Section(Header("Subscriptions"), listOf(section)))
        manager = Mockito.spy(GridLayoutManager(context, 1)).apply { spanSizeLookup = adapter.spanSizeLookup }
        adapter.spanCount = 1
        recycler = RecyclerView(context).apply {
            layoutManager = manager
            adapter = this@SubscriptionLayoutRefreshTest.adapter
        }
        val binding = FragmentSubscriptionBinding::class.java.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(RelativeLayout(context), null, null, recycler) as FragmentSubscriptionBinding
        ReflectionHelpers.setField(fragment, "_binding", binding)
        loadRows()
        observer = Mockito.mock(RecyclerView.AdapterDataObserver::class.java)
        adapter.registerAdapterDataObserver(observer)
    }

    @After
    fun cleanup() {
        if (::themes.isInitialized) themes.close()
    }

    private fun loadRows() {
        val rows = listOf(
            Header(0),
            ChannelItem(SubscriptionEntity(uid = 41L, serviceId = 0, url = "https://fixture.invalid/one", name = "One").toChannelInfoItem(), 41L),
            ChannelItem(SubscriptionEntity(uid = 42L, serviceId = 0, url = "https://fixture.invalid/two", name = "Two").toChannelInfoItem(), 42L),
            Header(99),
            ChannelItem(SubscriptionEntity(uid = 43L, serviceId = 99, url = "https://fixture.invalid/three", name = "Three").toChannelInfoItem(), 43L)
        )
        fragment.handleResult(SubscriptionViewModel.SubscriptionState.LoadedState(rows))
    }

    private fun assertLayout(version: ChannelItem.ItemVersion, spans: Int) {
        assertSame(adapter, recycler.adapter)
        assertSame(manager, recycler.layoutManager)
        assertEquals(spans, manager.spanCount)
        assertEquals(spans, adapter.spanCount)
        val channels = (0 until adapter.itemCount).map(adapter::getItem).filterIsInstance<ChannelItem>()
        assertEquals(listOf(41L, 42L, 43L), channels.map { it.id })
        assertEquals(List(3) { version }, channels.map { it.itemVersion })
        for (position in 0 until adapter.itemCount) {
            if (adapter.getItem(position) is Header) assertEquals(spans, manager.spanSizeLookup.getSpanSize(position))
            if (adapter.getItem(position) is ChannelItem) {
                assertEquals(if (version == ChannelItem.ItemVersion.GRID) R.layout.list_channel_grid_item else R.layout.list_channel_mini_item, adapter.getItemViewType(position))
            }
        }
    }

    @Test
    fun `resume applies list to grid and back without replacing the adapter or manager`() {
        val headers = (0 until adapter.itemCount).map(adapter::getItem).filterIsInstance<Header>()
        val scrollState = manager.onSaveInstanceState()
        doReturn(scrollState).`when`(manager).onSaveInstanceState()
        mode = ItemViewMode.GRID
        fragment.onResume()
        assertLayout(ChannelItem.ItemVersion.GRID, 2)
        verify(observer).onItemRangeChanged(1, 5, null)
        verify(observer, never()).onChanged()
        verify(manager).onRestoreInstanceState(scrollState)
        mode = ItemViewMode.LIST
        fragment.onResume()
        assertLayout(ChannelItem.ItemVersion.MINI, 1)
        headers.forEach { header -> assertSame(header, (0 until adapter.itemCount).map(adapter::getItem).first { it === header }) }
    }

    @Test
    fun `single column grid still changes row types when returning to list`() {
        gridSpans = 1
        mode = ItemViewMode.GRID
        fragment.onResume()
        assertLayout(ChannelItem.ItemVersion.GRID, 1)
        mode = ItemViewMode.LIST
        fragment.onResume()
        assertLayout(ChannelItem.ItemVersion.MINI, 1)
    }

    @Test
    fun `unchanged resume does not invalidate the rows`() {
        fragment.onResume()
        fragment.onResume()
        assertLayout(ChannelItem.ItemVersion.MINI, 1)
        verifyNoInteractions(observer)
        verify(manager, never()).onSaveInstanceState()
    }

    @Test
    fun `grid width changes refresh spans even when the row type stays grid`() {
        mode = ItemViewMode.GRID
        fragment.onResume()
        assertLayout(ChannelItem.ItemVersion.GRID, 2)
        gridSpans = 3
        fragment.onResume()
        assertLayout(ChannelItem.ItemVersion.GRID, 3)
    }

    @Test
    fun `data arriving before resume uses the applied layout then changes coherently`() {
        mode = ItemViewMode.CARD
        loadRows()
        assertLayout(ChannelItem.ItemVersion.MINI, 1)
        fragment.onResume()
        assertLayout(ChannelItem.ItemVersion.GRID, 2)
        loadRows()
        assertLayout(ChannelItem.ItemVersion.GRID, 2)
    }

    @Test
    fun `empty section uses the changed layout when data arrives later`() {
        section.update(emptyList())
        mode = ItemViewMode.GRID
        fragment.onResume()
        loadRows()
        assertLayout(ChannelItem.ItemVersion.GRID, 2)
    }
}
