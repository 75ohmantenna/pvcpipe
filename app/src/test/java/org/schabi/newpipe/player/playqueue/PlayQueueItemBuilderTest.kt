/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.playqueue

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.Localization

/** A RecyclerView binds the same holder again, so each bind has to replace everything it sets. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class PlayQueueItemBuilderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val builder = PlayQueueItemBuilder(
        // These tests run without the app's resources, and labels do not matter to them.
        object : ContextWrapper(context) {
            override fun getResources(): Resources = Mockito.mock(Resources::class.java)
        }
    )
    private val holder = PlayQueueItemHolder(
        FrameLayout(context).apply {
            id = R.id.itemRoot
            addView(TextView(context).apply { id = R.id.itemVideoTitleView })
            addView(TextView(context).apply { id = R.id.itemDurationView })
            addView(TextView(context).apply { id = R.id.itemAdditionalDetails })
            addView(ImageView(context).apply { id = R.id.itemThumbnailView })
            addView(ImageView(context).apply { id = R.id.itemHandle })
        }
    )

    private fun item(title: String, duration: Long) = PlayQueueItem(
        StreamInfoItem(0, "https://example.com/$title", title, StreamType.VIDEO_STREAM).apply {
            this.duration = duration
        }
    )

    @Test
    fun `a row recycled from an item without duration shows the next duration`() {
        builder.buildStreamInfoItem(holder, item("live", 0))
        assertEquals(View.GONE, holder.itemDurationView.visibility)

        builder.buildStreamInfoItem(holder, item("video", 90))

        assertEquals(View.VISIBLE, holder.itemDurationView.visibility)
        assertEquals(
            Localization.getDurationString(90),
            holder.itemDurationView.text.toString()
        )
    }

    @Test
    fun `a row recycled for an item without title clears the old title`() {
        builder.buildStreamInfoItem(holder, item("video", 90))

        builder.buildStreamInfoItem(holder, item("", 90))

        assertEquals("", holder.itemVideoTitleView.text.toString())
    }
}
