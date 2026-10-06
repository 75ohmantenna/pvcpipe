package org.schabi.newpipe.util.potoken

import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/** Owns the initialized proof-token session used by blocking extractor requests. */
internal class WebPoTokenProvider(
    private val environment: Environment,
    private val timeoutScheduler: Scheduler = Schedulers.computation(),
    private val diagnostics: (Event) -> Unit = {}
) : PoTokenProvider {
    internal interface Environment {
        fun supportsWebView(): Boolean
        fun obtainVisitorData(): String
        fun createGenerator(): Single<PoTokenGenerator>
        fun retire(generator: PoTokenGenerator)
    }

    internal enum class Event { BROKEN_WEBVIEW, RETRYING, GENERATED }

    private class Session(
        val generator: PoTokenGenerator,
        val visitorData: String,
        val streamingToken: String
    ) {
        private val ownershipLock = Any()
        private var activeRequests = 0
        private var retired = false
        private var closeRequested = false

        fun borrow() = synchronized(ownershipLock) {
            check(!retired)
            activeRequests++
        }

        fun retire(): PoTokenGenerator? = synchronized(ownershipLock) {
            retired = true
            takeRetirement()
        }

        fun release(): PoTokenGenerator? = synchronized(ownershipLock) {
            check(activeRequests > 0)
            activeRequests--
            takeRetirement()
        }

        private fun takeRetirement(): PoTokenGenerator? {
            if (!retired || activeRequests != 0 || closeRequested) return null
            closeRequested = true
            return generator
        }
    }

    private data class BorrowedSession(
        val session: Session,
        val recreated: Boolean,
        val unusedPreviousGenerator: PoTokenGenerator?
    )

    private val webViewSupported by lazy { environment.supportsWebView() }

    @Volatile private var webViewBadImpl = false
    private val sessionLock = Any()
    private var session: Session? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) return null

        try {
            return generatePlayerToken(videoId)
        } catch (error: RuntimeException) {
            // Single.blockingGet wraps checked exceptions, including WebView failures.
            when (val cause = error.cause) {
                is BadWebViewException -> {
                    diagnostics(Event.BROKEN_WEBVIEW)
                    webViewBadImpl = true
                    return null
                }

                null -> throw error

                else -> throw cause
            }
        }
    }

    private fun generatePlayerToken(videoId: String): PoTokenResult {
        var failedGenerator: PoTokenGenerator? = null
        var recovering = false
        while (true) {
            val borrowed = acquireSession(failedGenerator)
            val selected = borrowed.session
            try {
                // Main-thread retirement is external and must not run under the ownership lock.
                borrowed.unusedPreviousGenerator?.let(environment::retire)
                val playerToken = try {
                    selected.generator.generatePoToken(videoId).awaitToken()
                } catch (error: Throwable) {
                    // A newly initialized session already failed, or recovery was already used.
                    if (borrowed.recreated || recovering) throw error
                    diagnostics(Event.RETRYING)
                    failedGenerator = selected.generator
                    recovering = true
                    continue
                }

                diagnostics(Event.GENERATED)
                return PoTokenResult(selected.visitorData, playerToken, selected.streamingToken)
            } finally {
                releaseSession(selected)
            }
        }
    }

    private fun acquireSession(failedGenerator: PoTokenGenerator?): BorrowedSession {
        var unsuccessfulGenerator: PoTokenGenerator? = null
        try {
            return synchronized(sessionLock) {
                val current = session
                val shouldRecreate = current == null ||
                    (failedGenerator != null && current.generator === failedGenerator) ||
                    current.generator.isExpired()
                var unusedPreviousGenerator: PoTokenGenerator? = null

                if (shouldRecreate) {
                    val visitorData = environment.obtainVisitorData()
                    val generator = environment.createGenerator().awaitToken()
                    unsuccessfulGenerator = generator
                    val streamingToken = generator.generatePoToken(visitorData).awaitToken()
                    // Neither the generator nor its matching tokens become visible before success.
                    session = Session(generator, visitorData, streamingToken)
                    unsuccessfulGenerator = null
                    unusedPreviousGenerator = current?.retire()
                }

                val selected = session!!
                selected.borrow()
                BorrowedSession(selected, shouldRecreate, unusedPreviousGenerator)
            }
        } catch (error: Throwable) {
            // Candidate cleanup cannot hold up other owners releasing an existing session.
            unsuccessfulGenerator?.let(environment::retire)
            throw error
        }
    }

    private fun releaseSession(selected: Session) {
        // This short per-session lock is independent of potentially slow session initialization.
        val unusedGenerator = selected.release()
        unusedGenerator?.let(environment::retire)
    }

    private fun <T : Any> Single<T>.awaitToken(): T = timeout(30, TimeUnit.SECONDS, timeoutScheduler).blockingGet()

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
