package org.schabi.newpipe.util.potoken

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.annotation.MainThread
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.exceptions.Exceptions
import java.time.Instant

class PoTokenWebView private constructor(
    private val environment: PoTokenWebViewEnvironment,
    // to be used exactly once only during initialization!
    private val generatorEmitter: SingleEmitter<PoTokenGenerator>
) : PoTokenGenerator {
    private val webView = environment.createBrowser(this) { line ->
        val exception = BadWebViewException("Uncaught JavaScript error at line $line")
        onInitializationErrorCloseAndCancel(exception)
        popAllPoTokenEmitters().forEach { emitter -> emitter.tryOnError(exception) }
    }
    private val disposables = CompositeDisposable() // used only during initialization
    private val poTokenEmitters = mutableMapOf<String, SingleEmitter<String>>()
    private var nextRequestId = 0L
    private enum class Initialization {
        LOADING_HTML,
        WAITING_FOR_PAGE,
        WAITING_FOR_CHALLENGE,
        WAITING_FOR_BOTGUARD,
        WAITING_FOR_INTEGRITY,
        INSTALLING,
        READY,
        CLOSED
    }
    private var initialization = Initialization.LOADING_HTML
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
                        handleInitialization {
                            initialization = Initialization.WAITING_FOR_PAGE
                            webView.loadHtml(
                                html.replaceFirst(
                                    "</script>",
                                    // calls downloadAndRunBotguard() when the page has finished loading
                                    "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
                                )
                            )
                        }
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
        runInitializationOnMain {
            if (initialization != Initialization.WAITING_FOR_PAGE) return@runInitializationOnMain
            initialization = Initialization.WAITING_FOR_CHALLENGE
            environment.debug("downloadAndRunBotguard() called")
            makeBotguardServiceRequest(
                "https://www.youtube.com/api/jnn/v1/Create",
                "[ \"$REQUEST_KEY\" ]"
            ) { responseBody ->
                if (initialization != Initialization.WAITING_FOR_CHALLENGE) return@makeBotguardServiceRequest
                val parsedChallengeData = parseChallengeData(responseBody)
                initialization = Initialization.WAITING_FOR_BOTGUARD
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
        runInitializationOnMain {
            if (initialization != Initialization.WAITING_FOR_BOTGUARD) return@runInitializationOnMain
            initialization = Initialization.WAITING_FOR_INTEGRITY
            environment.debug("BotGuard response received")
            makeBotguardServiceRequest(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"
            ) { responseBody ->
                if (initialization != Initialization.WAITING_FOR_INTEGRITY) return@makeBotguardServiceRequest
                environment.debug("GenerateIT response received")
                val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
                expirationInstant = environment.now().plusSeconds(expirationTimeInSeconds - 600)
                initialization = Initialization.INSTALLING
                webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                    runInitializationOnMain install@{
                        if (initialization != Initialization.INSTALLING) return@install
                        environment.debug("initialization finished, expiration=${expirationTimeInSeconds}s")
                        initialization = Initialization.READY
                        initializationComplete = true
                        generatorEmitter.onSuccess(this)
                    }
                }
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
            val requestId = addPoTokenEmitter(emitter)
            emitter.setCancellable {
                synchronized(poTokenEmitters) {
                    poTokenEmitters.remove(requestId)
                }
            }
            val u8Identifier = stringToU8(identifier)
            try {
                webView.evaluateJavascript(
                    """(function () {
                        var requestId = "$requestId"
                        try {
                            var u8Identifier = $u8Identifier
                            var poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                            var poTokenU8String = ""
                            for (var i = 0; i < poTokenU8.length; i++) {
                                if (i != 0) poTokenU8String += ","
                                poTokenU8String += poTokenU8[i]
                            }
                            $JS_INTERFACE.onObtainPoTokenResult(requestId, poTokenU8String)
                        } catch (error) {
                            $JS_INTERFACE.onObtainPoTokenError(requestId, error + "\n" + error.stack)
                        }
                    })()"""
                ) {}
            } catch (error: Throwable) {
                Exceptions.throwIfFatal(error)
                popPoTokenEmitter(requestId)?.tryOnError(error)
            }
        }
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] when an error occurs in calling the
     * JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenError(requestId: String, error: String) {
        environment.error("obtainPoToken error from JavaScript")
        popPoTokenEmitter(requestId)?.tryOnError(buildExceptionForJsError(error))
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] with its unique request ID and the
     * result of the JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenResult(requestId: String, poTokenU8: String) {
        environment.debug("Generated encoded poToken")
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            Exceptions.throwIfFatal(t)
            popPoTokenEmitter(requestId)?.tryOnError(t)
            return
        }

        environment.debug("Decoded poToken")
        popPoTokenEmitter(requestId)?.onSuccess(poToken)
    }

    override fun isExpired(): Boolean {
        return environment.now().isAfter(expirationInstant)
    }
    //endregion

    //region Handling multiple emitters
    private fun addPoTokenEmitter(emitter: SingleEmitter<String>): String = synchronized(poTokenEmitters) {
        (++nextRequestId).toString().also { poTokenEmitters[it] = emitter }
    }

    private fun popPoTokenEmitter(requestId: String): SingleEmitter<String>? = synchronized(poTokenEmitters) {
        poTokenEmitters.remove(requestId)
    }

    private fun popAllPoTokenEmitters(): List<SingleEmitter<String>> = synchronized(poTokenEmitters) {
        poTokenEmitters.values.toList().also { poTokenEmitters.clear() }
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
                        handleInitialization {
                            val httpCode = response.responseCode()
                            if (httpCode != 200) {
                                onInitializationErrorCloseAndCancel(
                                    PoTokenException("Invalid response code: $httpCode")
                                )
                                return@handleInitialization
                            }
                            val responseBody = response.responseBody()
                            handleResponseBody(responseBody)
                        }
                    },
                    this::onInitializationErrorCloseAndCancel
                )
        )
    }

    private fun runInitializationOnMain(action: () -> Unit) {
        runOnMainThread(environment, generatorEmitter, runWhenDisposed = true) {
            handleInitialization(action)
        }
    }

    private fun handleInitialization(action: () -> Unit) {
        if (closed || initializationComplete) return
        if (generatorEmitter.isDisposed) {
            close()
            return
        }
        try {
            action()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            onInitializationErrorCloseAndCancel(error)
        }
    }

    /**
     * Handles any error happening during initialization, releasing resources and sending the error
     * to [generatorEmitter].
     */
    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        runOnMainThread(environment, generatorEmitter, runWhenDisposed = true) {
            if (closed) return@runOnMainThread
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
        initialization = Initialization.CLOSED
        val error = PoTokenException("PoToken generator is closed")
        popAllPoTokenEmitters().forEach { emitter -> emitter.tryOnError(error) }
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
                var potWv: PoTokenWebView? = null
                try {
                    val generator = PoTokenWebView(environment, emitter)
                    potWv = generator
                    emitter.setCancellable {
                        generator.disposables.dispose()
                        if (!generator.initializationComplete) {
                            environment.postToMain { generator.close() }
                        }
                    }
                    if (!emitter.isDisposed) generator.loadHtmlAndObtainBotguard()
                } catch (error: Throwable) {
                    Exceptions.throwIfFatal(error)
                    emitter.tryOnError(error)
                    potWv?.close()
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
