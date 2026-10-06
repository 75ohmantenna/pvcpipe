package org.schabi.newpipe.util.potoken

import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.junit.Assert.assertEquals
import org.junit.Test

class PoTokenWebViewCorrectionTest {
    @Test
    fun browserConstructionFailureReachesFactoryObserver() {
        val environment = PoTokenWebViewTestEnvironment()
        environment.creationError = PoTokenException("browser creation failure")
        val initialization = PoTokenWebView.newPoTokenGenerator(environment).test()
        environment.runMain()
        initialization.assertError(PoTokenException::class.java)
        assertEquals(0, environment.closeCount)
    }

    @Test
    fun browserHtmlLoadFailureReachesFactoryObserverAndCloses() {
        val errors = mutableListOf<Throwable>()
        RxJavaPlugins.setErrorHandler { errors.add(it) }
        try {
            val environment = PoTokenWebViewTestEnvironment()
            environment.loadError = PoTokenException("browser HTML load failure")
            environment.start().assertError(PoTokenException::class.java)
            assertEquals(1, environment.closeCount)
            assertEquals(emptyList<Throwable>(), errors)
        } finally {
            RxJavaPlugins.reset()
        }
    }

    @Test
    fun duplicateInitializationCallbacksDoNotStartDuplicateRequests() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = environment.start()
        environment.bridge.downloadAndRunBotguard()
        environment.bridge.downloadAndRunBotguard()
        environment.runMain()
        assertEquals(1, environment.requests.size)
        environment.respond(0, PoTokenWebViewTestEnvironment.CHALLENGE)
        environment.bridge.onRunBotguardResult("response")
        environment.bridge.onRunBotguardResult("response")
        environment.runMain()
        assertEquals(2, environment.requests.size)
        environment.respond(1, PoTokenWebViewTestEnvironment.INTEGRITY_TOKEN)
        environment.evaluations.last().complete()
        environment.runMain()
        initialization.assertValueCount(1).assertComplete()
        initialization.values().single().close()
    }

    @Test
    fun canceledOlderSuccessCannotCompleteNewerRequestForSameIdentifier() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        val old = generator.generatePoToken("same-video").test()
        environment.runMain()
        old.dispose()
        val newer = generator.generatePoToken("same-video").test()
        environment.runMain()
        environment.succeed(0, "97")
        newer.assertNoValues().assertNotComplete()
        environment.succeed(1, "98")
        newer.assertResult("Yg==")
        generator.close()
    }

    @Test
    fun canceledOlderErrorCannotFailNewerRequestForSameIdentifier() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        val old = generator.generatePoToken("same-video").test()
        environment.runMain()
        old.dispose()
        val newer = generator.generatePoToken("same-video").test()
        environment.runMain()
        environment.fail(0, "old error")
        newer.assertNoErrors().assertNoValues().assertNotComplete()
        environment.succeed(1, "98")
        newer.assertResult("Yg==")
        generator.close()
    }

    @Test
    fun sameIdentifierRequestsCanCompleteInReverseOrder() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        val first = generator.generatePoToken("same-video").test()
        val second = generator.generatePoToken("same-video").test()
        environment.runMain()
        environment.succeed(1, "98")
        second.assertResult("Yg==")
        first.assertNoValues().assertNotComplete()
        environment.succeed(0, "97")
        first.assertResult("YQ==")
        generator.close()
    }

    @Test
    fun duplicateOlderCallbackCannotCompleteNewerRequest() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        val old = generator.generatePoToken("same-video").test()
        environment.runMain()
        environment.succeed(0, "97")
        old.assertResult("YQ==")
        val newer = generator.generatePoToken("same-video").test()
        environment.runMain()
        environment.succeed(0, "97")
        newer.assertNoValues().assertNotComplete()
        environment.succeed(1, "98")
        newer.assertResult("Yg==")
        generator.close()
    }

    @Test
    fun malformedChallengeFailsInitializationAndClosesInsteadOfLeakingToRxHandler() {
        val errors = mutableListOf<Throwable>()
        RxJavaPlugins.setErrorHandler { errors.add(it) }
        try {
            val environment = PoTokenWebViewTestEnvironment()
            val initialization = environment.start()
            environment.bridge.downloadAndRunBotguard()
            environment.runMain()
            environment.respond(0, "not JSON")
            initialization.assertError(Throwable::class.java)
            assertEquals(1, environment.closeCount)
            assertEquals(emptyList<Throwable>(), errors)
        } finally {
            RxJavaPlugins.reset()
        }
    }

    @Test
    fun malformedIntegrityTokenFailsInitializationAndClosesInsteadOfLeakingToRxHandler() {
        val errors = mutableListOf<Throwable>()
        RxJavaPlugins.setErrorHandler { errors.add(it) }
        try {
            val environment = PoTokenWebViewTestEnvironment()
            val initialization = environment.start()
            environment.bridge.downloadAndRunBotguard()
            environment.runMain()
            environment.respond(0, PoTokenWebViewTestEnvironment.CHALLENGE)
            environment.bridge.onRunBotguardResult("response")
            environment.runMain()
            environment.respond(1, "[\"not base64!\",3600]")
            initialization.assertError(Throwable::class.java)
            assertEquals(1, environment.closeCount)
            assertEquals(emptyList<Throwable>(), errors)
        } finally {
            RxJavaPlugins.reset()
        }
    }

    @Test
    fun lateJavaScriptInitializationCallbacksAfterCloseDoNotStartNewTransport() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = environment.start()
        initialization.dispose()
        environment.runMain()
        assertEquals(1, environment.closeCount)
        environment.bridge.downloadAndRunBotguard()
        environment.bridge.onRunBotguardResult("late-response")
        environment.runMain()
        assertEquals(0, environment.requests.size)
        assertEquals(1, environment.closeCount)
    }
}
