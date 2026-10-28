/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.bookmark

import android.app.Application
import android.content.Context
import android.util.Pair
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.CompletableSubject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.reactivestreams.Subscriber
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.local.LocalItemListAdapter
import org.schabi.newpipe.util.debounce.DebounceSaver

/** Actual saveImmediate with deferred persistence; adapters/managers are controlled doubles. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class BookmarkSaveOwnershipTest {
    private lateinit var errorConstruction: MockedConstruction<ErrorInfo>
    private lateinit var fragment: BookmarkFragment
    private lateinit var saver: DebounceSaver
    private lateinit var writer: BookmarkSaveManager
    private lateinit var errors: MockedStatic<ErrorUtil>
    private lateinit var localWrite: CompletableSubject
    private lateinit var remoteWrite: CompletableSubject
    private lateinit var items: ArrayList<LocalItem>
    private lateinit var deleted: MutableList<Pair<Long, LocalItem.LocalItemType>>

    @Before
    fun setup() {
        errorConstruction = Mockito.mockConstruction(ErrorInfo::class.java)
        errors = Mockito.mockStatic(ErrorUtil::class.java)
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        fragment = Mockito.spy(BookmarkFragment())
        doNothing().`when`(fragment).showError(any(ErrorInfo::class.java))
        doNothing().`when`(fragment).showSnackBarError(any(ErrorInfo::class.java))
        Mockito.doReturn(RuntimeEnvironment.getApplication()).`when`(fragment).context
        saver = DebounceSaver(3000, fragment)
        writer = mock(BookmarkSaveManager::class.java)
        localWrite = CompletableSubject.create()
        remoteWrite = CompletableSubject.create()
        `when`(writer.save(anyList(), anyList())).thenReturn(Completable.mergeArray(localWrite, remoteWrite))
        items = arrayListOf(entry(1, 0), entry(2, 1))
        deleted = ArrayList()
        val adapter = mock(LocalItemListAdapter::class.java)
        `when`(adapter.itemsList).thenReturn(items)
        field("itemListAdapter", adapter)
        field("debounceSaver", saver)
        field("bookmarkSaveManager", writer)
        field("isLoadingComplete", AtomicBoolean(true))
        field("disposables", CompositeDisposable())
        field("deletedItems", deleted)
    }

    @After
    fun cleanup() {
        try {
            if (::errors.isInitialized) errors.close()
        } finally {
            if (::errorConstruction.isInitialized) errorConstruction.close()
            RxAndroidPlugins.reset()
        }
    }

    private fun entry(id: Long, index: Long) = PlaylistMetadataEntry(id, "playlist-$id", null, index, false, -1, 0)

    private fun field(name: String, value: Any) {
        var owner: Class<*>? = BookmarkFragment::class.java
        while (owner != null) {
            try {
                owner.getDeclaredField(name).also { it.isAccessible = true }.set(fragment, value)
                return
            } catch (_: NoSuchFieldException) {
                owner = owner.superclass
            }
        }
        error("Missing field $name")
    }

    @Suppress("UNCHECKED_CAST")
    private fun subscriber(): Subscriber<List<PlaylistLocalItem>> = BookmarkFragment::class.java
        .getDeclaredMethod("getPlaylistsSubscriber").also { it.isAccessible = true }.invoke(fragment) as Subscriber<List<PlaylistLocalItem>>

    private fun finish() {
        localWrite.onComplete()
        remoteWrite.onComplete()
    }

    private fun recreatedView(): ArrayList<LocalItem> {
        fragment.onDestroyView()
        val newItems = arrayListOf<LocalItem>()
        val adapter = mock(LocalItemListAdapter::class.java)
        `when`(adapter.itemsList).thenReturn(newItems)
        field("itemListAdapter", adapter)
        Mockito.doAnswer { call ->
            newItems.clear()
            newItems.addAll(call.getArgument<List<PlaylistLocalItem>>(0))
            null
        }.`when`(fragment).handleResult(anyList())
        return newItems
    }

    @Test
    fun `older completion keeps a newer draft dirty`() {
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        items.reverse()
        saver.setHasChangesToSave()
        localWrite.onComplete()
        remoteWrite.onComplete()
        assertTrue("A later edit still requires persistence", saver.isModified)
    }

    @Test
    fun `failure retains deletion for retry`() {
        val deletion = Pair(3L, LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM)
        deleted.add(deletion)
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        localWrite.onError(IOException("injected write failure"))
        assertTrue(saver.isModified)
        assertEquals(listOf(deletion), deleted)
    }

    @Test
    fun `saving does not mark adapter indices persisted before success`() {
        items.reverse()
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        assertEquals(1L, (items[0] as PlaylistMetadataEntry).displayIndex)
        assertEquals(0L, (items[1] as PlaylistMetadataEntry).displayIndex)
        assertTrue(saver.isModified)
        assertFalse(localWrite.hasComplete())
    }

    @Test
    fun `old acknowledgment removes only its deletion batch`() {
        val first = Pair(3L, LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM)
        val later = Pair(3L, LocalItem.LocalItemType.PLAYLIST_REMOTE_ITEM)
        deleted.add(first)
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        deleted.add(later)
        saver.setHasChangesToSave()
        finish()
        assertEquals(listOf(later), deleted)
        assertTrue(saver.isModified)
    }

    @Test
    fun `latest completion clears its revision after older save completes`() {
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        items.reverse()
        saver.setHasChangesToSave()
        val newer = CompletableSubject.create()
        `when`(writer.save(anyList(), anyList())).thenReturn(newer)
        fragment.saveImmediate()
        finish()
        assertTrue(saver.isModified)
        newer.onComplete()
        assertFalse(saver.isModified)
        assertEquals(listOf(2L, 1L), items.map { (it as PlaylistLocalItem).uid })
    }

    @Test
    fun `failure reports without clearing draft and retries all pending changes`() {
        val deletion = Pair(3L, LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM)
        deleted.add(deletion)
        items.reverse()
        Mockito.doReturn(true).`when`(fragment).isAdded
        Mockito.doReturn(mock(View::class.java)).`when`(fragment).view
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        localWrite.onError(IOException("controlled failure"))
        verify(fragment).showSnackBarError(any(ErrorInfo::class.java))
        verify(fragment, never()).showError(any(ErrorInfo::class.java))
        val retry = CompletableSubject.create()
        `when`(writer.save(anyList(), anyList())).thenReturn(retry)
        fragment.saveImmediate()
        val calls = Mockito.mockingDetails(writer).invocations.filter { it.method.name == "save" }
        assertEquals(2, calls.size)
        @Suppress("UNCHECKED_CAST")
        val retryOrder = calls.last().arguments[0] as List<PlaylistLocalItem>
        assertEquals(listOf(2L, 1L), retryOrder.map { it.uid })
        assertEquals(listOf(deletion), calls.last().arguments[1])
        assertTrue(saver.isModified)
        retry.onComplete()
        assertFalse(saver.isModified)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `accepted save and acknowledgment survive view disposal`() {
        deleted.add(Pair(3L, LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM))
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        fragment.onDestroyView()
        assertTrue(localWrite.hasObservers())
        assertTrue(remoteWrite.hasObservers())
        finish()
        assertFalse(saver.isModified)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `recreated view uses pending draft instead of older database emission`() {
        items.reverse()
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        val recreated = recreatedView()
        subscriber().onNext(listOf(entry(1, 0), entry(2, 1)))
        assertEquals(listOf(2L, 1L), recreated.map { (it as PlaylistLocalItem).uid })
        assertTrue(saver.isModified)
        finish()
        assertFalse(saver.isModified)
    }

    @Test
    fun `view destruction retains edits made after the accepted snapshot`() {
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        items.reverse()
        saver.setHasChangesToSave()
        val recreated = recreatedView()
        finish()
        subscriber().onNext(listOf(entry(1, 0), entry(2, 1)))
        assertEquals(listOf(2L, 1L), recreated.map { (it as PlaylistLocalItem).uid })
        assertTrue(saver.isModified)
    }

    @Test
    fun `destroying an unloaded replacement view does not erase the retained draft`() {
        items.reverse()
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        recreatedView()
        val nextView = recreatedView()
        subscriber().onNext(listOf(entry(1, 0), entry(2, 1)))
        assertEquals(listOf(2L, 1L), nextView.map { (it as PlaylistLocalItem).uid })
        assertTrue(saver.isModified)
    }

    @Test
    fun `failed accepted write reports after full fragment destruction`() {
        field("activity", Robolectric.buildActivity(AppCompatActivity::class.java).get())
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        fragment.onDestroyView()
        fragment.onDestroy()
        val failure = IOException("controlled detached failure")
        localWrite.onError(failure)
        // Java production calls the static bridge; a Kotlin call uses the unmocked companion.
        val report = ErrorUtil::class.java.getMethod(
            "reportDetached",
            Context::class.java,
            Throwable::class.java,
            UserAction::class.java,
            String::class.java
        )
        val appContext = RuntimeEnvironment.getApplication()
        errors.verify {
            report.invoke(
                null,
                appContext,
                failure,
                UserAction.REQUESTED_BOOKMARK,
                "Saving playlist"
            )
        }
        verify(fragment, never()).showError(any(ErrorInfo::class.java))
        verify(fragment, never()).showSnackBarError(any(ErrorInfo::class.java))
    }

    @Test
    fun `failed draft survives view recreation and can be retried`() {
        items.reverse()
        val deletion = Pair(3L, LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM)
        deleted.add(deletion)
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        localWrite.onError(IOException("controlled failure"))
        val recreated = recreatedView()
        subscriber().onNext(listOf(entry(1, 0), entry(2, 1)))
        assertEquals(listOf(2L, 1L), recreated.map { (it as PlaylistLocalItem).uid })
        assertEquals(listOf(deletion), deleted)
        val retry = CompletableSubject.create()
        `when`(writer.save(anyList(), anyList())).thenReturn(retry)
        fragment.saveImmediate()
        retry.onComplete()
        assertFalse(saver.isModified)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `old saver completion cannot acknowledge a replacement owner at equal revision`() {
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        val replacement = DebounceSaver(3000, fragment)
        replacement.setHasChangesToSave()
        val newDeleted = arrayListOf(Pair(4L, LocalItem.LocalItemType.PLAYLIST_REMOTE_ITEM))
        field("debounceSaver", replacement)
        field("deletedItems", newDeleted)
        finish()
        assertTrue(replacement.isModified)
        assertEquals(listOf(Pair(4L, LocalItem.LocalItemType.PLAYLIST_REMOTE_ITEM)), newDeleted)
    }

    @Test
    fun `dirty refresh retries without discarding visible draft`() {
        items.reverse()
        saver.setHasChangesToSave()
        fragment.startLoading(true)
        verify(writer).save(anyList(), anyList())
        assertEquals(listOf(2L, 1L), items.map { (it as PlaylistLocalItem).uid })
        assertTrue(saver.isModified)
    }

    @Test
    fun `database emission cannot replace a newer unsaved edit`() {
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        items.reverse()
        saver.setHasChangesToSave()
        subscriber().onNext(listOf(entry(1, 0), entry(2, 1)))
        verify(fragment, never()).handleResult(anyList())
        assertEquals(listOf(2L, 1L), items.map { (it as PlaylistLocalItem).uid })
        finish()
        assertTrue(saver.isModified)
    }

    @Test
    fun `database read error does not discard an active draft`() {
        items.reverse()
        saver.setHasChangesToSave()
        fragment.saveImmediate()
        subscriber().onError(IOException("controlled read failure"))
        verify(fragment).showSnackBarError(any(ErrorInfo::class.java))
        verify(fragment, never()).showError(any(ErrorInfo::class.java))
        assertEquals(listOf(2L, 1L), items.map { (it as PlaylistLocalItem).uid })
        assertTrue(saver.isModified)
    }
}
