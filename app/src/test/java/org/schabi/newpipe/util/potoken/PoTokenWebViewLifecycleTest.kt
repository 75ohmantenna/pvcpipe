package org.schabi.newpipe.util.potoken

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PoTokenWebViewLifecycleTest {
    @Test
    fun closingFailsPendingRequestsAndRejectsFurtherGeneration() {
        val queued = ArrayDeque<Runnable>()
        mockStatic(Looper::class.java).use { looper ->
            looper.`when`<Looper> { Looper.getMainLooper() }.thenReturn(mock(Looper::class.java))
            mockStatic(Log::class.java).use {
                mockConstruction(Handler::class.java) { handler, _ ->
                    `when`(handler.post(any(Runnable::class.java))).thenAnswer { call ->
                        queued.add(call.getArgument(0))
                        true
                    }
                }.use {
                    val generator = mock(PoTokenWebView::class.java, CALLS_REAL_METHODS)
                    val webView = mock(WebView::class.java)
                    field(generator, "webView", webView)
                    field(generator, "disposables", CompositeDisposable())
                    field(generator, "poTokenEmitters", mutableListOf<Pair<String, SingleEmitter<String>>>())
                    field(generator, "generatorEmitter", mock(SingleEmitter::class.java))
                    field(generator, "initializationComplete", true)
                    val pending = generator.generatePoToken("first").test()
                    queued.removeFirst().run()
                    generator.close()
                    pending.assertError(PoTokenException::class.java)
                    generator.close()
                    val late = generator.generatePoToken("late").test()
                    queued.removeFirst().run()
                    late.assertError(PoTokenException::class.java)
                    verify(webView, times(1)).destroy()
                    val canceled = generator.generatePoToken("canceled").test()
                    canceled.dispose()
                    queued.removeFirst().run()
                    assertEquals(0, emitters(generator).size)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun emitters(generator: PoTokenWebView): List<Any> {
        val field = PoTokenWebView::class.java.getDeclaredField("poTokenEmitters")
        field.isAccessible = true
        return field.get(generator) as List<Any>
    }

    private fun field(generator: PoTokenWebView, name: String, value: Any) {
        val field = PoTokenWebView::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(generator, value)
    }
}
