package org.schabi.newpipe.util.potoken

import io.reactivex.rxjava3.core.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoTokenWebViewLifecycleTest {
    @Test
    fun initializationPublishesOnlyAfterIntegrityTokenInstallationCompletes() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = environment.start()
        initialization.assertNoValues().assertNotComplete()
        environment.bridge.downloadAndRunBotguard()
        environment.runMain()
        environment.respond(0, PoTokenWebViewTestEnvironment.CHALLENGE)
        initialization.assertNoValues().assertNotComplete()
        environment.bridge.onRunBotguardResult("botguard-response")
        environment.runMain()
        environment.respond(1, PoTokenWebViewTestEnvironment.INTEGRITY_TOKEN)
        initialization.assertNoValues().assertNotComplete()
        environment.evaluations.last().complete()
        environment.runMain()
        initialization.assertValueCount(1).assertComplete()
        assertEquals(0, environment.closeCount)
        initialization.values().single().close()
        assertEquals(1, environment.closeCount)
    }

    @Test
    fun factoryRunsInitializationAndConvertsPlayerTokens() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        assertTrue(environment.html.contains("PoTokenWebView.downloadAndRunBotguard()"))
        assertTrue(environment.requests[0].url.endsWith("/Create"))
        assertTrue(environment.requests[1].url.endsWith("/GenerateIT"))
        assertTrue(environment.requests[1].body.contains("botguard-response"))
        assertTrue(environment.evaluations[0].script.contains("\"program\":\"program\""))
        assertTrue(environment.evaluations[1].script.contains("new Uint8Array([97,98,99])"))
        val token = generator.generatePoToken("video").test()
        environment.runMain()
        environment.succeed(0, "251,255")
        token.assertResult("-_8=")
        assertFalse(generator.isExpired())
        environment.now = environment.now.plusSeconds(3001)
        assertTrue(generator.isExpired())
        generator.close()
    }

    @Test
    fun distinctIdentifiersCanCompleteInReverseOrder() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        val first = generator.generatePoToken("first").test()
        val second = generator.generatePoToken("second").test()
        environment.runMain()
        environment.succeed(1, "98")
        second.assertResult("Yg==")
        first.assertNoValues().assertNotComplete()
        environment.succeed(0, "97")
        first.assertResult("YQ==")
        generator.close()
    }

    @Test
    fun closingFailsPendingRequestsAndRejectsFurtherGeneration() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        val pending = generator.generatePoToken("first").test()
        environment.runMain()
        environment.bridge.onJsInitializationError("late initialization failure")
        environment.runMain()
        pending.assertError(PoTokenException::class.java)
        generator.close()
        val late = generator.generatePoToken("late").test()
        environment.runMain()
        late.assertError(PoTokenException::class.java)
        assertEquals(1, environment.closeCount)
        val calls = environment.tokenCalls.size
        val canceled = generator.generatePoToken("canceled").test()
        canceled.dispose()
        environment.runMain()
        assertEquals(calls, environment.tokenCalls.size)
    }

    @Test
    fun disposingBeforeConstructionSkipsBrowserCreation() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = PoTokenWebView.newPoTokenGenerator(environment).test()
        initialization.dispose()
        environment.runMain()
        assertEquals("", environment.html)
        assertEquals(0, environment.closeCount)
    }

    @Test
    fun disposingInitializationCancelsTransportAndClosesOnce() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = environment.start()
        environment.bridge.downloadAndRunBotguard()
        environment.runMain()
        assertTrue(environment.requests[0].response.hasObservers())
        initialization.dispose()
        environment.runMain()
        assertFalse(environment.requests[0].response.hasObservers())
        assertEquals(1, environment.closeCount)
        environment.respond(0, PoTokenWebViewTestEnvironment.CHALLENGE)
        assertTrue(environment.evaluations.isEmpty())
    }

    @Test
    fun htmlAndNetworkFailuresReachFactoryAndCloseBrowser() {
        val htmlEnvironment = PoTokenWebViewTestEnvironment()
        htmlEnvironment.htmlResult = Single.error(PoTokenException("asset failure"))
        htmlEnvironment.start().assertError(PoTokenException::class.java)
        assertEquals(1, htmlEnvironment.closeCount)
        val networkEnvironment = PoTokenWebViewTestEnvironment()
        val initialization = networkEnvironment.start()
        networkEnvironment.bridge.downloadAndRunBotguard()
        networkEnvironment.runMain()
        networkEnvironment.requests[0].response.onError(PoTokenException("network failure"))
        networkEnvironment.runMain()
        initialization.assertError(PoTokenException::class.java)
        assertEquals(1, networkEnvironment.closeCount)
    }

    @Test
    fun unsuccessfulHttpResponseFailsFactoryAndClosesBrowser() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = environment.start()
        environment.bridge.downloadAndRunBotguard()
        environment.runMain()
        environment.respond(0, "sensitive response", 403)
        initialization.assertError { it is PoTokenException && it.message == "Invalid response code: 403" }
        assertEquals(1, environment.closeCount)
    }

    @Test
    fun consoleAndJavaScriptFailuresUseSafeErrorMessages() {
        val environment = PoTokenWebViewTestEnvironment()
        val initialization = environment.start()
        environment.consoleError(42)
        environment.runMain()
        initialization.assertError { it is BadWebViewException && it.message == "Uncaught JavaScript error at line 42" }
        assertEquals(1, environment.closeCount)
        val tokenEnvironment = PoTokenWebViewTestEnvironment()
        val generator = tokenEnvironment.initialize()
        val pending = generator.generatePoToken("video").test()
        tokenEnvironment.runMain()
        tokenEnvironment.fail(0, "private video identifier and SyntaxError")
        pending.assertError { it is BadWebViewException && it.message == "JavaScript syntax error" }
        generator.close()
    }

    @Test
    fun disposedRequestsDoNotEvaluateJavaScriptAndDoNotReceiveCallbacks() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        val beforeAdmission = generator.generatePoToken("canceled-before").test()
        beforeAdmission.dispose()
        environment.runMain()
        assertTrue(environment.tokenCalls.isEmpty())
        val pending = generator.generatePoToken("canceled-after").test()
        environment.runMain()
        pending.dispose()
        environment.succeed(0, "97")
        pending.assertNoValues()
        generator.close()
    }

    @Test
    fun failedMainThreadPostAndSynchronousEvaluationReachObservers() {
        val environment = PoTokenWebViewTestEnvironment()
        environment.acceptPosts = false
        PoTokenWebView.newPoTokenGenerator(environment).test().assertError(PoTokenException::class.java)
        assertEquals(0, environment.closeCount)
        val tokenEnvironment = PoTokenWebViewTestEnvironment()
        val generator = tokenEnvironment.initialize()
        tokenEnvironment.evaluationError = PoTokenException("browser evaluation failure")
        val pending = generator.generatePoToken("video").test()
        tokenEnvironment.runMain()
        pending.assertError(PoTokenException::class.java)
        generator.close()
    }

    @Test
    fun malformedTokenBytesFailOnlyTheirRequest() {
        val environment = PoTokenWebViewTestEnvironment()
        val generator = environment.initialize()
        val malformed = generator.generatePoToken("bad").test()
        val valid = generator.generatePoToken("good").test()
        environment.runMain()
        environment.succeed(0, "not bytes")
        malformed.assertError(NumberFormatException::class.java)
        environment.succeed(1, "97")
        valid.assertResult("YQ==")
        generator.close()
    }
}
