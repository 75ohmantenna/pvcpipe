package org.schabi.newpipe.local.feed.service

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList

class FeedErrorContextTest {
    private val odysee = ServiceList.Odysee.serviceId
    private val youtube = ServiceList.YouTube.serviceId

    private fun failure(id: Int, url: String) = FeedRefresh.RequestException(12, id, url, SocketTimeoutException("timeout"))

    @Test
    fun `a failed Odysee subscription retains its service request and cause`() {
        val url = "https://odysee.com/@Example:8"
        val cause = SocketTimeoutException("timeout")
        val error = FeedRefresh.RequestException(12, odysee, url, cause)
        val details = FeedErrorContext(listOf(error))

        assertEquals(odysee, details.serviceId)
        assertEquals("$odysee:$url", details.request)
        assertEquals(url, error.url)
        assertEquals(12L, error.subscriptionId)
        assertSame(cause, error.cause)
        assertTrue(error.stackTraceToString().contains("SocketTimeoutException: timeout"))
    }

    @Test
    fun `same service errors retain distinct channels without repeating the same request`() {
        val first = failure(odysee, "https://odysee.com/@One:1")
        val second = failure(odysee, "https://odysee.com/@Two:2")
        val details = FeedErrorContext(listOf(first, second, first))

        assertEquals(odysee, details.serviceId)
        assertEquals("${first.message}\n${second.message}", details.request)
    }

    @Test
    fun `mixed service failures do not get attributed to the first service`() {
        val first = failure(odysee, "https://odysee.com/@One:1")
        val second = failure(youtube, "https://www.youtube.com/@example")
        val details = FeedErrorContext(listOf(first, second))

        assertNull(details.serviceId)
        assertEquals("${first.message}\n${second.message}", details.request)
    }

    @Test
    fun `unattributed failures prevent inventing a shared service`() {
        val details = FeedErrorContext(listOf(failure(odysee, "https://odysee.com/@One:1"), IllegalStateException("storage")))
        assertNull(details.serviceId)
        assertEquals("$odysee:https://odysee.com/@One:1", details.request)
        assertEquals("Loading feed", FeedErrorContext(listOf(IllegalStateException("storage"))).request)
    }

    @Test
    fun `large refresh failures keep bounded request metadata`() {
        val errors = (1..2000).map { failure(odysee, "https://odysee.com/@Example$it:1") }
        val details = FeedErrorContext(errors)
        assertEquals(odysee, details.serviceId)
        assertTrue(details.request.contains("@Example1:1"))
        assertTrue(details.request.endsWith("..."))
        assertTrue(details.request.length <= 4000)

        val longRequest = FeedErrorContext(listOf(failure(odysee, "https://odysee.com/" + "a".repeat(8000))))
        assertEquals(4000, longRequest.request.length)
        assertTrue(longRequest.request.endsWith("…"))
    }
}
