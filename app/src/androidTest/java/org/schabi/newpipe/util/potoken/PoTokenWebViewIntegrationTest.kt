/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util.potoken

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Single
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Response

/** Exercises the actual Android browser and JavaScript bridge without external network access. */
class PoTokenWebViewIntegrationTest {
    @Test
    fun realBrowserInitializesGeneratesTokensAndRejectsRequestsAfterClosing() {
        val environment = FixtureEnvironment()
        val observer = PoTokenWebView.newPoTokenGenerator(environment).test()
            .awaitDone(20, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        val generator = observer.values().single()
        try {
            assertFalse(generator.isExpired())
            generator.generatePoToken("abc").test().awaitDone(10, TimeUnit.SECONDS)
                .assertValue("YWJj").assertComplete().assertNoErrors()
            generator.generatePoToken("video").test().awaitDone(10, TimeUnit.SECONDS)
                .assertValue("dmlkZW8=").assertComplete().assertNoErrors()
            assertEquals(2, environment.requests.get())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                generator.close()
                generator.close()
            }
        }
        assertEquals(1, environment.closed.get())
        generator.generatePoToken("late").test().awaitDone(10, TimeUnit.SECONDS)
            .assertError(PoTokenException::class.java)
    }

    @Test
    fun rejectedBotguardResponseClosesRealBrowserAndAllowsAnotherInitialization() {
        val rejected = FixtureEnvironment(responseCode = 503)
        PoTokenWebView.newPoTokenGenerator(rejected).test().awaitDone(20, TimeUnit.SECONDS)
            .assertError(PoTokenException::class.java)
        assertTrue(rejected.closeFinished.await(10, TimeUnit.SECONDS))
        assertEquals(1, rejected.closed.get())

        val working = FixtureEnvironment()
        val observer = PoTokenWebView.newPoTokenGenerator(working).test()
            .awaitDone(20, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        val generator = observer.values().single()
        try {
            generator.generatePoToken("abc").test().awaitDone(10, TimeUnit.SECONDS)
                .assertValue("YWJj").assertNoErrors()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { generator.close() }
        }
        assertEquals(1, working.closed.get())
    }

    @Test
    fun canceledInitializationReleasesTheActualBrowserExactlyOnce() {
        val environment = FixtureEnvironment(pendingResponse = true)
        val observer = PoTokenWebView.newPoTokenGenerator(environment).test()
        try {
            assertTrue(environment.requestStarted.await(20, TimeUnit.SECONDS))
        } finally {
            observer.dispose()
        }
        assertTrue(environment.closeFinished.await(10, TimeUnit.SECONDS))
        assertEquals(1, environment.closed.get())
        observer.assertNoValues()
    }

    private class FixtureEnvironment(
        private val responseCode: Int = 200,
        private val pendingResponse: Boolean = false,
        private val android: AndroidPoTokenWebViewEnvironment = AndroidPoTokenWebViewEnvironment(
            ApplicationProvider.getApplicationContext<Context>()
        )
    ) : PoTokenWebViewEnvironment by android {
        val requests = AtomicInteger()
        val closed = AtomicInteger()
        val requestStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)

        override fun readHtml(): Single<String> = Single.just(
            """<html><script>
                function runBotGuard(data) {
                    return Promise.resolve({webPoSignalOutput: {}, botguardResponse: "fixture"});
                }
                function obtainPoToken(signal, integrity, identifier) { return identifier; }
            </script></html>"""
        )

        override fun postBotguard(url: String, data: String): Single<Response> {
            requests.incrementAndGet()
            requestStarted.countDown()
            if (pendingResponse) return Single.never()
            val body = if (url.endsWith("/Create")) {
                """[["message", ["script"], ["resource"], "hash", "program", "global", null, "experiments"]]"""
            } else {
                """["YWJj", 3600]"""
            }
            return Single.just(Response(responseCode, "fixture", emptyMap(), body, url))
        }

        override fun createBrowser(bridge: PoTokenWebView, onConsoleError: (Int) -> Unit): PoTokenBrowser {
            val browser = android.createBrowser(bridge, onConsoleError)
            return object : PoTokenBrowser by browser {
                override fun close() {
                    browser.close()
                    closed.incrementAndGet()
                    closeFinished.countDown()
                }
            }
        }
    }
}
