/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedGroupIconTest {
    @Test
    fun `screen readers can tell every icon apart`() {
        val icons = FeedGroupIcon.entries

        assertEquals(icons.size, icons.map { it.nameResource }.toSet().size)
    }
}
