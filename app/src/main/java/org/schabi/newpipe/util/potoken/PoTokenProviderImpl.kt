package org.schabi.newpipe.util.potoken

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.App
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.util.DeviceUtils

object PoTokenProviderImpl : PoTokenProvider {
    val TAG = PoTokenProviderImpl::class.simpleName
    private val webViewSupported by lazy { DeviceUtils.supportsWebView() }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private object WebPoTokenGenLock
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenGenerator? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) {
            return null
        }

        try {
            return getWebClientPoToken(videoId = videoId, forceRecreate = false)
        } catch (e: RuntimeException) {
            // RxJava's Single wraps exceptions into RuntimeErrors, so we need to unwrap them here
            when (val cause = e.cause) {
                is BadWebViewException -> {
                    Log.e(TAG, "Could not obtain poToken because WebView is broken")
                    webViewBadImpl = true
                    return null
                }

                null -> throw e

                else -> throw cause // includes PoTokenException
            }
        }
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenGenerator.generatePoToken] was called
     */
    private fun getWebClientPoToken(
        videoId: String,
        forceRecreate: Boolean,
        failedGenerator: PoTokenGenerator? = null
    ): PoTokenResult {
        // just a helper class since Kotlin does not have builtin support for 4-tuples
        data class Quadruple<T1, T2, T3, T4>(val t1: T1, val t2: T2, val t3: T3, val t4: T4)

        val (poTokenGenerator, visitorData, streamingPot, hasBeenRecreated) =
            synchronized(WebPoTokenGenLock) {
                val shouldRecreate = webPoTokenGenerator == null ||
                    (forceRecreate && webPoTokenGenerator === failedGenerator) ||
                    webPoTokenGenerator!!.isExpired()

                if (shouldRecreate) {
                    val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
                    innertubeClientRequestInfo.clientInfo.clientVersion =
                        YoutubeParsingHelper.getClientVersion()

                    val visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                        innertubeClientRequestInfo,
                        NewPipe.getPreferredLocalization(),
                        NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(),
                        YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null,
                        false
                    )
                    val generator = PoTokenWebView.newPoTokenGenerator(App.instance)
                        .timeout(30, TimeUnit.SECONDS).blockingGet()
                    val streamingPot = try {
                        generator.generatePoToken(visitorData)
                            .timeout(30, TimeUnit.SECONDS).blockingGet()
                    } catch (error: Throwable) {
                        Handler(Looper.getMainLooper()).post { generator.close() }
                        throw error
                    }
                    // Publish a coherent replacement only after initialization succeeds.
                    val previous = webPoTokenGenerator
                    webPoTokenVisitorData = visitorData
                    webPoTokenStreamingPot = streamingPot
                    webPoTokenGenerator = generator
                    previous?.let { Handler(Looper.getMainLooper()).post { it.close() } }
                }

                return@synchronized Quadruple(
                    webPoTokenGenerator!!,
                    webPoTokenVisitorData!!,
                    webPoTokenStreamingPot!!,
                    shouldRecreate
                )
            }

        val playerPot = try {
            // Not using synchronized here, since poTokenGenerator would be able to generate
            // multiple poTokens in parallel if needed. The only important thing is for exactly one
            // visitorData/streaming poToken to be generated before anything else.
            poTokenGenerator.generatePoToken(videoId).timeout(30, TimeUnit.SECONDS).blockingGet()
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if NewPipe goes in the background and the WebView
                // content is lost
                Log.e(TAG, "Failed to obtain poToken, retrying")
                return getWebClientPoToken(
                    videoId = videoId,
                    forceRecreate = true,
                    failedGenerator = poTokenGenerator
                )
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Generated player and streaming poTokens")
        }

        return PoTokenResult(visitorData, playerPot, streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
