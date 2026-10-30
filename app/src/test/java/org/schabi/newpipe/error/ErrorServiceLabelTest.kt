/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.error

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Parcel
import android.text.Spanned
import android.text.style.URLSpan
import androidx.core.text.HtmlCompat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo.Companion.ErrorMessage
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.util.text.getText

/** Real HTML formatter with controlled resources; packaged strings/activity are tested natively. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class ErrorServiceLabelTest {
    private lateinit var resources: Resources
    private val context get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getResources() = this@ErrorServiceLabelTest.resources
    }
    private val unknownIds = listOf(null, -1, 2, 3, 4, 99, Int.MAX_VALUE)

    @Before
    fun resources() {
        resources = spy(RuntimeEnvironment.getApplication().resources)
        templates(resources)
        doReturn("Unknown service").`when`(resources).getString(R.string.unknown_service)
    }

    private fun templates(resources: Resources) {
        doReturn("%1\$s refuses requests").`when`(resources).getText(R.string.too_many_requests)
        doReturn("%1\$s provides this reason: %2\$s").`when`(resources).getText(R.string.account_terminated_service_provides_reason)
        doReturn(HtmlCompat.fromHtml("%1\$s requires sign-in. <a href=\"%2\$s\">FAQ</a>", HtmlCompat.FROM_HTML_MODE_LEGACY))
            .`when`(resources).getText(R.string.sign_in_confirm_not_bot_error)
    }

    @Test
    fun `request refusal names missing retired and invalid services readably`() {
        for (id in unknownIds) {
            val text = message(ReCaptchaException("fixture refusal", "https://fixture.invalid"), id)
            assertTrue("service=$id text=$text", text.contains("Unknown service"))
            assertFalse(text.contains(ErrorInfo.SERVICE_NONE))
        }
    }

    @Test
    fun `account termination names missing retired and invalid services readably`() {
        for (id in unknownIds) {
            val text = message(AccountTerminatedException("fixture reason"), id)
            assertTrue("service=$id text=$text", text.contains("Unknown service"))
            assertTrue(text.contains("fixture reason"))
        }
    }

    @Test
    fun `sign-in restriction names missing retired and invalid services readably`() {
        for (id in unknownIds) {
            val text = message(SignInConfirmNotBotException("fixture restriction"), id)
            assertTrue("service=$id text=$text", text.contains("Unknown service"))
            val rendered = ErrorInfo.getMessage(SignInConfirmNotBotException("fixture restriction"), UserAction.USER_REPORT, id).getText(context) as Spanned
            assertTrue(rendered.getSpans(0, rendered.length, URLSpan::class.java).any { it.url == ErrorInfo.YOUTUBE_IP_BAN_FAQ_URL })
        }
    }

    @Test
    fun `supported names reason and FAQ formatting stay unchanged`() {
        for (service in ServiceList.all()) {
            val name = service.serviceInfo.name
            assertEquals(name, captured(service.serviceId).getServiceName())
            assertEquals(name, captured(service.serviceId).getServiceName(context))
            assertEquals(
                context.resources.getText(R.string.too_many_requests, *arrayOf(name)).toString(),
                message(ReCaptchaException("fixture refusal", "https://fixture.invalid"), service.serviceId)
            )
            assertEquals(
                context.resources.getText(R.string.account_terminated_service_provides_reason, name, "fixture reason").toString(),
                message(AccountTerminatedException("fixture reason"), service.serviceId)
            )
            assertEquals(
                context.resources.getText(R.string.sign_in_confirm_not_bot_error, name, ErrorInfo.YOUTUBE_IP_BAN_FAQ_URL).toString(),
                message(SignInConfirmNotBotException("fixture restriction"), service.serviceId)
            )
        }
        verify(resources, never()).getString(R.string.unknown_service)
    }

    @Test
    fun `unknown diagnostic names and original ids remain unchanged`() {
        for (id in unknownIds) {
            val info = captured(id)
            assertEquals(id, info.serviceId)
            assertEquals(ErrorInfo.SERVICE_NONE, info.getServiceName())
            assertEquals("Unknown service", info.getServiceName(context))
        }
    }

    @Test
    fun `a delayed error resolves the label from the current context each time`() {
        val info = captured(null)
        val english = localized(Locale.US, "resource-en")
        val french = localized(Locale.FRANCE, "resource-fr")
        assertEquals("en", english.resources.configuration.locales[0].language)
        assertEquals("fr", french.resources.configuration.locales[0].language)
        for ((context, label) in listOf(english to "resource-en", french to "resource-fr", english to "resource-en")) {
            assertEquals(label, info.getServiceName(context))
            assertTrue(info.getMessage(context).toString().startsWith(label))
            assertEquals(ErrorInfo.SERVICE_NONE, info.getServiceName())
        }
    }

    @Test
    @Suppress("DEPRECATION") // Exercise the API 26 parcel transport.
    fun `parcel transport preserves deferred localization and diagnostic identity`() {
        for (id in unknownIds) {
            val info = captured(id)
            assertTrue(info.getMessage(context).toString().contains("Unknown service"))
            val parcel = Parcel.obtain()
            val restored = try {
                parcel.writeParcelable(info, 0)
                parcel.setDataPosition(0)
                parcel.readParcelable<ErrorInfo>(ErrorInfo::class.java.classLoader)!!
            } finally {
                parcel.recycle()
            }
            val french = localized(Locale.FRANCE, "resource-fr")
            assertEquals(id, restored.serviceId)
            assertEquals(ErrorInfo.SERVICE_NONE, restored.getServiceName())
            assertEquals("resource-fr", restored.getServiceName(french))
            assertTrue(restored.getMessage(french).toString().startsWith("resource-fr"))
        }
    }

    @Test
    fun `an exception reason equal to the diagnostic token is not localized`() {
        for (id in listOf(null, ServiceList.YouTube.serviceId)) {
            val label = if (id == null) "Unknown service" else "YouTube"
            val expected = context.resources.getText(
                R.string.account_terminated_service_provides_reason,
                *arrayOf(label, ErrorInfo.SERVICE_NONE)
            ).toString()
            assertEquals(expected, message(AccountTerminatedException(ErrorInfo.SERVICE_NONE), id))
        }
    }

    @Test
    fun `a non-service formatting argument retains existing rendering`() {
        doReturn("Value: %1\$s").`when`(resources).getText(R.string.player_http_invalid_status)
        val expected = context.resources.getText(R.string.player_http_invalid_status, *arrayOf(ErrorInfo.SERVICE_NONE)).toString()
        assertEquals(expected, ErrorMessage(R.string.player_http_invalid_status, ErrorInfo.SERVICE_NONE).getText(context).toString())
    }

    private fun localized(locale: Locale, label: String): ContextWrapper {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
        val base = app.createConfigurationContext(config)
        val resources = spy(base.resources)
        templates(resources)
        doReturn(label).`when`(resources).getString(R.string.unknown_service)
        return object : ContextWrapper(base) {
            override fun getResources() = resources
        }
    }

    private fun captured(id: Int?): ErrorInfo {
        // Already-captured fixture details avoid starting the optional log-capture subsystem.
        // Native tests exercise public construction, the actual activity and report builders.
        val constructor = ErrorInfo::class.java.declaredConstructors.single { it.parameterCount == 10 }
        constructor.isAccessible = true
        return constructor.newInstance(
            arrayOf("fixture trace"), UserAction.USER_REPORT, "fixture request", id,
            ErrorInfo.getMessage(ReCaptchaException("fixture refusal", "https://fixture.invalid"), UserAction.USER_REPORT, id),
            true, true, null, null, 1L
        ) as ErrorInfo
    }

    private fun message(throwable: Throwable, id: Int?) = ErrorInfo.getMessage(throwable, UserAction.USER_REPORT, id).getText(context).toString()
}
