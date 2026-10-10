/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.ktx

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.observers.TestObserver
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises [clicks] and [textChanges] on real views, on and off the main thread. */
class ViewObservablesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun clicksInstallsListenerEmitsEachClickAndClearsListenerOnDispose() {
        val button = onMain { Button(context) }
        val observer = onMain { button.clicks().test() }
        assertTrue(onMain { button.hasOnClickListeners() })

        onMain { button.performClick() }
        onMain { button.performClick() }
        observer.assertValues(Unit, Unit).assertNotComplete().assertNoErrors()

        onMain { observer.dispose() }
        assertFalse(onMain { button.hasOnClickListeners() })
        onMain { button.performClick() }
        observer.assertValueCount(2)
    }

    @Test
    fun clicksSubscribedOffTheMainThreadFailsWithoutInstallingListener() {
        val button = onMain { Button(context) }

        val observer = button.clicks().test()

        observer.assertError(IllegalStateException::class.java).assertNoValues()
        assertFalse(onMain { button.hasOnClickListeners() })
    }

    @Test
    fun clicksDisposedOffTheMainThreadClearsListenerOnTheMainThread() {
        val button = onMain { Button(context) }
        val observer = onMain { button.clicks().test() }

        observer.dispose()
        instrumentation.waitForIdleSync()

        assertFalse(onMain { button.hasOnClickListeners() })
    }

    @Test
    fun textChangesEmitsCurrentTextThenEachChangeAndRemovesWatcherOnDispose() {
        val field = onMain { WatcherCountingEditText(context).apply { setText("abc") } }
        val texts = ArrayList<String>()
        val observer = onMain { field.textChanges().map { it.toString() }.doOnNext(texts::add).test() }
        assertEquals(listOf("abc"), texts)
        assertEquals(1, field.watchersAdded)

        onMain { field.setText("abcd") }
        onMain { field.append("e") }
        assertEquals(listOf("abc", "abcd", "abcde"), texts)

        onMain { observer.dispose() }
        assertEquals(1, field.watchersRemoved)
        onMain { field.setText("ignored") }
        assertEquals(listOf("abc", "abcd", "abcde"), texts)
    }

    @Test
    fun textChangesDisposedOffTheMainThreadRemovesWatcherOnTheMainThread() {
        val field = onMain { WatcherCountingEditText(context) }
        val observer = onMain { field.textChanges().test() }

        observer.dispose()
        instrumentation.waitForIdleSync()

        assertEquals(1, field.watchersRemoved)
        assertTrue(field.removedOnMainThread)
    }

    @Test
    fun debouncedTextChangesDeliversOnlyTheLastTextOfABurst() {
        val field = onMain { EditText(context) }
        val observer = TestObserver<String>()
        onMain {
            field.textChanges()
                .skip(1)
                .debounce(200, TimeUnit.MILLISECONDS)
                .map { it.toString() }
                .subscribe(observer)
        }

        onMain { field.setText("p") }
        onMain { field.setText("pl") }
        onMain { field.setText("pla") }

        observer.awaitCount(1)
        observer.assertValue("pla")
        onMain { observer.dispose() }
    }

    private fun <T : Any> onMain(block: () -> T): T {
        lateinit var result: T
        instrumentation.runOnMainSync { result = block() }
        return result
    }

    @SuppressLint("AppCompatCustomView")
    private class WatcherCountingEditText(context: Context) : EditText(context) {
        @Volatile
        var watchersAdded = 0

        @Volatile
        var watchersRemoved = 0

        @Volatile
        var removedOnMainThread = false

        override fun addTextChangedListener(watcher: TextWatcher) {
            watchersAdded++
            super.addTextChangedListener(watcher)
        }

        override fun removeTextChangedListener(watcher: TextWatcher) {
            watchersRemoved++
            removedOnMainThread = Looper.myLooper() == Looper.getMainLooper()
            super.removeTextChangedListener(watcher)
        }
    }
}
