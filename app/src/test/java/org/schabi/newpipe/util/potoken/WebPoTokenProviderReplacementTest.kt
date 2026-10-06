package org.schabi.newpipe.util.potoken

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.SingleSubject
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class WebPoTokenProviderReplacementTest {
    @Test
    fun completedOldRequestReturnsWhileReplacementInitializationIsStillPending() {
        val old = ScriptedPoTokenGenerator("old")
        val replacement = ScriptedPoTokenGenerator("replacement")
        val pendingPlayer = SingleSubject.create<String>()
        val pendingCreation = SingleSubject.create<PoTokenGenerator>()
        val playerSubscribed = CountDownLatch(1)
        val creationSubscribed = CountDownLatch(1)
        old.generate = { identifier ->
            if (identifier == "overlap") {
                pendingPlayer.doOnSubscribe { playerSubscribed.countDown() }
            } else {
                Single.just("old:$identifier")
            }
        }
        val environment = ScriptedPoTokenEnvironment(old)
        environment.creations.add(pendingCreation.doOnSubscribe { creationSubscribed.countDown() })
        val provider = WebPoTokenProvider(environment)
        provider.getWebClientPoToken("prime")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val oldResult = executor.submit(Callable { provider.getWebClientPoToken("overlap") })
            await(playerSubscribed)
            old.expired = true
            val replacementResult = executor.submit(Callable { provider.getWebClientPoToken("next") })
            await(creationSubscribed)

            pendingPlayer.onSuccess("old-player")

            // Ownership release must not wait on another request's WebView initialization.
            assertResult(oldResult.get(5, TimeUnit.SECONDS), "visitor-1", "old-player", "old:visitor-1")
            assertEquals(0, old.closes.get())
            pendingCreation.onSuccess(replacement)
            assertResult(replacementResult.get(5, TimeUnit.SECONDS), "visitor-2", "replacement:next", "replacement:visitor-2")
            assertEquals(1, old.closes.get())
            assertEquals(listOf(old), environment.retired)
            assertEquals(2, environment.factoryCalls)
        } finally {
            pendingPlayer.onSuccess("released")
            pendingCreation.onSuccess(replacement)
            executor.shutdownNow()
        }
    }
}
