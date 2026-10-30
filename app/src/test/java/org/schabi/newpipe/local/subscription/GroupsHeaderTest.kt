/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription

import android.app.Application
import android.widget.ImageButton
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.SubscriptionGroupsHeaderBinding
import org.schabi.newpipe.local.subscription.item.GroupsHeader
import org.schabi.newpipe.views.NewPipeTextView

/** Real binding and clicks with controlled label resources and drawable loading. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class GroupsHeaderTest {
    private fun binding(): SubscriptionGroupsHeaderBinding {
        val context = spy(RuntimeEnvironment.getApplication())
        val resources = spy(context.resources)
        doReturn("Show channel groups as grid").`when`(resources).getString(R.string.show_channel_groups_as_grid)
        doReturn("Show channel groups as list").`when`(resources).getString(R.string.show_channel_groups_as_list)
        doReturn(resources).`when`(context).resources
        val root = LinearLayout(context)
        root.addView(NewPipeTextView(context).apply { id = R.id.header_title })
        root.addView(ImageButton(context).apply { id = R.id.header_sort })
        root.addView(
            spy(ImageButton(context)).apply {
                id = R.id.header_toggle_view_mode
                doNothing().`when`(this).setImageResource(anyInt())
            }
        )
        return SubscriptionGroupsHeaderBinding.bind(root)
    }

    @Test
    fun `full binding describes the next action in either mode`() {
        for (listMode in listOf(true, false)) {
            val binding = binding()
            GroupsHeader("Groups", {}, {}, listViewMode = listMode).bind(binding, 0)
            assertEquals(if (listMode) "Show channel groups as grid" else "Show channel groups as list", binding.headerToggleViewMode.contentDescription)
            verify(binding.headerToggleViewMode).setImageResource(if (listMode) R.drawable.ic_apps else R.drawable.ic_list)
        }
    }

    @Test
    fun `icon payload refreshes the label in both directions`() {
        val binding = binding()
        val header = GroupsHeader("Groups", {}, {})
        header.bind(binding, 0)
        header.listViewMode = false
        header.bind(binding, 0, mutableListOf(GroupsHeader.PAYLOAD_UPDATE_ICONS))
        assertEquals("Show channel groups as list", binding.headerToggleViewMode.contentDescription)
        header.listViewMode = true
        header.bind(binding, 0, mutableListOf(GroupsHeader.PAYLOAD_UPDATE_ICONS))
        assertEquals("Show channel groups as grid", binding.headerToggleViewMode.contentDescription)
    }

    @Test
    fun `recycled binding replaces the label and callback`() {
        val binding = binding()
        var firstClicks = 0
        var secondClicks = 0
        GroupsHeader("First", {}, { firstClicks++ }).bind(binding, 0)
        binding.headerToggleViewMode.performClick()
        GroupsHeader("Second", {}, { secondClicks++ }, listViewMode = false).bind(binding, 0, mutableListOf())
        assertEquals("Show channel groups as list", binding.headerToggleViewMode.contentDescription)
        binding.headerToggleViewMode.performClick()
        assertEquals(1, firstClicks)
        assertEquals(1, secondClicks)
    }

    @Test
    fun `payload keeps the toggle callback separate from sorting`() {
        val binding = binding()
        var sorts = 0
        lateinit var header: GroupsHeader
        header = GroupsHeader("Groups", { sorts++ }, {
            header.listViewMode = !header.listViewMode
            header.bind(binding, 0, mutableListOf(GroupsHeader.PAYLOAD_UPDATE_ICONS))
        })
        header.bind(binding, 0)
        assertTrue(binding.headerToggleViewMode.performClick())
        assertEquals("Show channel groups as list", binding.headerToggleViewMode.contentDescription)
        assertEquals(0, sorts)
        binding.headerSort.performClick()
        assertEquals(1, sorts)
        binding.headerToggleViewMode.performClick()
        assertEquals("Show channel groups as grid", binding.headerToggleViewMode.contentDescription)
        assertEquals(1, sorts)
    }
}
