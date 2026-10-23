/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription.workers

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.text.NumberFormat
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.R

/**
 * Uses packaged Android resources. Run only in disposable environments: tests change
 * AppCompat/framework language state; teardown is not an installation-isolation boundary.
 */
class SubscriptionImportPresentationTest {
    private lateinit var originalAppLocales: LocaleListCompat
    private lateinit var originalDefaultLocale: Locale

    @Before
    fun saveLocales() {
        originalAppLocales = AppCompatDelegate.getApplicationLocales()
        originalDefaultLocale = Locale.getDefault()
    }

    @After
    fun restoreLocales() {
        setAppLocales(originalAppLocales)
        Locale.setDefault(originalDefaultLocale)
    }

    @Test
    fun completionDistinguishesNoSkipsFromSingularAndPluralSkips() {
        val resources = localizedContext(Locale.US).resources
        val none = SubscriptionImportPresentation.completion(resources, 0)
        val one = SubscriptionImportPresentation.completion(resources, 1)
        val two = SubscriptionImportPresentation.completion(resources, 2)

        assertNotEquals(none, one)
        assertNotEquals(none, two)
        assertNotEquals(one.replace("1", "#"), two.replace("2", "#"))
    }

    @Test
    fun skippedCompletionSelectsPluralAndFormatsRawQuantity() {
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
            val resources = localizedContext(locale).resources
            for (skipped in listOf(1, 2, 11, 1_234_567, Int.MAX_VALUE)) {
                val expected = String.format(
                    locale,
                    resources.getQuantityString(R.plurals.import_subscriptions_skipped, skipped),
                    skipped
                )

                val completion = SubscriptionImportPresentation.completion(resources, skipped)

                assertEquals("$locale: skipped=$skipped", expected, completion)
                assertTrue(completion.contains(String.format(locale, "%d", skipped)))
                assertFalse(completion.contains('%'))
            }
        }
    }

    @Test
    fun progressFormatsBothPositionsWithGroupedAppLocaleNumbers() {
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
            setAppLocales(LocaleListCompat.create(locale))
            val context = localizedContext(locale)
            val format = NumberFormat.getIntegerInstance(locale)
            for ((current, total) in listOf(0 to 0, 999 to 1_000, 1_234_567 to Int.MAX_VALUE)) {
                val formattedCurrent = format.format(current)
                val formattedTotal = format.format(total)
                val expected = String.format(
                    locale,
                    context.getString(R.string.subscription_import_progress),
                    formattedCurrent,
                    formattedTotal
                )

                val progress = SubscriptionImportPresentation.progress(context, current, total)

                assertEquals("$locale: $current/$total", expected, progress)
                assertTrue(progress.contains(formattedCurrent))
                assertTrue(progress.contains(formattedTotal))
                assertFalse(progress.contains('%'))
                if (current != total) {
                    assertNotEquals(
                        String.format(
                            locale,
                            context.getString(R.string.subscription_import_progress),
                            formattedTotal,
                            formattedCurrent
                        ),
                        progress
                    )
                }
                if (locale.language == "ar" && current >= 1_000) {
                    assertTrue(progress.any { it in '\u0660'..'\u0669' })
                    assertFalse(progress.any { it in '0'..'9' })
                }
            }
        }
    }

    @Test
    fun progressUsesAppUiLocaleRatherThanDefaultOrResourceLocale() {
        val uiLocale = Locale.forLanguageTag("ar-EG")
        setAppLocales(LocaleListCompat.create(uiLocale))
        // Keep both the process default and this context's resources deliberately different.
        Locale.setDefault(Locale.US)
        val context = localizedContext(Locale.GERMANY)
        val current = 1_234_567
        val total = Int.MAX_VALUE
        val uiFormat = NumberFormat.getIntegerInstance(uiLocale)

        val progress = SubscriptionImportPresentation.progress(context, current, total)

        assertEquals(
            context.getString(
                R.string.subscription_import_progress,
                uiFormat.format(current),
                uiFormat.format(total)
            ),
            progress
        )
        assertFalse(progress.contains(NumberFormat.getIntegerInstance(Locale.US).format(current)))
        assertFalse(progress.contains(NumberFormat.getIntegerInstance(Locale.GERMANY).format(current)))
        assertTrue(progress.any { it in '\u0660'..'\u0669' })
    }

    @Test
    fun progressUsesSavedUiLocaleBeforeAnAppCompatActivityStarts() {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        setAppLocales(LocaleListCompat.getEmptyLocaleList())
        Locale.setDefault(Locale.US)
        val context = localizedContext(Locale.US)
        val record = File.createTempFile("import-locale-", ".xml", context.cacheDir)
        try {
            record.writeText("""<locales application_locales="ar-EG"/>""")
            val isolatedContext = object : ContextWrapper(context) {
                override fun openFileInput(name: String): FileInputStream = FileInputStream(record)

                override fun deleteFile(name: String): Boolean = record.delete()
            }
            val uiFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ar-EG"))
            val current = 1_234_567
            val total = Int.MAX_VALUE

            val progress = SubscriptionImportPresentation.progress(isolatedContext, current, total)

            assertTrue(progress.contains(uiFormat.format(current)))
            assertTrue(progress.contains(uiFormat.format(total)))
            assertFalse(progress.any { it in '0'..'9' })
        } finally {
            record.delete()
        }
    }

    private fun localizedContext(locale: Locale): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }

    private fun setAppLocales(locales: LocaleListCompat) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
