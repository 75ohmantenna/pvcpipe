package org.schabi.newpipe.util.potoken

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.SingleSubject
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebPoTokenProviderCorrectionTest {
    @Test
    fun retiredGeneratorStaysAliveUntilAllActiveRequestsFinish() {
        val old = ScriptedPoTokenGenerator("old")
        val replacement = ScriptedPoTokenGenerator("replacement")
        val first = SingleSubject.create<String>()
        val second = SingleSubject.create<String>()
        val subscribed = CountDownLatch(2)
        old.generate = { identifier ->
            when (identifier) {
                "first" -> first.doOnSubscribe { subscribed.countDown() }
                "second" -> second.doOnSubscribe { subscribed.countDown() }
                else -> Single.just("old:$identifier")
            }
        }
        old.onClose = {
            if (first.hasObservers()) {
                first.onError(PoTokenException("generator closed during first request"))
            }
            if (second.hasObservers()) {
                second.onError(PoTokenException("generator closed during second request"))
            }
        }
        val environment = ScriptedPoTokenEnvironment(old, replacement)
        val provider = WebPoTokenProvider(environment)
        provider.getWebClientPoToken("prime")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstResult = executor.submit(Callable { provider.getWebClientPoToken("first") })
            val secondResult = executor.submit(Callable { provider.getWebClientPoToken("second") })
            await(subscribed)
            old.expired = true
            assertResult(provider.getWebClientPoToken("next"), "visitor-2", "replacement:next", "replacement:visitor-2")

            assertEquals("Active requests still own the old generator", 0, old.closes.get())
            assertTrue(environment.retired.isEmpty())
            first.onSuccess("first-player")
            assertResult(firstResult.get(5, TimeUnit.SECONDS), "visitor-1", "first-player", "old:visitor-1")
            assertEquals("The second request still owns the generator", 0, old.closes.get())
            second.onSuccess("second-player")
            assertResult(secondResult.get(5, TimeUnit.SECONDS), "visitor-1", "second-player", "old:visitor-1")
            assertEquals(1, old.closes.get())
            assertEquals(listOf(old), environment.retired)
        } finally {
            first.onSuccess("released")
            second.onSuccess("released")
            executor.shutdownNow()
        }
    }

    @Test
    fun failedRequestHasOneRecoveryAttemptWhenItReusesConcurrentReplacement() {
        val old = ScriptedPoTokenGenerator("old")
        val replacement = ScriptedPoTokenGenerator("replacement")
        val unnecessary = ScriptedPoTokenGenerator("unnecessary")
        val pending = SingleSubject.create<String>()
        val subscribed = CountDownLatch(1)
        val replacementFailure = PoTokenException("replacement player failed")
        old.generate = { identifier ->
            if (identifier == "overlap") {
                pending.doOnSubscribe { subscribed.countDown() }
            } else {
                Single.just("old:$identifier")
            }
        }
        replacement.generate = { identifier ->
            if (identifier == "overlap") {
                Single.error(replacementFailure)
            } else {
                Single.just("replacement:$identifier")
            }
        }
        val environment = ScriptedPoTokenEnvironment(old, replacement, unnecessary)
        val provider = WebPoTokenProvider(environment)
        provider.getWebClientPoToken("prime")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit(Callable { provider.getWebClientPoToken("overlap") })
            await(subscribed)
            old.expired = true
            provider.getWebClientPoToken("replacement-owner")
            pending.onError(PoTokenException("old player failed"))

            try {
                result.get(5, TimeUnit.SECONDS)
                fail("A request must stop after its recovery attempt fails")
            } catch (error: ExecutionException) {
                assertSame(replacementFailure, error.cause)
            }
            assertEquals(2, environment.factoryCalls)
            assertEquals(1, old.closes.get())
            assertEquals(listOf(old), environment.retired)
            assertEquals(0, replacement.closes.get())
        } finally {
            pending.onSuccess("released")
            executor.shutdownNow()
        }
    }

    @Test
    fun retiredGeneratorClosesWhenItsFinalRequestTimesOut() {
        val old = ScriptedPoTokenGenerator("old")
        val replacement = ScriptedPoTokenGenerator("replacement")
        val pending = SingleSubject.create<String>()
        val subscribed = CountDownLatch(1)
        old.generate = { identifier ->
            if (identifier == "overlap") {
                pending.doOnSubscribe { subscribed.countDown() }
            } else {
                Single.just("old:$identifier")
            }
        }
        val environment = ScriptedPoTokenEnvironment(old, replacement)
        val scheduler = TestScheduler()
        val provider = WebPoTokenProvider(environment, scheduler)
        provider.getWebClientPoToken("prime")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit(Callable { provider.getWebClientPoToken("overlap") })
            await(subscribed)
            old.expired = true
            provider.getWebClientPoToken("replacement-owner")
            assertEquals(0, old.closes.get())

            scheduler.advanceTimeBy(30, TimeUnit.SECONDS)

            assertResult(result.get(5, TimeUnit.SECONDS), "visitor-2", "replacement:overlap", "replacement:visitor-2")
            assertFalse(pending.hasObservers())
            assertEquals(1, old.closes.get())
            assertEquals(listOf(old), environment.retired)
            assertEquals(0, replacement.closes.get())
            assertEquals(2, environment.factoryCalls)
        } finally {
            pending.onSuccess("released")
            executor.shutdownNow()
        }
    }
}
