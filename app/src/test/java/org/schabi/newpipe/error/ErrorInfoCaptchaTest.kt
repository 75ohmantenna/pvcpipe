package org.schabi.newpipe.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class ErrorInfoCaptchaTest {
    private fun captcha(url: String?) = ReCaptchaException("reCaptcha Challenge requested", url)

    @Test
    fun `a 429 from YouTube or Google can be solved in the app`() {
        for (url in listOf(
            "https://www.youtube.com/watch?v=abc",
            "https://youtube.com/",
            "https://music.youtube.com/watch?v=abc",
            "https://youtubei.googleapis.com/youtubei/v1/player",
            "https://www.google.com/sorry/index?continue=x",
            "https://rr1---sn-abc.googlevideo.com/videoplayback?x=1"
        )) {
            assertEquals(url, ErrorInfo.solvableCaptchaUrl(captcha(url)))
            assertTrue(ErrorInfo.isReportable(captcha(url)))
        }
    }

    @Test
    fun `a 429 from any other site is a rate limit and offers no captcha`() {
        for (url in listOf(
            "https://player.odycdn.com/v6/streams/abc/def.mp4",
            "https://api.na-backend.odysee.com/api/v1/proxy",
            "https://rumble.com/embedJS/u3/?request=video",
            "https://api.bitchute.com/api/beta/video",
            "https://api-v2.soundcloud.com/tracks",
            "https://sponsor.ajay.app/api/skipSegments/abcd"
        )) {
            assertNull(ErrorInfo.solvableCaptchaUrl(captcha(url)))
            assertFalse(ErrorInfo.isReportable(captcha(url)))
            assertTrue(ErrorInfo.isRetryable(captcha(url)))
        }
    }

    @Test
    fun `a host that only looks like YouTube is not YouTube`() {
        for (url in listOf(
            "https://youtube.com.evil.example/watch?v=abc",
            "https://notyoutube.com/",
            "https://evil.example/youtube.com/",
            "https://evil.example/?u=https://www.youtube.com/",
            "https://google.com@evil.example/"
        )) {
            assertNull(url, ErrorInfo.solvableCaptchaUrl(captcha(url)))
        }
    }

    @Test
    fun `an address that cannot be read is no captcha`() {
        assertNull(ErrorInfo.solvableCaptchaUrl(captcha(null)))
        assertNull(ErrorInfo.solvableCaptchaUrl(captcha("")))
        assertNull(ErrorInfo.solvableCaptchaUrl(captcha("not an address")))
        assertNull(ErrorInfo.solvableCaptchaUrl(java.io.IOException("HTTP 429")))
        assertNull(ErrorInfo.solvableCaptchaUrl(null))
    }
}
