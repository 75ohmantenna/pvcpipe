package org.schabi.newpipe.util.potoken

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.SingleSubject
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

class WebPoTokenProviderTest {
    @Test
    fun unsupportedWebViewNeverObtainsVisitorDataOrCreatesGenerator() {
        val environment = ScriptedPoTokenEnvironment(supported = false)
        val provider = WebPoTokenProvider(environment)

        assertNull(provider.getWebClientPoToken("first"))
        assertNull(provider.getWebClientPoToken("second"))
        assertEquals(1, environment.supportChecks)
        assertEquals(0, environment.visitorCalls)
        assertEquals(0, environment.factoryCalls)
    }

    @Test
    fun otherClientTypesDoNotInitializeTheWebSession() {
        val environment = ScriptedPoTokenEnvironment()
        val provider = WebPoTokenProvider(environment)

        assertNull(provider.getWebEmbedClientPoToken("video"))
        assertNull(provider.getAndroidClientPoToken("video"))
        assertNull(provider.getIosClientPoToken("video"))
        assertEquals(0, environment.supportChecks)
        assertEquals(0, environment.factoryCalls)
    }

    @Test
    fun reusesVisitorDataAndStreamingTokenForDifferentVideos() {
        val generator = ScriptedPoTokenGenerator("first")
        val environment = ScriptedPoTokenEnvironment(generator)
        val provider = WebPoTokenProvider(environment)

        assertResult(provider.getWebClientPoToken("one"), "visitor-1", "first:one", "first:visitor-1")
        assertResult(provider.getWebClientPoToken("two"), "visitor-1", "first:two", "first:visitor-1")
        assertEquals(listOf("visitor-1", "one", "two"), generator.identifiers)
        assertEquals(1, environment.visitorCalls)
        assertEquals(1, environment.factoryCalls)
        assertEquals(0, generator.closes.get())
    }

    @Test
    fun expiredSessionIsReplacedWithACoherentTokenPair() {
        val old = ScriptedPoTokenGenerator("old")
        val replacement = ScriptedPoTokenGenerator("replacement")
        val environment = ScriptedPoTokenEnvironment(old, replacement)
        val provider = WebPoTokenProvider(environment)
        provider.getWebClientPoToken("prime")
        old.expired = true

        assertResult(provider.getWebClientPoToken("next"), "visitor-2", "replacement:next", "replacement:visitor-2")
        assertEquals(listOf(old), environment.retired)
        assertEquals(1, old.closes.get())
        assertEquals(0, replacement.closes.get())
    }

    @Test
    fun failedVisitorAcquisitionDoesNotCreateOrPublishAGenerator() {
        val failure = PoTokenException("visitor unavailable")
        val generator = ScriptedPoTokenGenerator("working")
        val environment = ScriptedPoTokenEnvironment(generator)
        environment.visitorAction = { throw failure }
        val provider = WebPoTokenProvider(environment)

        assertSame(failure, thrown { provider.getWebClientPoToken("first") })
        assertEquals(0, environment.factoryCalls)
        environment.visitorAction = null
        assertResult(provider.getWebClientPoToken("second"), "visitor-2", "working:second", "working:visitor-2")
        assertEquals(1, environment.factoryCalls)
    }

    @Test
    fun failedGeneratorCreationCanBeRetriedOnTheNextCall() {
        val failure = PoTokenException("initialization failed")
        val environment = ScriptedPoTokenEnvironment(ScriptedPoTokenGenerator("working"))
        environment.creations.addFirst(Single.error(failure))
        val provider = WebPoTokenProvider(environment)

        assertSame(failure, thrown { provider.getWebClientPoToken("first") })
        assertResult(provider.getWebClientPoToken("second"), "visitor-2", "working:second", "working:visitor-2")
        assertTrue(environment.retired.isEmpty())
        assertEquals(2, environment.factoryCalls)
    }

    @Test
    fun failedStreamingInitializationClosesOnlyCandidateAndKeepsPreviousSession() {
        val old = ScriptedPoTokenGenerator("old")
        val candidate = ScriptedPoTokenGenerator("candidate")
        val failure = PoTokenException("streaming initialization failed")
        candidate.generate = { Single.error(failure) }
        val environment = ScriptedPoTokenEnvironment(old, candidate)
        val provider = WebPoTokenProvider(environment)
        provider.getWebClientPoToken("prime")
        old.expired = true

        assertSame(failure, thrown { provider.getWebClientPoToken("failed") })
        assertEquals(listOf(candidate), environment.retired)
        assertEquals(0, old.closes.get())
        old.expired = false
        assertResult(provider.getWebClientPoToken("retained"), "visitor-1", "old:retained", "old:visitor-1")
        assertEquals(2, environment.factoryCalls)
    }

    @Test
    fun playerFailureOnNewSessionPropagatesWithoutCreatingAnotherGenerator() {
        val generator = ScriptedPoTokenGenerator("new")
        val failure = PoTokenException("player failed")
        generator.generate = { identifier ->
            if (identifier == "video") Single.error(failure) else Single.just("streaming")
        }
        val environment = ScriptedPoTokenEnvironment(generator)
        val provider = WebPoTokenProvider(environment)

        assertSame(failure, thrown { provider.getWebClientPoToken("video") })
        assertEquals(1, environment.factoryCalls)
        assertTrue(environment.retired.isEmpty())
    }

    @Test
    fun playerFailureOnCachedSessionRecreatesAndReturnsReplacementPair() {
        val old = ScriptedPoTokenGenerator("old")
        val replacement = ScriptedPoTokenGenerator("replacement")
        val failure = PoTokenException("lost WebView content")
        old.generate = { identifier ->
            if (identifier == "video") Single.error(failure) else Single.just("old:$identifier")
        }
        val environment = ScriptedPoTokenEnvironment(old, replacement)
        val provider = WebPoTokenProvider(environment)
        provider.getWebClientPoToken("prime")

        assertResult(provider.getWebClientPoToken("video"), "visitor-2", "replacement:video", "replacement:visitor-2")
        assertEquals(2, environment.factoryCalls)
        assertEquals(listOf(old), environment.retired)
    }

    @Test
    fun brokenWebViewIsRememberedWithoutRepeatedInitialization() {
        val environment = ScriptedPoTokenEnvironment()
        environment.creations.add(Single.error(BadWebViewException("unsupported JavaScript")))
        val provider = WebPoTokenProvider(environment)

        assertNull(provider.getWebClientPoToken("first"))
        assertNull(provider.getWebClientPoToken("second"))
        assertEquals(1, environment.factoryCalls)
        assertEquals(1, environment.visitorCalls)
    }

    @Test
    fun staleFailureUsesAlreadyPublishedReplacementInsteadOfRecreatingIt() {
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
        val provider = WebPoTokenProvider(environment)
        provider.getWebClientPoToken("prime")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit(Callable { provider.getWebClientPoToken("overlap") })
            await(subscribed)
            old.expired = true
            provider.getWebClientPoToken("replacement-owner")
            pending.onError(PoTokenException("old request failed"))

            assertResult(result.get(5, TimeUnit.SECONDS), "visitor-2", "replacement:overlap", "replacement:visitor-2")
            assertEquals(2, environment.factoryCalls)
        } finally {
            pending.onSuccess("released")
            executor.shutdownNow()
        }
    }

    @Test
    fun generatorCreationTimeoutDisposesInitializationWithoutPublishingIt() {
        val pending = SingleSubject.create<PoTokenGenerator>()
        val subscribed = CountDownLatch(1)
        val environment = ScriptedPoTokenEnvironment(ScriptedPoTokenGenerator("working"))
        environment.creations.addFirst(pending.doOnSubscribe { subscribed.countDown() })
        val scheduler = TestScheduler()
        val provider = WebPoTokenProvider(environment, scheduler)

        assertTimeout(provider, scheduler, subscribed)
        assertFalse(pending.hasObservers())
        assertTrue(environment.retired.isEmpty())
        assertResult(provider.getWebClientPoToken("next"), "visitor-2", "working:next", "working:visitor-2")
    }

    @Test
    fun streamingInitializationTimeoutClosesCandidateAndAllowsLaterInitialization() {
        val pending = SingleSubject.create<String>()
        val subscribed = CountDownLatch(1)
        val candidate = ScriptedPoTokenGenerator("candidate")
        candidate.generate = { pending.doOnSubscribe { subscribed.countDown() } }
        val environment = ScriptedPoTokenEnvironment(candidate, ScriptedPoTokenGenerator("working"))
        val scheduler = TestScheduler()
        val provider = WebPoTokenProvider(environment, scheduler)

        assertTimeout(provider, scheduler, subscribed)
        assertFalse(pending.hasObservers())
        assertEquals(listOf(candidate), environment.retired)
        assertEquals(1, candidate.closes.get())
        assertResult(provider.getWebClientPoToken("next"), "visitor-2", "working:next", "working:visitor-2")
    }

    @Test
    fun playerTimeoutOnNewSessionPropagatesAndDisposesOnlyThatRequest() {
        val pending = SingleSubject.create<String>()
        val subscribed = CountDownLatch(1)
        val generator = ScriptedPoTokenGenerator("working")
        generator.generate = { identifier ->
            if (identifier == "timeout") {
                pending.doOnSubscribe { subscribed.countDown() }
            } else {
                Single.just("working:$identifier")
            }
        }
        val environment = ScriptedPoTokenEnvironment(generator)
        val scheduler = TestScheduler()
        val provider = WebPoTokenProvider(environment, scheduler)

        assertTimeout(provider, scheduler, subscribed)
        assertFalse(pending.hasObservers())
        assertEquals(0, generator.closes.get())
        assertResult(provider.getWebClientPoToken("next"), "visitor-1", "working:next", "working:visitor-1")
        assertEquals(1, environment.factoryCalls)
    }

    private fun assertTimeout(
        provider: WebPoTokenProvider,
        scheduler: TestScheduler,
        subscribed: CountDownLatch
    ) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit(Callable { provider.getWebClientPoToken("timeout") })
            await(subscribed)
            scheduler.advanceTimeBy(30, TimeUnit.SECONDS)
            try {
                result.get(5, TimeUnit.SECONDS)
                fail("Expected timeout")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is TimeoutException)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}

internal class ScriptedPoTokenEnvironment(
    vararg generators: ScriptedPoTokenGenerator,
    private val supported: Boolean = true
) : WebPoTokenProvider.Environment {
    val creations = ArrayDeque<Single<PoTokenGenerator>>()
    val retired: MutableList<PoTokenGenerator> = Collections.synchronizedList(mutableListOf())
    var supportChecks = 0
    var visitorCalls = 0
    var factoryCalls = 0
    var visitorAction: (() -> String)? = null

    init {
        generators.forEach { creations.add(Single.just<PoTokenGenerator>(it)) }
    }

    override fun supportsWebView(): Boolean {
        supportChecks++
        return supported
    }

    override fun obtainVisitorData(): String {
        visitorCalls++
        return visitorAction?.invoke() ?: "visitor-$visitorCalls"
    }

    override fun createGenerator(): Single<PoTokenGenerator> {
        factoryCalls++
        return creations.removeFirst()
    }

    override fun retire(generator: PoTokenGenerator) {
        retired.add(generator)
        generator.close()
    }
}

internal class ScriptedPoTokenGenerator(private val name: String) : PoTokenGenerator {
    val identifiers: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val closes = AtomicInteger()

    @Volatile var expired = false
    var generate: (String) -> Single<String> = { Single.just("$name:$it") }
    var onClose: () -> Unit = {}

    override fun generatePoToken(identifier: String): Single<String> = Single.defer {
        identifiers.add(identifier)
        generate(identifier)
    }

    override fun isExpired(): Boolean = expired

    override fun close() {
        closes.incrementAndGet()
        onClose()
    }
}

internal fun assertResult(
    result: PoTokenResult?,
    visitor: String,
    player: String,
    streaming: String
) {
    requireNotNull(result)
    assertEquals(visitor, result.visitorData)
    assertEquals(player, result.playerRequestPoToken)
    assertEquals(streaming, result.streamingDataPoToken)
}

internal fun await(latch: CountDownLatch) {
    assertTrue("Operation did not start", latch.await(5, TimeUnit.SECONDS))
}

internal fun thrown(operation: () -> Unit): Throwable {
    try {
        operation()
    } catch (error: Throwable) {
        return error
    }
    throw AssertionError("Expected failure")
}
