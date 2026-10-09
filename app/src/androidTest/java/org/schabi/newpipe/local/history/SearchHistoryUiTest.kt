package org.schabi.newpipe.local.history

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.R as MaterialR
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.fragments.list.search.SearchFragment
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.util.NavigationHelper

@RunWith(AndroidJUnit4::class)
class SearchHistoryUiTest {
    private lateinit var database: AppDatabase
    private lateinit var databaseField: Field
    private var previousDatabase: Any? = null
    private lateinit var preferences: SharedPreferences
    private lateinit var searchHistoryKey: String
    private lateinit var disableErrorsKey: String
    private var previousSearchHistory: Boolean? = null
    private var previousDisableErrors: Boolean? = null
    private var activity: MainActivity? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        searchHistoryKey = context.getString(R.string.enable_search_history_key)
        disableErrorsKey = context.getString(R.string.disable_error_reports_key)
        previousSearchHistory = if (preferences.contains(searchHistoryKey)) {
            preferences.getBoolean(searchHistoryKey, false)
        } else {
            null
        }
        previousDisableErrors = if (preferences.contains(disableErrorsKey)) {
            preferences.getBoolean(disableErrorsKey, false)
        } else {
            null
        }
        check(
            preferences.edit().putBoolean(searchHistoryKey, true)
                .putBoolean(disableErrorsKey, false).commit()
        )

        databaseField = NewPipeDatabase::class.java.getDeclaredField("databaseInstance")
        databaseField.isAccessible = true
        previousDatabase = databaseField.get(null)
        database = TestDatabase.createReplacingNewPipeDatabase()
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
        } finally {
            RxJavaPlugins.reset()
        }
        databaseField.set(null, previousDatabase)
        database.close()

        val editor = preferences.edit()
        previousSearchHistory?.let { editor.putBoolean(searchHistoryKey, it) }
            ?: editor.remove(searchHistoryKey)
        previousDisableErrors?.let { editor.putBoolean(disableErrorsKey, it) }
            ?: editor.remove(disableErrorsKey)
        check(editor.commit())
    }

    private fun startActivity(): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        activity = started
        instrumentation.waitForIdleSync()
        return started
    }

    private fun openSearch(activity: MainActivity): SearchFragment {
        NavigationHelper.openSearchFragment(activity.supportFragmentManager, 0, "")
        activity.supportFragmentManager.executePendingTransactions()
        return activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as SearchFragment
    }

    private fun search(fragment: SearchFragment, query: String) {
        val method = SearchFragment::class.java.getDeclaredMethod("search", String::class.java)
        method.isAccessible = true
        method.invoke(fragment, query)
    }

    @Test
    fun writeFailureShowsSnackbarWhileSearchViewIsAttached() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val extractorIo = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { extractorIo }
        val activity = startActivity()
        val historyIo = TestScheduler()
        instrumentation.runOnMainSync {
            val fragment = openSearch(activity)
            val manager = object : HistoryRecordManager(activity) {
                override fun onSearched(serviceId: Int, search: String): Maybe<Long> {
                    return Maybe.error<Long>(IllegalStateException("history failure: $search"))
                        .subscribeOn(historyIo)
                }
            }
            val field = SearchFragment::class.java.getDeclaredField("historyRecordManager")
            field.isAccessible = true
            field.set(fragment, manager)
            search(fragment, "pvcpipe_attached_failure")
        }
        instrumentation.waitForIdleSync()
        assertNull(activity.findViewById<TextView>(MaterialR.id.snackbar_text))

        historyIo.triggerActions()
        instrumentation.waitForIdleSync()
        val snackbar = activity.findViewById<TextView>(MaterialR.id.snackbar_text)
        assertNotNull("Search-history write failure should be visible", snackbar)
        assertEquals(activity.getString(R.string.error_snackbar_message), snackbar.text.toString())
    }

    @Test
    fun acceptedWriteFinishesAfterSearchFragmentIsDestroyed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val extractorIo = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { extractorIo }
        val io = TestScheduler()
        RxJavaPlugins.setSingleSchedulerHandler { io }
        val activity = startActivity()
        val query = "pvcpipe_queued_destroy_regression"
        instrumentation.runOnMainSync {
            val fragment = openSearch(activity)
            search(fragment, query)
            activity.supportFragmentManager.popBackStackImmediate()
            assertFalse(fragment.isAdded)
            // A queued preference notification may still target this removed fragment.
            fragment.onSharedPreferenceChanged(preferences, disableErrorsKey)
        }
        assertFalse(database.searchHistoryDAO().getAll().blockingFirst().any { it.search == query })

        io.triggerActions()
        instrumentation.waitForIdleSync()
        assertTrue(database.searchHistoryDAO().getAll().blockingFirst().any { it.search == query })
    }
}
