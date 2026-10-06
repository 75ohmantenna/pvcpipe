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

    private data class Session(
        val generator: PoTokenGenerator,
        val visitorData: String,
        val streamingToken: String
    )

    private val webViewSupported by lazy { environment.supportsWebView() }
    private var webViewBadImpl = false
    private val sessionLock = Any()
    private var session: Session? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) return null

        try {
            return getWebClientPoToken(videoId, forceRecreate = false)
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

    private fun getWebClientPoToken(
        videoId: String,
        forceRecreate: Boolean,
        failedGenerator: PoTokenGenerator? = null
    ): PoTokenResult {
        val (selected, recreated) = synchronized(sessionLock) {
            val current = session
            val shouldRecreate = current == null ||
                (forceRecreate && current.generator === failedGenerator) ||
                current.generator.isExpired()

            if (shouldRecreate) {
                val visitorData = environment.obtainVisitorData()
                val generator = environment.createGenerator().awaitToken()
                val streamingToken = try {
                    generator.generatePoToken(visitorData).awaitToken()
                } catch (error: Throwable) {
                    environment.retire(generator)
                    throw error
                }
                // Neither the generator nor its matching tokens become visible before success.
                session = Session(generator, visitorData, streamingToken)
                current?.let { environment.retire(it.generator) }
            }

            Pair(session!!, shouldRecreate)
        }

        val playerToken = try {
            // The selected session is coherent; player requests can execute concurrently.
            selected.generator.generatePoToken(videoId).awaitToken()
        } catch (error: Throwable) {
            if (recreated) throw error
            diagnostics(Event.RETRYING)
            return getWebClientPoToken(
                videoId,
                forceRecreate = true,
                failedGenerator = selected.generator
            )
        }

        diagnostics(Event.GENERATED)
        return PoTokenResult(selected.visitorData, playerToken, selected.streamingToken)
    }

    private fun <T : Any> Single<T>.awaitToken(): T = timeout(30, TimeUnit.SECONDS, timeoutScheduler).blockingGet()

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
