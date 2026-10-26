package org.schabi.newpipe.about

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseFormattingTest {
    @Test
    fun copyrightAndNoticeAreReadableTextRatherThanMarkup() {
        val component = SoftwareComponent(
            "Example <library>",
            "2015 - 2016",
            "A & B <authors>",
            "https://example.com",
            StandardLicenses.MIT
        )
        val html = formatLicenseHtml(
            "<html><head></head><body><p>Permission notice</p></body></html>",
            component,
            "Copyright 2015 A & B\n<not-an-html-element>"
        )
        val document = Jsoup.parse(html)
        assertEquals("Example <library>", document.selectFirst("h2")!!.text())
        assertTrue(document.body().text().contains("Copyright © 2015 - 2016 A & B <authors>"))
        assertEquals(
            "Copyright 2015 A & B\n<not-an-html-element>",
            document.selectFirst("pre")!!.wholeText()
        )
        assertTrue(document.body().text().contains("Permission notice"))
        assertTrue(document.select("library, authors, not-an-html-element").isEmpty())
    }

    @Test
    fun upstreamWithoutAYearKeepsItsCreditWithoutInventingADate() {
        val component = SoftwareComponent(
            "Example",
            "",
            "Example contributors",
            "https://example.com",
            StandardLicenses.MIT
        )
        val document = Jsoup.parse(formatLicenseHtml("<html><body></body></html>", component))
        assertEquals("Copyright © Example contributors", document.selectFirst("p")!!.text())
        assertFalse(document.body().text().contains("2026"))
        assertTrue(document.select("pre").isEmpty())
    }
}
