/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.fragments.detail

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.R

/** Runs the view model against the real default preferences and real lifecycles. */
class DetailPagerViewModelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val key = application.getString(R.string.pvc_settings_scroll_only_below_player_key)
    private val stores = ArrayList<ViewModelStore>()

    private var hadSetting = false
    private var originalSetting = false

    @Before
    fun rememberSetting() {
        hadSetting = preferences.contains(key)
        originalSetting = preferences.getBoolean(key, false)
    }

    @After
    fun restoreSetting() {
        onMain { stores.forEach(ViewModelStore::clear) }
        val editor = preferences.edit()
        if (hadSetting) editor.putBoolean(key, originalSetting) else editor.remove(key)
        editor.commit()
        instrumentation.waitForIdleSync()
    }

    @Test
    fun startsWithTheStoredSetting() {
        setSetting(true)
        assertEquals(true, newViewModel().scrollOnlyBelowPlayer.value)

        setSetting(false)
        assertEquals(false, newViewModel().scrollOnlyBelowPlayer.value)
    }

    @Test
    fun followsTheSettingWhileAlive() {
        setSetting(false)
        val viewModel = newViewModel()
        val seen = ArrayList<Boolean>()
        onMain { viewModel.scrollOnlyBelowPlayer.observeForever { seen.add(it) } }

        setSetting(true)
        setSetting(false)

        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun stopsFollowingTheSettingOnceCleared() {
        setSetting(false)
        val store = ViewModelStore().also(stores::add)
        val viewModel = newViewModel(store)

        onMain { store.clear() }
        setSetting(true)

        assertEquals(false, viewModel.scrollOnlyBelowPlayer.value)
    }

    @Test
    fun publishesHeightOnlyWhileTheSettingIsOn() {
        setSetting(false)
        val viewModel = newViewModel()

        onMain { viewModel.publishPagerContentHeight(500) }
        assertNull(viewModel.pagerContentHeight.value)

        setSetting(true)
        onMain { viewModel.publishPagerContentHeight(600) }
        assertEquals(600, viewModel.pagerContentHeight.value)
    }

    @Test
    fun aViewThatStartsLaterReceivesTheLatestHeightAndNothingAfterItIsDestroyed() {
        setSetting(true)
        val viewModel = newViewModel()
        onMain { viewModel.publishPagerContentHeight(700) }

        val view = TestOwner()
        val heights = ArrayList<Int>()
        onMain {
            view.registry.currentState = Lifecycle.State.CREATED
            viewModel.pagerContentHeight.observe(view) { heights.add(it) }
        }
        assertEquals(emptyList<Int>(), heights)

        onMain { view.registry.currentState = Lifecycle.State.STARTED }
        assertEquals(listOf(700), heights)

        onMain { viewModel.publishPagerContentHeight(800) }
        assertEquals(listOf(700, 800), heights)

        onMain { view.registry.currentState = Lifecycle.State.DESTROYED }
        onMain { viewModel.publishPagerContentHeight(900) }
        assertEquals(listOf(700, 800), heights)
    }

    private fun setSetting(on: Boolean) {
        preferences.edit().putBoolean(key, on).commit()
        instrumentation.waitForIdleSync()
    }

    private fun newViewModel(store: ViewModelStore = ViewModelStore().also(stores::add)): DetailPagerViewModel = onMain {
        ViewModelProvider(
            store,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[DetailPagerViewModel::class.java]
    }

    private fun <T : Any> onMain(block: () -> T): T {
        lateinit var result: T
        instrumentation.runOnMainSync { result = block() }
        return result
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
