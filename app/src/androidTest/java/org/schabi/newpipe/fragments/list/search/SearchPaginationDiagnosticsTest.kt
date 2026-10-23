package org.schabi.newpipe.fragments.list.search

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.withErrorReports
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.info_list.InfoListAdapter

/** Synthetic pagination only; run in a disposable installation. No network requests. */
class SearchPaginationDiagnosticsTest {
    @Test
    fun cookiesStayOutOfPaginationReportsAndAppLogs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val captureKey = context.getString(R.string.pvc_settings_debug_logcat_dumper_key)
        val original = if (preferences.contains(captureKey)) preferences.getBoolean(captureKey, true) else null
        val marker = "PVC_SYNTHETIC_PAGE_COOKIE_86f243e9"
        val cookies = linkedMapOf("synthetic_session" to marker)
        val page = Page("https://example.invalid/page/2", null, listOf("synthetic-page-id"), cookies, null)
        try {
            for (capture in listOf(true, false)) {
                check(preferences.edit().putBoolean(captureKey, capture).commit())
                // Clear only this disposable emulator's buffer; never collect pre-existing logs.
                instrumentation.uiAutomation.executeShellCommand("logcat -c").close()
                lateinit var info: ErrorInfo
                instrumentation.runOnMainSync {
                    val fragment = DiagnosticSearchFragment(context)
                    fragment.setPage(page)
                    val result = ListExtractor.InfoItemsPage<InfoItem>(
                        emptyList(),
                        Page("https://example.invalid/page/3"),
                        listOf(IllegalStateException("synthetic pagination failure"))
                    )
                    fragment.handleNextItems(result)
                    info = checkNotNull(fragment.reported)
                }
                assertSame(cookies, page.cookies)
                assertEquals(marker, page.cookies["synthetic_session"])
                assertTrue(info.request.contains("synthetic query"))
                assertTrue(info.request.contains("https://example.invalid/page/2"))
                assertTrue(info.request.contains("synthetic-page-id"))
                withErrorReports(info) { _, markdown, json ->
                    val leakedOutputs = linkedMapOf(
                        "request" to info.request,
                        "ErrorInfo serialization" to info.toString(),
                        "Markdown" to markdown,
                        "JSON" to json.toString()
                    ).filterValues { it.contains(marker) }.keys.toList()
                    assertEquals("Cookie-only marker leaked", emptyList<String>(), leakedOutputs)
                    assertEquals(info.request, json.getString("request"))
                }
                val descriptor = instrumentation.uiAutomation.executeShellCommand("logcat -d -v brief --pid=${android.os.Process.myPid()}")
                val logs = FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
                descriptor.close()
                assertTrue("The real error path must have logged the synthetic failure", logs.contains("synthetic pagination failure"))
                assertFalse("Cookie-only marker leaked into app log messages", logs.contains(marker))
            }
        } finally {
            val editor = preferences.edit()
            if (original == null) editor.remove(captureKey) else editor.putBoolean(captureKey, original)
            check(editor.commit())
        }
    }

    private class DiagnosticSearchFragment(context: Context) : SearchFragment() {
        var reported: ErrorInfo? = null

        init {
            searchString = "synthetic query"
            serviceId = ServiceList.YouTube.serviceId
            infoListAdapter = InfoListAdapter(context)
        }

        fun setPage(page: Page) {
            val field = SearchFragment::class.java.getDeclaredField("nextPage")
            field.isAccessible = true
            field.set(this, page)
        }

        override fun showListFooter(show: Boolean) = Unit

        override fun showSnackBarError(errorInfo: ErrorInfo) {
            reported = errorInfo
        }
    }
}
