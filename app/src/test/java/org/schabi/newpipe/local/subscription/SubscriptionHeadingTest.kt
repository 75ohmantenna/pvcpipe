/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription

import android.app.Application
import android.os.Looper
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.ViewModelStore
import com.xwray.groupie.Group
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.processors.BehaviorProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.databinding.SubscriptionHeaderBinding
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.item.ChannelItem
import org.schabi.newpipe.local.subscription.item.Header
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.views.NewPipeTextView

/** Real view-model grouping and header binding; managers and label resources are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class SubscriptionHeadingTest {
    private val source = BehaviorProcessor.createDefault<List<SubscriptionEntity>>(emptyList())
    private val clock = TestScheduler()
    private lateinit var feeds: MockedConstruction<FeedDatabaseManager>
    private lateinit var subscriptions: MockedConstruction<SubscriptionManager>
    private lateinit var themes: MockedStatic<ThemeHelper>
    private lateinit var model: SubscriptionViewModel

    @Before
    fun setup() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setComputationSchedulerHandler { clock }
        feeds = Mockito.mockConstruction(FeedDatabaseManager::class.java) { manager, _ ->
            `when`(manager.groups()).thenReturn(Flowable.just(emptyList()))
        }
        subscriptions = Mockito.mockConstruction(SubscriptionManager::class.java) { manager, _ ->
            `when`(manager.subscriptions()).thenReturn(source)
        }
        val application = RuntimeEnvironment.getApplication()
        themes = Mockito.mockStatic(ThemeHelper::class.java)
        themes.`when`<ItemViewMode> { ThemeHelper.getItemViewMode(application) }.thenReturn(ItemViewMode.LIST)
        model = SubscriptionViewModel(application)
    }

    @After
    fun cleanup() {
        try {
            if (::model.isInitialized) {
                ViewModelStore().apply {
                    put("subscriptions", model)
                    clear()
                }
            }
        } finally {
            if (::themes.isInitialized) themes.close()
            if (::subscriptions.isInitialized) subscriptions.close()
            if (::feeds.isInitialized) feeds.close()
            RxJavaPlugins.reset()
        }
    }

    private fun rows(entities: List<SubscriptionEntity>): List<Group> {
        source.onNext(entities)
        clock.advanceTimeBy(1, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        return (model.stateLiveData.value as SubscriptionViewModel.SubscriptionState.LoadedState).subscriptions
    }

    private fun entities(vararg ids: Int) = ids.mapIndexed { index, id ->
        SubscriptionEntity(uid = index + 1L, serviceId = id, url = "https://fixture.invalid/$index", name = "channel-$index")
    }

    private fun view(locale: Locale = Locale.US): NewPipeTextView {
        val context = RuntimeEnvironment.getApplication()
        val resources = spy(context.resources)
        doAnswer { call ->
            val id = call.getArgument<Int>(1)
            if (locale.language == "ar") "resource-ar [${String.format(locale, "%d", id)}]" else "Unavailable service (ID $id)"
        }.`when`(resources).getString(anyInt(), anyInt())
        return spy(NewPipeTextView(context)).also { doReturn(resources).`when`(it).resources }
    }

    private fun bind(header: Header, view: NewPipeTextView = view()): NewPipeTextView {
        header.bind(SubscriptionHeaderBinding.bind(view), 0)
        return view
    }

    @Test
    fun `supported services keep their registered names and order`() {
        val ids = ServiceList.all().map { it.serviceId }.reversed().toIntArray()
        val headers = rows(entities(*ids)).filterIsInstance<Header>()
        assertEquals(ServiceList.all().map { it.serviceInfo.name }, headers.map { bind(it).text.toString() })
    }

    @Test
    fun `retired ids have distinct resource labels`() {
        val headers = rows(entities(4, 2, 3)).filterIsInstance<Header>()
        assertEquals(listOf("Unavailable service (ID 2)", "Unavailable service (ID 3)", "Unavailable service (ID 4)"), headers.map { bind(it).text.toString() })
    }

    @Test
    fun `arbitrary unavailable ids are distinguishable without claiming a retired service name`() {
        val headers = rows(entities(Int.MAX_VALUE, 99, -1)).filterIsInstance<Header>()
        assertEquals(listOf("Unavailable service (ID -1)", "Unavailable service (ID 99)", "Unavailable service (ID 2147483647)"), headers.map { bind(it).text.toString() })
    }

    @Test
    fun `grouping retains all channel ids and original entity data`() {
        val data = entities(8, 2, 5, 3, 1, 2, 99, 4, 0)
        val snapshot = data.map { it.copy() }
        val grouped = rows(data)
        assertEquals(8, grouped.filterIsInstance<Header>().size)
        assertEquals(listOf(9L, 5L, 2L, 6L, 4L, 8L, 3L, 1L, 7L), grouped.filterIsInstance<ChannelItem>().map { it.id })
        assertEquals(snapshot, data)
        assertTrue(grouped.first() is Header)
    }

    @Test
    fun `removing a final channel removes only that section`() {
        val data = entities(2, 3, 3)
        assertEquals(2, rows(data).filterIsInstance<Header>().size)
        val remaining = rows(data.drop(1))
        assertEquals(1, remaining.filterIsInstance<Header>().size)
        assertEquals(listOf(2L, 3L), remaining.filterIsInstance<ChannelItem>().map { it.id })
    }

    @Test
    fun `empty subscriptions have no headers`() {
        assertTrue(rows(emptyList()).isEmpty())
    }

    @Test
    fun `generic subscriptions section keeps its supplied title`() {
        val root = bind(Header("Subscriptions"))
        assertEquals("Subscriptions", root.text.toString())
        assertTrue(ViewCompat.isAccessibilityHeading(root))
    }

    @Test
    fun `bound headers advertise heading semantics on api26`() {
        val header = rows(entities(0)).filterIsInstance<Header>().single()
        val root = bind(header)
        assertTrue(ViewCompat.isAccessibilityHeading(root))
        assertTrue(AccessibilityNodeInfoCompat.wrap(root.createAccessibilityNodeInfo()).isHeading)
        assertFalse(root.isClickable)
    }

    @Test
    fun `unavailable label uses the bound view resource locale`() {
        val header = rows(entities(2)).filterIsInstance<Header>().single()
        val root = bind(header, view(Locale.forLanguageTag("ar-EG")))
        assertEquals("resource-ar [٢]", root.text.toString())
    }

    @Test
    fun `rebinding the same header resolves resources again`() {
        val header = rows(entities(3)).filterIsInstance<Header>().single()
        assertEquals("Unavailable service (ID 3)", bind(header).text.toString())
        assertEquals("resource-ar [٣]", bind(header, view(Locale.forLanguageTag("ar-EG"))).text.toString())
    }

    @Test
    fun `recycled header view gets the current label and remains a heading`() {
        val headers = rows(entities(0, 2, 99)).filterIsInstance<Header>()
        val root = bind(headers[1])
        assertEquals("Unavailable service (ID 2)", root.text.toString())
        bind(headers[0], root)
        assertEquals("YouTube", root.text.toString())
        bind(headers[2], root)
        assertEquals("Unavailable service (ID 99)", root.text.toString())
        assertTrue(ViewCompat.isAccessibilityHeading(root))
    }
}
