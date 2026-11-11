/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.GroupieAdapter
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.databinding.ListStreamItemBinding
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.views.AnimatedProgressBar
import org.schabi.newpipe.views.NewPipeTextView

/** Real fragment, views and adapter notifications; only theme drawable lookup is stubbed. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class FeedHighlightOrderingTest {
    private lateinit var fragment: FeedFragment
    private lateinit var adapter: GroupieAdapter
    private lateinit var theme: MockedStatic<ThemeHelper>
    private val bindings = mutableListOf<ListStreamItemBinding>()
    private val changedRanges = mutableListOf<Pair<Int, Int>>()
    private val cutoff = OffsetDateTime.parse("2026-11-01T12:00:00Z")

    @Before
    fun setup() {
        theme = mockStatic(ThemeHelper::class.java)
        theme.`when`<android.graphics.drawable.Drawable> { ThemeHelper.resolveDrawable(any(Context::class.java), anyInt()) }
            .thenAnswer { ColorDrawable(Color.TRANSPARENT) }
        fragment = FeedFragment()
        adapter = GroupieAdapter()
        FeedFragment::class.java.getDeclaredField("groupAdapter").apply { isAccessible = true }.set(fragment, adapter)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                changedRanges.add(positionStart to itemCount)
                for (position in positionStart until positionStart + itemCount) {
                    (adapter.getItem(position) as StreamItem).execBindEnd!!.accept(bindings[position])
                }
            }
        })
    }

    @After
    fun cleanup() = theme.close()

    @Test
    fun oldLiveStreamDoesNotSuppressNewUploadsOrUndatedItemsBelowIt() {
        populate(
            item(1, StreamType.LIVE_STREAM, cutoff.minusDays(1)),
            item(2, StreamType.VIDEO_STREAM, null),
            item(3, StreamType.VIDEO_STREAM, cutoff.plusMinutes(1)),
            item(4, StreamType.VIDEO_STREAM, cutoff)
        )

        highlight(cutoff)

        assertEquals(listOf(0 to 4), changedRanges)
        assertSame(Typeface.DEFAULT, bindings[0].itemVideoTitleView.typeface)
        assertSame(Typeface.DEFAULT_BOLD, bindings[1].itemVideoTitleView.typeface)
        assertSame(Typeface.DEFAULT_BOLD, bindings[2].itemVideoTitleView.typeface)
        assertTrue(bindings[2].itemRoot.background is LayerDrawable)
        assertSame(Typeface.DEFAULT, bindings[3].itemVideoTitleView.typeface)
    }

    @Test
    fun refreshClearsOldHighlightsAndRebindsNewOnesOutsideTheLeadingRange() {
        val live = item(1, StreamType.LIVE_STREAM, cutoff.plusMinutes(1))
        val upload = item(2, StreamType.VIDEO_STREAM, cutoff.minusDays(1))
        populate(live, upload)
        highlight(cutoff)
        assertSame(Typeface.DEFAULT_BOLD, bindings[0].itemVideoTitleView.typeface)
        assertSame(Typeface.DEFAULT, bindings[1].itemVideoTitleView.typeface)

        live.streamWithState.stream.uploadDate = cutoff.minusDays(1)
        upload.streamWithState.stream.uploadDate = cutoff.plusMinutes(1)
        highlight(cutoff)

        assertSame(Typeface.DEFAULT, bindings[0].itemVideoTitleView.typeface)
        assertFalse(bindings[0].itemRoot.background is LayerDrawable)
        assertSame(Typeface.DEFAULT_BOLD, bindings[1].itemVideoTitleView.typeface)

        highlight(cutoff.plusDays(1))

        assertSame(Typeface.DEFAULT, bindings[1].itemVideoTitleView.typeface)
        assertFalse(bindings[1].itemRoot.background is LayerDrawable)
    }

    @Test
    fun shrinkingOrEmptyFeedClearsHighlightsWithoutInvalidNotificationRanges() {
        populate(
            item(1, StreamType.LIVE_STREAM, cutoff.plusMinutes(1)),
            item(2, StreamType.VIDEO_STREAM, cutoff.plusMinutes(1))
        )
        highlight(cutoff)
        adapter.remove(adapter.getItem(1))
        highlight(cutoff.plusDays(1))
        assertSame(Typeface.DEFAULT, bindings[0].itemVideoTitleView.typeface)

        adapter.clear()
        highlight(cutoff.plusDays(1))
    }

    private fun populate(vararg items: StreamItem) {
        val context = RuntimeEnvironment.getApplication()
        items.forEach { _ ->
            val root = ConstraintLayout(context).apply { id = R.id.itemRoot }
            listOf(R.id.itemAdditionalDetails, R.id.itemDurationView, R.id.itemUploaderView, R.id.itemVideoTitleView).forEach { viewId ->
                root.addView(NewPipeTextView(context).apply { id = viewId })
            }
            root.addView(ImageView(context).apply { id = R.id.itemThumbnailView })
            root.addView(AnimatedProgressBar(context).apply { id = R.id.itemProgressView })
            bindings.add(ListStreamItemBinding.bind(root))
        }
        adapter.addAll(items.toList())
    }

    private fun highlight(time: OffsetDateTime) {
        FeedFragment::class.java.getDeclaredMethod("highlightNewItemsAfter", OffsetDateTime::class.java)
            .apply { isAccessible = true }.invoke(fragment, time)
    }

    private fun item(id: Long, type: StreamType, date: OffsetDateTime?): StreamItem = StreamItem(
        StreamWithState(
            StreamEntity(
                uid = id,
                serviceId = 0,
                url = "https://example.invalid/$id",
                title = "fixture",
                streamType = type,
                duration = -1,
                uploader = "fixture",
                uploadDate = date
            ),
            null
        )
    )
}
