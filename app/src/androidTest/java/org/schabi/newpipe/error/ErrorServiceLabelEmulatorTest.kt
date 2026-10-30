/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.error

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Parcel
import android.text.Spanned
import android.text.style.URLSpan
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/** Real parcel/activity/export checks; explicitly guarded disposable-emulator invocation only. */
@RunWith(AndroidJUnit4::class)
class ErrorServiceLabelEmulatorTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preferences: SharedPreferences
    private val saved = linkedMapOf<String, Boolean?>()

    @Before
    fun requireDisposableAndDisableCapture() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires disposable invocation", arguments.getString("serviceLabelDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(Build.VERSION.SDK_INT == 26)
        check(context.packageName == "org.seventyfiveohmantenna.pvcpipe.debug")
        check(InstrumentationRegistry.getInstrumentation().context.packageName == context.packageName + ".test")
        val nonce = requireNotNull(arguments.getString("serviceLabelNonce"))
        check(nonce.matches(Regex("[a-f0-9]{32}")))
        check(File(context.filesDir, "unknown-service-label-allow").readText() == nonce)
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val capture = context.getString(R.string.pvc_settings_debug_logcat_dumper_key)
        for (key in listOf(capture, context.getString(R.string.disable_error_reports_key), MainActivity.KEY_IS_IN_BACKGROUND)) {
            saved[key] = if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        }
        check(preferences.edit().putBoolean(capture, false).commit())
    }

    @After
    fun restorePreferences() {
        if (!::preferences.isInitialized) return
        val editor = preferences.edit()
        saved.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putBoolean(key, value) }
        check(editor.commit())
        saved.forEach { (key, value) ->
            assertEquals(value != null, preferences.contains(key))
            if (value != null) assertEquals(value, preferences.getBoolean(key, !value))
        }
    }

    @Test
    fun unknownLabelsDisplayAfterParcelWhileExportsKeepDiagnosticToken() {
        val ids = listOf(null, -1, 2, 3, 4, 99, Int.MAX_VALUE)
        for (id in ids) {
            val throwable = when (id) {
                2 -> AccountTerminatedException("fixture reason")
                3 -> SignInConfirmNotBotException("fixture sign-in")
                else -> ReCaptchaException("fixture refusal", "https://fixture.invalid")
            }
            val info = parcel(ErrorInfo(throwable, UserAction.USER_REPORT, "synthetic service-label request", id))
            assertEquals(id, info.serviceId)
            assertEquals(ErrorInfo.SERVICE_NONE, info.getServiceName())
            withErrorReports(info) { activity, markdown, json ->
                val label = activity.getString(R.string.unknown_service)
                assertEquals("Unknown service", label)
                assertEquals(label, info.getServiceName(activity))
                val message = activity.findViewById<TextView>(R.id.errorMessageView).text.toString()
                assertTrue("service=$id text=$message", message.contains(label))
                assertFalse(message.contains(ErrorInfo.SERVICE_NONE))
                if (throwable is AccountTerminatedException) assertTrue(message.contains("fixture reason"))
                if (throwable is SignInConfirmNotBotException) {
                    val styled = activity.findViewById<TextView>(R.id.errorMessageView).text as Spanned
                    assertTrue(styled.getSpans(0, styled.length, URLSpan::class.java).any { it.url == ErrorInfo.YOUTUBE_IP_BAN_FAQ_URL })
                }
                val details = activity.findViewById<TextView>(R.id.errorInfosView).text.toString()
                assertEquals(label, details.lineSequence().elementAt(5))
                assertFalse(details.contains(ErrorInfo.SERVICE_NONE))
                assertEquals(ErrorInfo.SERVICE_NONE, json.getString("service"))
                assertEquals(listOf("* __Service:__ ${ErrorInfo.SERVICE_NONE}"), markdown.lineSequence().filter { it.startsWith("* __Service:__ ") }.toList())

                // No French resource is bundled: a French context must use the English fallback.
                val config = Configuration(activity.resources.configuration).apply { setLocale(Locale.FRANCE) }
                val french = activity.createConfigurationContext(config)
                assertEquals("fr", french.resources.configuration.locales[0].language)
                assertEquals("Unknown service", info.getServiceName(french))
                assertTrue(info.getMessage(french).toString().contains("Unknown service"))
            }
        }
    }

    @Test
    fun manualMessageWithoutServiceStillParcels() {
        val info = parcel(ErrorInfo(arrayOf("synthetic trace"), UserAction.UI_ERROR, "synthetic service-label request", null, R.string.app_ui_crash))
        assertEquals(context.getString(R.string.app_ui_crash), info.getMessage(context).toString())
        assertEquals(ErrorInfo.SERVICE_NONE, info.getServiceName())
        assertEquals("Unknown service", info.getServiceName(context))
    }

    @Test
    fun supportedServiceDisplayAndExportsKeepTheirName() {
        for (service in listOf(ServiceList.YouTube, ServiceList.Kick)) {
            val info = parcel(ErrorInfo(ReCaptchaException("fixture refusal", "https://fixture.invalid"), UserAction.USER_REPORT, "synthetic service-label request", service.serviceId))
            withErrorReports(info) { activity, markdown, json ->
                val name = service.serviceInfo.name
                assertEquals(name, info.getServiceName(activity))
                assertEquals(name, activity.findViewById<TextView>(R.id.errorInfosView).text.toString().lineSequence().elementAt(5))
                assertEquals(name, json.getString("service"))
                assertTrue(markdown.lineSequence().any { it == "* __Service:__ $name" })
                assertTrue(activity.findViewById<TextView>(R.id.errorMessageView).text.toString().contains(name))
            }
        }
    }

    @Suppress("DEPRECATION") // Exercise the API 26 parcel transport.
    private fun parcel(info: ErrorInfo): ErrorInfo {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(info, 0)
            parcel.setDataPosition(0)
            parcel.readParcelable<ErrorInfo>(ErrorInfo::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }
}
