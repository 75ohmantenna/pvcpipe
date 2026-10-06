package org.schabi.newpipe.util.potoken

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.reactivex.rxjava3.core.Single
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
    private val webProvider = WebPoTokenProvider(AndroidPoTokenEnvironment()) { event ->
        when (event) {
            WebPoTokenProvider.Event.BROKEN_WEBVIEW ->
                Log.e(TAG, "Could not obtain poToken because WebView is broken")

            WebPoTokenProvider.Event.RETRYING -> Log.e(TAG, "Failed to obtain poToken, retrying")

            WebPoTokenProvider.Event.GENERATED -> if (BuildConfig.DEBUG) {
                Log.d(TAG, "Generated player and streaming poTokens")
            }
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = webProvider.getWebClientPoToken(videoId)

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}

internal class AndroidPoTokenEnvironment(
    private val generatorFactory: PoTokenGenerator.Factory = PoTokenWebView
) : WebPoTokenProvider.Environment {
    override fun supportsWebView(): Boolean = DeviceUtils.supportsWebView()

    override fun obtainVisitorData(): String {
        val requestInfo = InnertubeClientRequestInfo.ofWebClient()
        requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
        return YoutubeParsingHelper.getVisitorDataFromInnertube(
            requestInfo,
            NewPipe.getPreferredLocalization(),
            NewPipe.getPreferredContentCountry(),
            YoutubeParsingHelper.getYouTubeHeaders(),
            YoutubeParsingHelper.YOUTUBEI_V1_URL,
            null,
            false
        )
    }

    override fun createGenerator(): Single<PoTokenGenerator> = generatorFactory.newPoTokenGenerator(App.instance)

    override fun retire(generator: PoTokenGenerator) {
        Handler(Looper.getMainLooper()).post { generator.close() }
    }
}
