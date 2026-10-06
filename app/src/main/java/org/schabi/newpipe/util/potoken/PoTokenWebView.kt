package org.schabi.newpipe.util.potoken

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.annotation.MainThread
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.time.Instant

class PoTokenWebView private constructor(
    private val environment: PoTokenWebViewEnvironment,
    // to be used exactly once only during initialization!
    private val generatorEmitter: SingleEmitter<PoTokenGenerator>
) : PoTokenGenerator {
    private val webView = environment.createBrowser(this) { line ->
        val exception = BadWebViewException("Uncaught JavaScript error at line $line")
        onInitializationErrorCloseAndCancel(exception)
        popAllPoTokenEmitters().forEach { (_, emitter) -> emitter.tryOnError(exception) }
    }
    private val disposables = CompositeDisposable() // used only during initialization
    private val poTokenEmitters = mutableListOf<Pair<String, SingleEmitter<String>>>()
    private lateinit var expirationInstant: Instant

    // Accessed only on the main thread.
    private var closed = false

    @Volatile private var initializationComplete = false

    //region Initialization

    /**
     * Must be called right after instantiating [PoTokenWebView] to perform the actual
     * initialization. This will asynchronously go through all the steps needed to load BotGuard,
     * run it, and obtain an `integrityToken`.
     */
    private fun loadHtmlAndObtainBotguard() {
        environment.debug("loadHtmlAndObtainBotguard() called")

        disposables.add(
            environment.readHtml()
                .observeOn(environment.mainScheduler)
                .subscribe(
                    { html ->
                        webView.loadHtml(
                            html.replaceFirst(
                                "</script>",
                                // calls downloadAndRunBotguard() when the page has finished loading
                                "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
                            )
                        )
                    },
                    this::onInitializationErrorCloseAndCancel
                )
        )
    }

    /**
     * Called during initialization by the JavaScript snippet appended to the HTML page content in
     * [loadHtmlAndObtainBotguard] after the WebView content has been loaded.
     */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        environment.debug("downloadAndRunBotguard() called")

        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]"
        ) { responseBody ->
            val parsedChallengeData = parseChallengeData(responseBody)
            webView.evaluateJavascript(
                """try {
                    data = $parsedChallengeData
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                {}
            )
        }
    }

    /**
     * Called during initialization by the JavaScript snippets from either
     * [downloadAndRunBotguard] or [onRunBotguardResult].
     */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        environment.error("Initialization error from JavaScript")
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
    }

    /**
     * Called during initialization by the JavaScript snippet from [downloadAndRunBotguard] after
     * obtaining the BotGuard execution output [botguardResponse].
     */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        environment.debug("BotGuard response received")
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"
        ) { responseBody ->
            environment.debug("GenerateIT response received")
            val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)

            // leave 10 minutes of margin just to be sure
            expirationInstant = environment.now().plusSeconds(expirationTimeInSeconds - 600)

            webView.evaluateJavascript(
                "this.integrityToken = $integrityToken"
            ) {
                environment.debug("initialization finished, expiration=${expirationTimeInSeconds}s")
                initializationComplete = true
                generatorEmitter.onSuccess(this)
            }
        }
    }
    //endregion

    //region Obtaining poTokens
    override fun generatePoToken(identifier: String): Single<String> = Single.create { emitter ->
        environment.debug("generatePoToken() called")
        runOnMainThread(environment, emitter) {
            if (closed) {
                emitter.tryOnError(PoTokenException("PoToken generator is closed"))
                return@runOnMainThread
            }
            addPoTokenEmitter(identifier, emitter)
            emitter.setCancellable {
                synchronized(poTokenEmitters) {
                    poTokenEmitters.removeAll { it.second === emitter }
                }
            }
            val u8Identifier = stringToU8(identifier)
            try {
                webView.evaluateJavascript(
                    """try {
                        identifier = "$identifier"
                        u8Identifier = $u8Identifier
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }"""
                ) {}
            } catch (error: Exception) {
                popPoTokenEmitter(identifier)?.tryOnError(error)
            }
        }
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] when an error occurs in calling the
     * JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        environment.error("obtainPoToken error from JavaScript")
        popPoTokenEmitter(identifier)?.tryOnError(buildExceptionForJsError(error))
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] with the original identifier and the
     * result of the JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        environment.debug("Generated encoded poToken")
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            popPoTokenEmitter(identifier)?.tryOnError(t)
            return
        }

        environment.debug("Decoded poToken")
        popPoTokenEmitter(identifier)?.onSuccess(poToken)
    }

    override fun isExpired(): Boolean {
        return environment.now().isAfter(expirationInstant)
    }
    //endregion

    //region Handling multiple emitters

    /**
     * Adds the ([identifier], [emitter]) pair to the [poTokenEmitters] list. This makes it so that
     * multiple poToken requests can be generated invparallel, and the results will be notified to
     * the right emitters.
     */
    private fun addPoTokenEmitter(identifier: String, emitter: SingleEmitter<String>) {
        synchronized(poTokenEmitters) {
            poTokenEmitters.add(Pair(identifier, emitter))
        }
    }

    /**
     * Extracts and removes from the [poTokenEmitters] list a [SingleEmitter] based on its
     * [identifier]. The emitter is supposed to be used immediately after to either signal a success
     * or an error.
     */
    private fun popPoTokenEmitter(identifier: String): SingleEmitter<String>? {
        return synchronized(poTokenEmitters) {
            poTokenEmitters.indexOfFirst { it.first == identifier }.takeIf { it >= 0 }?.let {
                poTokenEmitters.removeAt(it).second
            }
        }
    }

    /**
     * Clears [poTokenEmitters] and returns its previous contents. The emitters are supposed to be
     * used immediately after to either signal a success or an error.
     */
    private fun popAllPoTokenEmitters(): List<Pair<String, SingleEmitter<String>>> {
        return synchronized(poTokenEmitters) {
            val result = poTokenEmitters.toList()
            poTokenEmitters.clear()
            result
        }
    }
    //endregion

    //region Utils

    /**
     * Makes a POST request to [url] with the given [data] by setting the correct headers. Calls
     * [onInitializationErrorCloseAndCancel] in case of any network errors and also if the response
     * does not have HTTP code 200, therefore this is supposed to be used only during
     * initialization. Calls [handleResponseBody] with the response body if the response is
     * successful. The request is performed in the background and a disposable is added to
     * [disposables].
     */
    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        handleResponseBody: (String) -> Unit
    ) {
        disposables.add(
            environment.postBotguard(url, data)
                .observeOn(environment.mainScheduler)
                .subscribe(
                    { response ->
                        val httpCode = response.responseCode()
                        if (httpCode != 200) {
                            onInitializationErrorCloseAndCancel(
                                PoTokenException("Invalid response code: $httpCode")
                            )
                            return@subscribe
                        }
                        val responseBody = response.responseBody()
                        handleResponseBody(responseBody)
                    },
                    this::onInitializationErrorCloseAndCancel
                )
        )
    }

    /**
     * Handles any error happening during initialization, releasing resources and sending the error
     * to [generatorEmitter].
     */
    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        runOnMainThread(environment, generatorEmitter, runWhenDisposed = true) {
            generatorEmitter.tryOnError(error)
            close()
        }
    }

    /**
     * Releases all [webView] and [disposables] resources.
     */
    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        val error = PoTokenException("PoToken generator is closed")
        popAllPoTokenEmitters().forEach { (_, emitter) -> emitter.tryOnError(error) }
        generatorEmitter.tryOnError(error)
        disposables.dispose()

        webView.close()
    }
    //endregion

    companion object : PoTokenGenerator.Factory {
        // Request key used by the bundled BotGuard workflow.
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        internal const val JS_INTERFACE = "PoTokenWebView"

        override fun newPoTokenGenerator(context: Context): Single<PoTokenGenerator> = newPoTokenGenerator(AndroidPoTokenWebViewEnvironment(context))

        internal fun newPoTokenGenerator(
            environment: PoTokenWebViewEnvironment
        ): Single<PoTokenGenerator> = Single.create { emitter ->
            runOnMainThread(environment, emitter) {
                val potWv = PoTokenWebView(environment, emitter)
                potWv.loadHtmlAndObtainBotguard()
                emitter.setCancellable {
                    potWv.disposables.dispose()
                    if (!potWv.initializationComplete) {
                        environment.postToMain { potWv.close() }
                    }
                }
            }
        }

        /**
         * Runs [runnable] on the main thread using `Handler(Looper.getMainLooper()).post()`, and
         * if the `post` fails emits an error on [emitterIfPostFails].
         */
        private fun runOnMainThread(
            environment: PoTokenWebViewEnvironment,
            emitterIfPostFails: SingleEmitter<out Any>,
            runWhenDisposed: Boolean = false,
            runnable: Runnable
        ) {
            if (!environment.postToMain {
                    if (runWhenDisposed || !emitterIfPostFails.isDisposed) runnable.run()
                }
            ) {
                emitterIfPostFails.tryOnError(PoTokenException("Could not run on main thread"))
            }
        }
    }
}
