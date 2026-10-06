package org.schabi.newpipe.util.potoken

import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.observers.TestObserver
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.SingleSubject
import java.time.Instant
import java.util.concurrent.Executor
import org.schabi.newpipe.extractor.downloader.Response

/** Scripted external transport; the generator still performs its complete initialization. */
internal class PoTokenWebViewTestEnvironment : PoTokenWebViewEnvironment, PoTokenBrowser {
    data class Request(val url: String, val body: String, val response: SingleSubject<Response>)
    data class Evaluation(val script: String, val complete: () -> Unit)
    data class TokenCall(val identifier: String, val callbackIdentity: String)

    private val queued = ArrayDeque<Runnable>()
    override val mainScheduler: Scheduler = Schedulers.from(Executor { queued.add(it) })
    val requests = mutableListOf<Request>()
    val evaluations = mutableListOf<Evaluation>()
    val tokenCalls = mutableListOf<TokenCall>()
    lateinit var bridge: PoTokenWebView
    lateinit var consoleError: (Int) -> Unit
    var html = ""
    var closeCount = 0
    var acceptPosts = true
    var now = Instant.parse("2026-01-01T00:00:00Z")
    var htmlResult: Single<String> = Single.just("<html><script>function runBotGuard() {}</script></html>")
    var evaluationError: Exception? = null

    override fun readHtml(): Single<String> = htmlResult
    override fun postBotguard(url: String, data: String): Single<Response> = SingleSubject.create<Response>().also { requests.add(Request(url, data, it)) }

    override fun createBrowser(bridge: PoTokenWebView, onConsoleError: (Int) -> Unit): PoTokenBrowser {
        this.bridge = bridge
        consoleError = onConsoleError
        return this
    }

    override fun postToMain(runnable: Runnable): Boolean {
        if (!acceptPosts) return false
        queued.add(runnable)
        return true
    }

    override fun now(): Instant = now
    override fun debug(message: String) = Unit
    override fun error(message: String) = Unit
    override fun loadHtml(html: String) {
        this.html = html
    }

    override fun evaluateJavascript(script: String, onComplete: () -> Unit) {
        evaluationError?.let { throw it }
        evaluations.add(Evaluation(script, onComplete))
        if (script.contains("obtainPoToken(webPoSignalOutput")) {
            val identifier = Regex("identifier = \"([^\"]*)\"").find(script)!!.groupValues[1]
            val requestId = Regex("requestId = \"([^\"]*)\"").find(script)?.groupValues?.get(1)
            tokenCalls.add(TokenCall(identifier, requestId ?: identifier))
        }
    }

    override fun close() {
        closeCount++
    }

    fun runMain() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    fun start(): TestObserver<PoTokenGenerator> = PoTokenWebView.newPoTokenGenerator(this).test().also { runMain() }

    fun initialize(): PoTokenGenerator {
        val result = start()
        bridge.downloadAndRunBotguard()
        respond(0, CHALLENGE)
        bridge.onRunBotguardResult("botguard-response")
        respond(1, INTEGRITY_TOKEN)
        evaluations.last().complete()
        result.assertValueCount(1).assertComplete()
        return result.values().single()
    }

    fun respond(index: Int, body: String, code: Int = 200) {
        requests[index].response.onSuccess(Response(code, "", null, body, requests[index].url))
        runMain()
    }

    fun succeed(call: Int, tokenBytes: String) {
        bridge.onObtainPoTokenResult(tokenCalls[call].callbackIdentity, tokenBytes)
    }

    fun fail(call: Int, error: String) {
        bridge.onObtainPoTokenError(tokenCalls[call].callbackIdentity, error)
    }

    companion object {
        const val CHALLENGE = "[[\"message\",[\"interpreter-code\"],[\"https://example.test/interpreter\"],\"hash\",\"program\",\"global\",null,\"experiments\"]]"
        const val INTEGRITY_TOKEN = "[\"YWJj\",3600]"
    }
}
