/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.bookmark

import android.util.Pair
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Scheduler
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.LocalItem.LocalItemType
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.local.playlist.LocalPlaylistManager

/** Frozen bookmark mutations share the accepted playlist write queue across fragment instances. */
open class BookmarkSaveManager internal constructor(
    private val database: AppDatabase,
    private val writes: Scheduler
) {
    constructor(database: AppDatabase) : this(database, LocalPlaylistManager.WRITES)

    private data class Key(val id: Long, val type: LocalItemType)

    @JvmSuppressWildcards
    open fun save(items: List<PlaylistLocalItem>, deleted: List<Pair<Long, LocalItemType>>): Completable {
        val order = items.map { Key(it.uid, it.localItemType) }
        val removals = deleted.map { Key(it.first, it.second) }
        require(
            (order + removals).all {
                it.type == LocalItemType.PLAYLIST_LOCAL_ITEM || it.type == LocalItemType.PLAYLIST_REMOTE_ITEM
            }
        )
        return Completable.fromRunnable {
            database.runInTransaction {
                for (item in removals) {
                    if (item.type == LocalItemType.PLAYLIST_LOCAL_ITEM) {
                        database.playlistDAO().deletePlaylist(item.id)
                    } else {
                        database.playlistRemoteDAO().deletePlaylist(item.id)
                    }
                }
                // Update only order: stale UI metadata must not overwrite a rename/thumbnail,
                // and an item deleted by an earlier request must not be recreated by upsert.
                order.forEachIndexed { index, item ->
                    if (item.type == LocalItemType.PLAYLIST_LOCAL_ITEM) {
                        database.playlistDAO().updateDisplayIndex(item.id, index.toLong())
                    } else {
                        database.playlistRemoteDAO().updateDisplayIndex(item.id, index.toLong())
                    }
                }
            }
        }.subscribeOn(writes).cache()
    }
}
