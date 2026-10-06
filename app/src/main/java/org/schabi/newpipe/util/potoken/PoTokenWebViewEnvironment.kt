package org.schabi.newpipe.util.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.Instant
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.downloader.Response

/** Android and third-party operations used by the token generator implementation. */
internal interface PoTokenWebViewEnvironment {
    val mainScheduler: Scheduler
    fun readHtml(): Single<String>
    fun postBotguard(url: String, data: String): Single<Response>
    fun createBrowser(bridge: PoTokenWebView, onConsoleError: (Int) -> Unit): PoTokenBrowser
    fun postToMain(runnable: Runnable): Boolean
    fun now(): Instant
    fun debug(message: String)
    fun error(message: String)
}

internal interface PoTokenBrowser {
    fun loadHtml(html: String)
    fun evaluateJavascript(script: String, onComplete: () -> Unit)
    fun close()
}

internal class AndroidPoTokenWebViewEnvironment(private val context: Context) : PoTokenWebViewEnvironment {
    override val mainScheduler: Scheduler
        get() = AndroidSchedulers.mainThread()

    override fun readHtml(): Single<String> = Single.fromCallable {
        context.assets.open("po_token.html").bufferedReader().use { it.readText() }
    }.subscribeOn(Schedulers.io())

    override fun postBotguard(url: String, data: String): Single<Response> = Single.fromCallable {
        DownloaderImpl.getInstance().post(
            url,
            mapOf(
                "User-Agent" to listOf(DownloaderImpl.USER_AGENT),
                "Accept" to listOf("application/json"),
                "Content-Type" to listOf("application/json+protobuf"),
                "x-goog-api-key" to listOf(GOOGLE_API_KEY),
                "x-user-agent" to listOf("grpc-web-javascript/0.1")
            ),
            data.toByteArray()
        )
    }.subscribeOn(Schedulers.io())

    override fun createBrowser(bridge: PoTokenWebView, onConsoleError: (Int) -> Unit): PoTokenBrowser {
        val webView = WebView(context)
        webView.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            //noinspection SetJavaScriptEnabled the bundled token workflow requires JavaScript.
            javaScriptEnabled = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(this, false)
            }
            userAgentString = DownloaderImpl.USER_AGENT
            blockNetworkLoads = true
        }
        webView.addJavascriptInterface(bridge, PoTokenWebView.JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.message().contains("Uncaught")) {
                    Log.e(TAG, "This WebView implementation reported an uncaught error")
                    onConsoleError(message.lineNumber())
                }
                return super.onConsoleMessage(message)
            }
        }
        return object : PoTokenBrowser {
            override fun loadHtml(html: String) {
                webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
            }

            override fun evaluateJavascript(script: String, onComplete: () -> Unit) {
                webView.evaluateJavascript(script) { onComplete() }
            }

            override fun close() {
                webView.clearHistory()
                webView.clearCache(true)
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    override fun postToMain(runnable: Runnable): Boolean = Handler(Looper.getMainLooper()).post(runnable)
    override fun now(): Instant = Instant.now()

    override fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    override fun error(message: String) {
        if (BuildConfig.DEBUG) Log.e(TAG, message)
    }

    companion object {
        private val TAG = PoTokenWebView::class.simpleName

        // Public key used by BotGuard, obtained from its browser requests.
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
    }
}
