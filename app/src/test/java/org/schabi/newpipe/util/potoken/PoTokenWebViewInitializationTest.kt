package org.schabi.newpipe.util.potoken

import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.junit.Assert.assertEquals
import org.junit.Test

class PoTokenWebViewInitializationTest {
    @Test
    fun disposalBeforeQueuedPublicationClosesWithoutPublishing() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = environment.start()
        environment.bridge.downloadAndRunBotguard()
        environment.runMain()
        environment.respond(0, PoTokenWebViewTestEnvironment.CHALLENGE)
        environment.bridge.onRunBotguardResult("response")
        environment.runMain()
        environment.respond(1, PoTokenWebViewTestEnvironment.INTEGRITY_TOKEN)
        initialization.assertNoValues().assertNotComplete()
        environment.evaluations.last().complete()
        initialization.dispose()
        environment.runMain()
        initialization.assertNoValues().assertNoErrors()
        assertEquals(1, environment.closeCount)
        environment.bridge.downloadAndRunBotguard()
        environment.bridge.onRunBotguardResult("late response")
        environment.runMain()
        assertEquals(2, environment.requests.size)
        assertEquals(1, environment.closeCount)
    }

    @Test
    fun earlyBotguardResultIsIgnoredAndNormalInitializationStillCompletes() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = environment.start()
        environment.bridge.onRunBotguardResult("unsolicited response")
        environment.runMain()
        assertEquals(0, environment.requests.size)
        initialization.assertNoValues().assertNotComplete()
        environment.bridge.downloadAndRunBotguard()
        environment.runMain()
        environment.respond(0, PoTokenWebViewTestEnvironment.CHALLENGE)
        environment.bridge.onRunBotguardResult("normal response")
        environment.runMain()
        environment.respond(1, PoTokenWebViewTestEnvironment.INTEGRITY_TOKEN)
        environment.evaluations.last().complete()
        environment.runMain()
        initialization.assertValueCount(1).assertComplete()
        assertEquals(2, environment.requests.size)
        assertEquals(0, environment.closeCount)
        initialization.values().single().close()
        assertEquals(1, environment.closeCount)
    }

    @Test
    fun synchronousChallengeEvaluationFailureFailsFactoryAndCloses() {
        val errors = mutableListOf<Throwable>()
        val previous = RxJavaPlugins.getErrorHandler()
        RxJavaPlugins.setErrorHandler { errors.add(it) }
        try {
            val environment = PoTokenWebViewTestEnvironment()
            val initialization = environment.start()
            environment.bridge.downloadAndRunBotguard()
            environment.runMain()
            environment.evaluationError = PoTokenException("challenge evaluation failure")
            environment.respond(0, PoTokenWebViewTestEnvironment.CHALLENGE)
            initialization.assertError(PoTokenException::class.java)
            assertEquals(1, environment.closeCount)
            assertEquals(emptyList<Throwable>(), errors)
        } finally {
            RxJavaPlugins.setErrorHandler(previous)
        }
    }

    @Test
    fun synchronousIntegrityEvaluationFailureFailsFactoryAndCloses() {
        val errors = mutableListOf<Throwable>()
        val previous = RxJavaPlugins.getErrorHandler()
        RxJavaPlugins.setErrorHandler { errors.add(it) }
        try {
            val environment = PoTokenWebViewTestEnvironment()
            val initialization = environment.start()
            environment.bridge.downloadAndRunBotguard()
            environment.runMain()
            environment.respond(0, PoTokenWebViewTestEnvironment.CHALLENGE)
            environment.bridge.onRunBotguardResult("response")
            environment.runMain()
            environment.evaluationError = PoTokenException("integrity evaluation failure")
            environment.respond(1, PoTokenWebViewTestEnvironment.INTEGRITY_TOKEN)
            initialization.assertError(PoTokenException::class.java)
            assertEquals(1, environment.closeCount)
            assertEquals(emptyList<Throwable>(), errors)
        } finally {
            RxJavaPlugins.setErrorHandler(previous)
        }
    }
}
