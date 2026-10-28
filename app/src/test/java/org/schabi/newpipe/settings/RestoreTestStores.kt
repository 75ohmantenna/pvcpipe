/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import org.schabi.newpipe.settings.export.RestoreTransaction

/** Codec-only fixtures have no preferences or native SQLite engine. */
internal fun emptyRestorePreferences() = object : RestoreTransaction.Preferences {
    override fun read(): Map<String, Any> = emptyMap()
    override fun replace(values: Map<String, Any>) = Unit
}
